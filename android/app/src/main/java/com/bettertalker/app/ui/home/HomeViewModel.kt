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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(ctx: android.content.Context, db: AppDatabase) : ViewModel() {
    private val repo = NotesRepository(ctx.applicationContext, db)
    val query = MutableStateFlow("")
    val folderId = MutableStateFlow<String?>(null)

    val folders = repo.observeFolders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val notes = combine(query, folderId) { q, f -> q to f }
        .flatMapLatest { (q, f) -> repo.observeNotes(f, q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val trashed = repo.observeTrashed()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    /** noteId -> nº de anexos vinculados */
    val attachCounts = repo.observeAllAttachments()
        .map { list -> list.filter { it.noteId != null }.groupingBy { it.noteId!! }.eachCount() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap<String, Int>())

    fun setQuery(v: String) { query.value = v }
    fun setFolder(id: String?) { folderId.value = id }

    suspend fun newNote(): String = repo.newNote(folderId.value)

    /** Fase 1 "Colar e Pronto": nota pronta do texto colado (detecção V&M dentro). */
    suspend fun createNoteFromPaste(text: String): String =
        repo.createPastedNote(folderId.value, com.bettertalker.app.data.util.buildPastedNote(text))

    /** Fase 1: Discurso — nota tipada S-34 vazia. */
    suspend fun createSpeechNote(): String = repo.createTypedNote(
        folderId.value, "Nova nota", com.bettertalker.app.domain.speech.DiscourseType.S34_DISCOURSE,
    )

    /** Fase 1: Demonstração — nota tipada V&M com o BODY vazio pronto. */
    suspend fun createDemoNote(): String = repo.createPastedNote(
        folderId.value,
        com.bettertalker.app.data.util.PastedNote(
            title = "Demonstração",
            discourseType = com.bettertalker.app.domain.speech.DiscourseType.MINISTRY_PART,
            plainText = "",
            bodyHtml = "",
            bodyTitle = "Demonstração",
            bodyMinutes = 4,
        ),
    )
    fun trash(id: String) = viewModelScope.launch { repo.trash(id) }
    fun togglePin(id: String) = viewModelScope.launch { repo.togglePin(id) }
    fun restore(id: String) = viewModelScope.launch { repo.restore(id) }
    fun deleteForever(id: String) = viewModelScope.launch { repo.deleteForever(id) }
    fun newFolder(name: String, color: Long) = viewModelScope.launch { repo.newFolder(name, color) }
    fun renameFolder(id: String, name: String) = viewModelScope.launch { repo.renameFolder(id, name) }
    fun deleteFolder(id: String) = viewModelScope.launch {
        repo.deleteFolder(id)
        if (folderId.value == id) folderId.value = null
    }

    class Factory(private val ctx: android.content.Context, private val db: AppDatabase) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(ctx, db) as T
    }
}
