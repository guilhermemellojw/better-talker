package com.bettertalker.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.repo.NotesRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(db: AppDatabase) : ViewModel() {
    private val repo = NotesRepository(db)
    val query = MutableStateFlow("")
    val folderId = MutableStateFlow<String?>(null)

    val folders = repo.observeFolders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val notes = combine(query, folderId) { q, f -> q to f }
        .flatMapLatest { (q, f) -> repo.observeNotes(f, q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setQuery(v: String) { query.value = v }
    fun setFolder(id: String?) { folderId.value = id }

    suspend fun newNote(): String = repo.newNote(folderId.value)
    fun trash(id: String) = viewModelScope.launch { repo.trash(id) }
    fun newFolder(name: String, color: Long) = viewModelScope.launch { repo.newFolder(name, color) }

    class Factory(private val db: AppDatabase) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(db) as T
    }
}
