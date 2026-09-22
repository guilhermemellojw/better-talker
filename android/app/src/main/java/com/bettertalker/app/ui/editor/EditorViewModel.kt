package com.bettertalker.app.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.repo.NotesRepository
import com.bettertalker.app.ui.theme.NOTE_COLORS
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Inserção pendente a aplicar na árvore viva (preserva estilos). */
data class PendingInsert(val markdown: String, val heading: String?)

/**
 * Verdade visual = HTML; markdown é derivado para Copilot/busca/sync.
 * Escritas externas (abertura, sync) entram por revisão; inserções
 * (Copilot, esboço) entram pela fila, aplicadas na árvore viva.
 */
class EditorViewModel(private val appCtx: android.content.Context, private val db: AppDatabase, private val noteId: String) : ViewModel() {
    private val repo = NotesRepository(appCtx.applicationContext, db)
    private val _title = MutableStateFlow("")
    private val _html = MutableStateFlow("")
    private val _mdText = MutableStateFlow("")
    /** incrementado a cada escrita externa (load, sync) */
    private val _mdRevision = MutableStateFlow(0)
    private val _pending = MutableStateFlow<List<PendingInsert>>(emptyList())
    private val _saving = MutableStateFlow(false)
    private val _color = MutableStateFlow(NOTE_COLORS.first().value.toLong())
    private val _folderId = MutableStateFlow<String?>(null)
    private val _pinned = MutableStateFlow(false)
    val title = _title.asStateFlow()
    val html = _html.asStateFlow()
    val mdText = _mdText.asStateFlow()
    val mdRevision = _mdRevision.asStateFlow()
    val pendingInserts = _pending.asStateFlow()
    val saving = _saving.asStateFlow()
    val color = _color.asStateFlow()
    val folderId = _folderId.asStateFlow()
    val pinned = _pinned.asStateFlow()
    val attachments = repo.attachmentsFor(noteId)
    val folders = db.folderDao().observe()

    private var curFolderId: String? = null
    private var curPinned: Boolean = false
    private var job: Job? = null
    private var loadedOnce = false
    private var lastLocalEdit = 0L

    init {
        // reage a mudanças externas (sync, outra tela)
        viewModelScope.launch {
            db.noteDao().observeById(noteId).collect { note ->
                if (note == null) return@collect
                if (!loadedOnce) {
                    applyExternal(note.title, note.richHtml, note.mdText,
                        note.folderId, note.colorArgb, note.pinned)
                    loadedOnce = true
                    return@collect
                }
                // eco do próprio save: ignora
                if (_saving.value) return@collect
                if (note.richHtml != _html.value || note.title != _title.value) {
                    // digitando agora (<2s): mantém o local; um pull futuro re-entrega
                    if (System.currentTimeMillis() - lastLocalEdit < 2000) return@collect
                    applyExternal(note.title, note.richHtml, note.mdText,
                        note.folderId, note.colorArgb, note.pinned)
                } else {
                    if (note.colorArgb != 0L) _color.value = note.colorArgb
                    curFolderId = note.folderId
                    _folderId.value = note.folderId
                    curPinned = note.pinned
                    _pinned.value = note.pinned
                }
            }
        }
    }

    private fun applyExternal(title: String, html: String, md: String, folderId: String?, color: Long, pinned: Boolean) {
        _title.value = title
        _html.value = html
        _mdText.value = md
        _mdRevision.value = _mdRevision.value + 1
        curFolderId = folderId
        _folderId.value = folderId
        if (color != 0L) _color.value = color
        curPinned = pinned
        _pinned.value = pinned
    }

    fun onTitle(v: String) { _title.value = v; lastLocalEdit = System.currentTimeMillis(); schedule() }

    /** Chamado pelo editor visual com HTML + markdown exportados. */
    fun onContent(html: String, md: String) {
        if (html == _html.value && md == _mdText.value) return
        _html.value = html
        _mdText.value = md
        lastLocalEdit = System.currentTimeMillis()
        schedule()
    }

    fun setColor(argb: Long) {
        _color.value = argb
        schedule()
    }

    private fun schedule() {
        job?.cancel()
        job = viewModelScope.launch {
            _saving.value = true
            delay(400)
            repo.save(noteId, _title.value, _mdText.value, _html.value,
                curFolderId, _color.value, curPinned)
            _saving.value = false
        }
    }

    /** Enfileira inserção para a tela aplicar na árvore viva (preserva estilos). */
    fun queueInsertMarkdown(markdown: String, heading: String? = null) {
        _pending.value = _pending.value + PendingInsert(markdown, heading)
    }

    fun consumePending() {
        _pending.value = emptyList()
    }

    fun unlinkAttachment(id: String) = viewModelScope.launch {
        db.attachmentDao().setNote(id, null)
        com.bettertalker.app.data.cloud.SyncScheduler.requestSync(getAppCtx())
    }

    private val outlines by lazy {
        com.bettertalker.app.data.repo.OutlineRepository(getAppCtx(), db)
    }
    val outline: kotlinx.coroutines.flow.Flow<List<com.bettertalker.app.data.db.OutlineEntity>> by lazy {
        outlines.observe(noteId)
    }

    fun unlinkOutline() = viewModelScope.launch { outlines.unlink(noteId) }

    fun moveToFolder(fid: String?) = viewModelScope.launch {
        repo.moveNote(noteId, fid)
        curFolderId = fid
        _folderId.value = fid
    }

    fun togglePin() = viewModelScope.launch {
        repo.togglePin(noteId)
        curPinned = !curPinned
        _pinned.value = curPinned
    }

    fun trashNote() = viewModelScope.launch { repo.trash(noteId) }

    private fun getAppCtx(): android.content.Context = appCtx

    class Factory(private val ctx: android.content.Context, private val db: AppDatabase, private val noteId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = EditorViewModel(ctx, db, noteId) as T
    }
}
