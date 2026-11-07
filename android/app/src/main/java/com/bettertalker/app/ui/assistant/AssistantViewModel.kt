package com.bettertalker.app.ui.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bettertalker.app.data.copilot.PracticeSnapshot
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.NoteEntity
import com.bettertalker.app.data.db.toDomain
import com.bettertalker.app.data.prefs.SettingsStore
import com.bettertalker.app.ui.copilot.buildPracticeSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Estado da aba Assistente: notas recentes + última nota usada.
 *
 * `notes == null` = carregando; lista vazia = nenhuma nota. A tela só decide
 * entre landing e chat depois que o Room emitiu, para não “abrir sozinho” nem
 * limpar a última nota antes da primeira leitura.
 */
class AssistantViewModel(
    private val db: AppDatabase,
    private val settings: SettingsStore,
) : ViewModel() {

    val notes: StateFlow<List<NoteEntity>?> = db.noteDao().observe(null, "")
        .map { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val lastNoteId: StateFlow<String?> = settings.lastNoteId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Fatia 5: snapshot de Treino/Palco da nota ativa. É recarregado ao entrar
     * no Assistente (o chat de prática não fica composto durante o ensaio).
     */
    private val _practiceContext = MutableStateFlow<PracticeSnapshot?>(null)
    val practiceContext: StateFlow<PracticeSnapshot?> = _practiceContext.asStateFlow()
    private var practiceRequest: String? = null

    fun refreshPracticeContext(noteId: String) {
        practiceRequest = noteId
        viewModelScope.launch {
            val note = db.noteDao().get(noteId) ?: run {
                if (practiceRequest == noteId) _practiceContext.value = null
                return@launch
            }
            val sections = db.speechSectionDao().forNote(noteId).map { it.toDomain() }
            val ids = sections.map { it.id }
            val subPointsBySection = if (ids.isEmpty()) {
                emptyMap()
            } else {
                db.subPointDao().forSections(ids)
                    .map { it.toDomain() }
                    .groupBy { it.sectionId }
            }
            val snapshot = buildPracticeSnapshot(
                noteId = noteId,
                note = note,
                sections = sections,
                subPointsBySection = subPointsBySection,
                trainPrepRaw = settings.trainPrep(noteId),
                presentSessionRaw = settings.presentSession(noteId),
            )
            // Evita que uma leitura antiga sobrescreva a nota atual.
            if (practiceRequest == noteId) _practiceContext.value = snapshot
        }
    }

    fun clearPracticeContext() {
        _practiceContext.value = null
    }

    fun clearLastNoteId() = viewModelScope.launch { settings.clearLastNoteId() }

    class Factory(
        private val db: AppDatabase,
        private val settings: SettingsStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AssistantViewModel(db, settings) as T
    }
}
