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
    // "Colar e Pronto" v3: persistência atômica do esboço convertido (mesma
    // máquina do import de arquivo).
    private val outlineImports = com.bettertalker.app.data.repo.OutlineImportService(
        transactionRunner = com.bettertalker.app.data.db.RoomTransactionRunner(db),
        sectionDao = db.speechSectionDao(),
        subPointDao = db.subPointDao(),
    )
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

    /** Resultado do "Colar e Pronto": nota criada + aviso opcional (estrutura). */
    data class PasteOutcome(val noteId: String, val notice: String? = null)

    /**
     * Fase 1 "Colar e Pronto": nota pronta do texto colado.
     *
     * - Esboço estruturado (S-34, `outline` do builder) → nota tipada + seções
     *   via [com.bettertalker.app.domain.speech.OutlineConverter] (Introdução +
     *   corpos + Conclusão) — mesma máquina do import de arquivo;
     * - TALK → template de discurso (Intro/Desenvolvimento/Conclusão);
     * - DEMO/genérico → seções do builder.
     */
    suspend fun createNoteFromPaste(text: String): PasteOutcome {
        val built = com.bettertalker.app.data.util.buildPastedNote(text)
        val outline = built.outline
        if (outline != null) {
            val type = com.bettertalker.app.domain.speech.DiscourseType.S34_DISCOURSE
            val id = repo.createTypedNote(folderId.value, built.title, type)
            val conversion = com.bettertalker.app.domain.speech.OutlineConverter()
                .convert(outline, id, type)
            val persisted = outlineImports.persist(conversion, type)
            return PasteOutcome(id, "Esboço reconhecido e estruturado! ($persisted seções)")
        }
        val id = repo.createPastedNote(folderId.value, built)
        val notice = if (built.speechTemplate) {
            "Estrutura de discurso criada (Introdução, Desenvolvimento, Conclusão)."
        } else {
            null
        }
        return PasteOutcome(id, notice)
    }

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
            sections = listOf(
                com.bettertalker.app.data.util.PastedSection(
                    com.bettertalker.app.domain.speech.SectionRole.BODY, "Parte 1", 4, "",
                ),
            ),
        ),
    )
    fun trash(id: String) = viewModelScope.launch { repo.trash(id) }
    fun togglePin(id: String) = viewModelScope.launch { repo.togglePin(id) }
    /** UX refactor: mover nota entre pastas (menu do card na Home). */
    fun moveNote(id: String, folderId: String?) = viewModelScope.launch {
        repo.moveNote(id, folderId)
    }
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
