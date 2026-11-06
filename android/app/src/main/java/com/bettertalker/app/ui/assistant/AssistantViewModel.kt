package com.bettertalker.app.ui.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.NoteEntity
import com.bettertalker.app.data.prefs.SettingsStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    db: AppDatabase,
    private val settings: SettingsStore,
) : ViewModel() {

    val notes: StateFlow<List<NoteEntity>?> = db.noteDao().observe(null, "")
        .map { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val lastNoteId: StateFlow<String?> = settings.lastNoteId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
