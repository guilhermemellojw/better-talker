package com.bettertalker.app.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.repo.LibraryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LibraryViewModel(db: AppDatabase, private val repo: LibraryRepository) : ViewModel() {
    val items = repo.observe()
    private val _toast = MutableStateFlow("")
    val toast = _toast.asStateFlow()
    private val _scan = MutableStateFlow<List<com.bettertalker.app.data.repo.DownloadCandidate>?>(null)
    /** null = ainda não procurou; vazio = nada novo em Downloads. */
    val scan = _scan.asStateFlow()

    fun scanDownloads() = viewModelScope.launch {
        _scan.value = repo.scanDownloads()
        if (_scan.value.isNullOrEmpty()) {
            _toast.value = "Nada novo em Downloads. Use Importar e escolha o arquivo."
        }
    }

    fun importScanned(c: com.bettertalker.app.data.repo.DownloadCandidate, noteId: String? = null) =
        viewModelScope.launch {
            try {
                repo.importUri(android.net.Uri.parse(c.uriString), noteId)
                _scan.value = _scan.value?.filter { it.uriString != c.uriString }
                _toast.value = "Importado: ${c.name}"
            } catch (e: com.bettertalker.app.data.repo.ImportException) {
                _toast.value = e.message ?: "Falha ao importar."
            } catch (_: Exception) {
                _toast.value = "Falha ao importar o arquivo."
            }
        }

    fun delete(id: String) = viewModelScope.launch { repo.delete(id) }
    fun reindex(id: String) = viewModelScope.launch { repo.reindex(id) }
    fun retryRegister(id: String) = viewModelScope.launch {
        if (!repo.retryRegister(id)) _toast.value = "Sem download para retentar. Baixe de novo."
    }
    fun redownloadDirect(id: String) = viewModelScope.launch {
        if (!repo.redownloadDirect(id)) _toast.value = "Reabra a página e baixe de novo."
    }
    fun markBase(id: String, slot: String?) = viewModelScope.launch { repo.markBaseSlot(id, slot) }
    fun linkToNote(id: String, noteId: String) = viewModelScope.launch {
        repo.linkToNote(id, noteId)
        _toast.value = "Vinculado à nota."
    }
    fun consumeToast() { _toast.value = "" }

    fun importUri(uri: Uri, noteId: String? = null) =
        viewModelScope.launch {
            try {
                repo.importUri(uri, noteId)
            } catch (e: com.bettertalker.app.data.repo.ImportException) {
                _toast.value = e.message ?: "Falha ao importar."
            } catch (_: Exception) {
                _toast.value = "Falha ao importar o arquivo."
            }
        }

    class Factory(private val db: AppDatabase, private val repo: LibraryRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LibraryViewModel(db, repo) as T
    }
}
