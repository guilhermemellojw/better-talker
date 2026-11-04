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
    fun delete(id: String) = viewModelScope.launch { repo.delete(id) }
    fun reindex(id: String) = viewModelScope.launch { repo.reindex(id) }
    fun retryRegister(id: String) = viewModelScope.launch {
        if (!repo.retryRegister(id)) _toast.value = "Sem download para retentar. Baixe de novo."
    }
    fun redownloadDirect(id: String) = viewModelScope.launch {
        if (!repo.redownloadDirect(id)) _toast.value = "Reabra a página e baixe de novo."
    }
    fun repairMissing(id: String, uri: Uri) = viewModelScope.launch {
        try {
            repo.repairMissing(id, uri)
            _toast.value = "Arquivo restaurado; indexando."
        } catch (e: com.bettertalker.app.data.repo.ImportException) {
            _toast.value = e.message ?: "Falha ao restaurar o arquivo."
        } catch (_: Exception) {
            _toast.value = "Falha ao restaurar o arquivo."
        }
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
