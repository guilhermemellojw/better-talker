package com.bettertalker.app.ui.hub

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.repo.NotesRepository
import com.bettertalker.app.domain.speech.DiscourseType
import com.bettertalker.app.domain.speech.tribunaCapsuleFromJson
import com.bettertalker.app.ui.present.PresentReadiness
import com.bettertalker.app.ui.present.decodePresentSession
import com.bettertalker.app.ui.present.presentReadiness
import com.bettertalker.app.ui.stage.formatClock
import com.bettertalker.app.ui.stage.parseStageTurns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Card de nota nos hubs Treinar/Apresentar. */
data class HubNoteCard(
    val id: String,
    val title: String,
    val isDemonstration: Boolean,
    /** null = sem duração conhecida (nota vazia/legada). */
    val minutes: Int?,
    /** Modos de lista: mesmos dados do card da Home (data/preview/cor/fixada). */
    val updatedAt: Long = 0L,
    val preview: String = "",
    val pinned: Boolean = false,
    val colorArgb: Long = 0L,
    /** Apresentar: prontidão da Tribuna; null nos demais hubs. */
    val presentReadiness: PresentReadiness? = null,
    /** Apresentar: sessão salva ("Continuar da seção 2 · 01:23"). */
    val presentResumeLabel: String? = null,
)

/** Rótulo do tipo da nota no card ("Demonstração" × "Discurso"). Puro/testável. */
fun hubBadgeLabel(discourseType: String?): String =
    if (discourseType == DiscourseType.MINISTRY_PART.name ||
        discourseType == DiscourseType.FREE_DEMO.name
    ) "Demonstração" else "Discurso"

/**
 * Duração do card: soma das seções (mesma fonte do Ensaio —
 * StageViewModel.totalMinutes) com fallback do esboço importado. Puro/testável.
 */
fun hubMinutesOf(sectionMinutesSum: Int, outlineMinutes: Int?): Int? = when {
    sectionMinutesSum > 0 -> sectionMinutesSum
    (outlineMinutes ?: 0) > 0 -> outlineMinutes
    else -> null
}

/**
 * Hubs Treinar/Apresentar: lista de notas ativas com tipo e duração para a
 * escolha visual (substitui o atalho silencioso de "última nota").
 */
class HubViewModel(ctx: Context, db: AppDatabase) : ViewModel() {

    private val repo = NotesRepository(ctx.applicationContext, db)
    private val settings = com.bettertalker.app.data.prefs.SettingsStore(ctx.applicationContext)

    /** Modo de visualização GLOBAL (mesmo `home_view_mode` da Home). */
    val viewMode: StateFlow<com.bettertalker.app.ui.home.HomeViewMode> = settings.homeViewMode
        .map { com.bettertalker.app.ui.home.HomeViewMode.fromNameOrDefault(it) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            com.bettertalker.app.ui.home.HomeViewMode.GRID,
        )

    fun setViewMode(m: com.bettertalker.app.ui.home.HomeViewMode) = viewModelScope.launch {
        settings.setHomeViewMode(m.name)
    }

    private val refreshTick = MutableStateFlow(0)

    /** Recalcula prontidão/duração (ex.: ao voltar do editor/Tribuna). */
    fun refresh() { refreshTick.value += 1 }

    val cards: StateFlow<List<HubNoteCard>> = combine(
        repo.observeNotes(folderId = null, q = ""),
        refreshTick,
    ) { notes, _ ->
        notes.map { note ->
            val sections = db.speechSectionDao().forNote(note.id)
            val sectionSum = sections.sumOf { it.minutes }
            val outlineMinutes = db.outlineDao().getForNote(note.id)?.totalMinutes
            val demo = note.discourseType == DiscourseType.MINISTRY_PART.name ||
                note.discourseType == DiscourseType.FREE_DEMO.name
            val readySections = if (demo) {
                sections.count { entity -> parseStageTurns(entity.contentHtml).isNotEmpty() }
            } else {
                sections.count { entity ->
                    entity.tribunaJson?.let { tribunaCapsuleFromJson(it) } != null
                }
            }
            val saved = decodePresentSession(
                raw = settings.presentSession(note.id),
                totalSections = sections.size,
            )
            HubNoteCard(
                id = note.id,
                title = note.title.ifBlank { "Sem título" },
                isDemonstration = demo,
                minutes = hubMinutesOf(sectionSum, outlineMinutes),
                updatedAt = note.updatedAt,
                preview = note.mdText,
                pinned = note.pinned,
                colorArgb = note.colorArgb,
                presentReadiness = presentReadiness(sections.size, readySections),
                presentResumeLabel = saved?.let {
                    "Continuar da seção ${it.index + 1} · ${formatClock(it.elapsedSec)}"
                },
            )
        }
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    class Factory(private val ctx: Context, private val db: AppDatabase) :
        ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HubViewModel(ctx, db) as T
    }
}
