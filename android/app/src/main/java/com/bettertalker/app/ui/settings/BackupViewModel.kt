package com.bettertalker.app.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bettertalker.app.data.backup.BackupPreview
import com.bettertalker.app.data.backup.BackupRepository
import com.bettertalker.app.data.db.AppDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BackupUiState(
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val pendingImport: BackupPreview? = null,
)

/**
 * Export/import local: a UI só escolhe o arquivo; o repository valida,
 * cria o backup de segurança e importa em transação.
 */
class BackupViewModel(
    private val repo: BackupRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    fun export(uri: Uri) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null, message = null)
        viewModelScope.launch {
            runCatching { repo.exportTo(uri) }
                .onSuccess { result ->
                    _state.value = BackupUiState(
                        message = "Backup exportado: ${result.counts.notes} notas, " +
                            "${result.counts.attachments} anexos (metadados), " +
                            "${result.counts.speechSections} seções.",
                    )
                }
                .onFailure { e ->
                    _state.value = BackupUiState(
                        error = "Falha ao exportar: ${e.message ?: "erro desconhecido"}",
                    )
                }
        }
    }

    fun prepareImport(uri: Uri) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null, message = null)
        viewModelScope.launch {
            runCatching { repo.readFrom(uri) }
                .onSuccess { preview ->
                    _state.value = BackupUiState(pendingImport = preview)
                }
                .onFailure { e ->
                    _state.value = BackupUiState(
                        error = "Backup inválido: ${e.message ?: "erro desconhecido"}",
                    )
                }
        }
    }

    fun confirmImport() {
        val preview = _state.value.pendingImport ?: return
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null, message = null)
        viewModelScope.launch {
            runCatching { repo.import(preview) }
                .onSuccess { result ->
                    _state.value = BackupUiState(
                        message = "Importado: ${result.counts.notes} notas, " +
                            "${result.counts.speechSections} seções. " +
                            "Backup de segurança: ${result.safetyBackupPath}",
                    )
                }
                .onFailure { e ->
                    _state.value = BackupUiState(
                        error = "Falha ao importar (nada foi alterado): " +
                            "${e.message ?: "erro desconhecido"}",
                    )
                }
        }
    }

    fun dismissImport() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(pendingImport = null)
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null, error = null)
    }

    class Factory(
        private val context: Context,
        private val db: AppDatabase,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return BackupViewModel(BackupRepository(context.applicationContext, db)) as T
        }
    }
}
