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
    fun markBase(id: String, slot: String?) = viewModelScope.launch { repo.markBaseSlot(id, slot) }
    fun linkToNote(id: String, noteId: String) = viewModelScope.launch {
        repo.linkToNote(id, noteId)
        _toast.value = "Vinculado à nota."
    }
    fun consumeToast() { _toast.value = "" }

    fun importUri(uri: Uri, noteId: String? = null) =
        viewModelScope.launch {
            val id = repo.importUri(uri, noteId)
            if (id == null) _toast.value = "Formato não suportado. Use PDF, EPUB, DOCX, RTF, ZIP ou TXT."
        }

    class Factory(private val db: AppDatabase, private val repo: LibraryRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LibraryViewModel(db, repo) as T
    }
}
