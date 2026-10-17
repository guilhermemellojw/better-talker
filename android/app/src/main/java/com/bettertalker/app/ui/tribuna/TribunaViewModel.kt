package com.bettertalker.app.ui.tribuna

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.SpeechSectionDao
import com.bettertalker.app.data.prefs.SettingsStore
import com.bettertalker.app.data.prefs.STAGE_FONT_DEFAULT_SP
import com.bettertalker.app.data.voice.TextToSpeechManager
import com.bettertalker.app.domain.speech.TribunaCapsule
import com.bettertalker.app.domain.speech.toJson
import com.bettertalker.app.domain.speech.tribunaCapsuleFromJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** #20-fix-b: seam de TTS (fake nos testes; produção = TextToSpeechManager). */
interface TribunaSpeaker {
    fun speak(text: String)
    fun stop()
    fun shutdown()
    val speaking: Flow<Boolean>
}

/** #20-fix-b: seam das preferências de fonte (reusa a chave do Ensaio). */
interface TribunaFontPrefs {
    val fontSp: Flow<Int>
    suspend fun setFontSp(v: Int)
}

/**
 * #20-fix-b: a Tribuna — mapa de memória por seção. Lê os `tribunaJson`,
 * edita inline com autosave debounced e lê SÓ a frase-âncora (o orador
 * completa o resto de coração).
 *
 * Testável sem Context/Room: DAO fake + seams (speaker/fontPrefs) +
 * debounceMs = 0 + runBlocking (mesmo padrão do SectionsController).
 */
class TribunaViewModel(
    private val dao: SpeechSectionDao,
    private val noteId: String,
    private val speaker: TribunaSpeaker,
    private val fontPrefs: TribunaFontPrefs,
    private val debounceMs: Long = 400L,
    /** Testes passam um escopo; produção usa o viewModelScope. */
    private val scope: CoroutineScope? = null,
    /** #20-fix-c: título da nota (export). Testes usam o default. */
    private val noteTitleProvider: suspend () -> String = { "" },
) : ViewModel() {

    private val workScope: CoroutineScope get() = scope ?: viewModelScope

    data class TribunaCardUi(
        val sectionId: String,
        val order: Int,
        val sectionTitle: String,
        val minutes: Int,
        /** null = Tribuna ainda não gerada para esta seção. */
        val capsule: TribunaCapsule?,
    )

    private val _cards = MutableStateFlow<List<TribunaCardUi>>(emptyList())
    val cards: StateFlow<List<TribunaCardUi>> = _cards.asStateFlow()

    private val _speakingId = MutableStateFlow<String?>(null)

    /**
     * Seção cuja âncora está sendo lida. Derivado: quando o TTS para
     * (naturalmente ou por stop), volta a null.
     */
    val speakingId: StateFlow<String?> =
        kotlinx.coroutines.flow.combine(_speakingId, speaker.speaking) { id, on ->
            if (on) id else null
        }.stateIn(workScope, SharingStarted.Eagerly, null)

    val fontSp: StateFlow<Int> = fontPrefs.fontSp
        .stateIn(workScope, SharingStarted.Lazily, STAGE_FONT_DEFAULT_SP)

    private val persistJobs = mutableMapOf<String, Job>()

    private val _noteTitle = MutableStateFlow("")
    /** Título da nota (para o export); vazio até o load. */
    val noteTitle: StateFlow<String> = _noteTitle.asStateFlow()

    /** Lê as seções da nota (live) + parse dos tribunaJson. */
    fun load() {
        workScope.launch {
            _noteTitle.value = noteTitleProvider().ifBlank { "" }
        }
        workScope.launch {
            dao.observeForNote(noteId).collect { rows ->
                _cards.value = rows
                    .sortedBy { it.order }
                    .map { e ->
                        TribunaCardUi(
                            sectionId = e.id,
                            order = e.order,
                            sectionTitle = e.title,
                            minutes = e.minutes,
                            capsule = e.tribunaJson?.let { tribunaCapsuleFromJson(it) },
                        )
                    }
            }
        }
    }

    fun updateMainIdea(sectionId: String, text: String) =
        edit(sectionId) { it.copy(mainIdea = text) }

    fun updateBibleRef(sectionId: String, text: String) =
        edit(sectionId) { it.copy(bibleRef = text.trim().ifBlank { null }) }

    fun updateAnchorPhrase(sectionId: String, text: String) =
        edit(sectionId) { it.copy(anchorPhrase = text) }

    fun updateTransition(sectionId: String, text: String) =
        edit(sectionId) { it.copy(transition = text.trim().ifBlank { null }) }

    /** Update otimista + autosave debounced (400ms em produção). */
    private fun edit(sectionId: String, transform: (TribunaCapsule) -> TribunaCapsule) {
        _cards.update { list ->
            list.map { c ->
                if (c.sectionId == sectionId) c.copy(capsule = c.capsule?.let(transform)) else c
            }
        }
        persistJobs[sectionId]?.cancel()
        persistJobs[sectionId] = workScope.launch {
            delay(debounceMs)
            val capsule = _cards.value.firstOrNull { it.sectionId == sectionId }?.capsule
                ?: return@launch
            val entity = dao.get(sectionId) ?: return@launch
            dao.upsert(
                entity.copy(
                    tribunaJson = capsule.toJson(),
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /** TTS SÓ da frase-âncora. Sem cápsula/âncora → nada. */
    fun speakAnchor(sectionId: String) {
        val phrase = _cards.value.firstOrNull { it.sectionId == sectionId }
            ?.capsule?.anchorPhrase?.takeIf { it.isNotBlank() }
            ?: return
        _speakingId.value = sectionId
        speaker.speak(phrase)
    }

    fun stopSpeaking() {
        speaker.stop()
        _speakingId.value = null
    }

    fun setFontSp(v: Int) {
        workScope.launch { fontPrefs.setFontSp(v) }
    }

    override fun onCleared() {
        speaker.shutdown()
        super.onCleared()
    }

    class Factory(
        private val ctx: android.content.Context,
        private val db: AppDatabase,
        private val settings: SettingsStore,
        private val noteId: String,
        private val voiceEngine: com.bettertalker.app.data.voice.VoiceEngine? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            // #23-fix-a: engine injetável (router); default = nativo.
            val tts: com.bettertalker.app.data.voice.VoiceEngine =
                voiceEngine ?: TextToSpeechManager(ctx.applicationContext)
            val speaker = object : TribunaSpeaker {
                override fun speak(text: String) = tts.speak(text)
                override fun stop() = tts.stop()
                override fun shutdown() = tts.shutdown()
                override val speaking: Flow<Boolean> = tts.speaking
            }
            val prefs = object : TribunaFontPrefs {
                override val fontSp: Flow<Int> = settings.stageFontSp
                override suspend fun setFontSp(v: Int) = settings.setStageFontSp(v)
            }
            return TribunaViewModel(
                db.speechSectionDao(), noteId, speaker, prefs,
                noteTitleProvider = { db.noteDao().get(noteId)?.title.orEmpty() },
            ) as T
        }
    }
}
