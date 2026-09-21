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

/**
 * Fonte de verdade do markdown (formato do Copilot/sync/busca).
 * A tela edita visualmente (WYSIWYG) e sincroniza nos dois sentidos
 * via mdText + mdRevision (sem loops).
 */
class EditorViewModel(private val appCtx: android.content.Context, private val db: AppDatabase, private val noteId: String) : ViewModel() {
    private val repo = NotesRepository(appCtx.applicationContext, db)
    private val _title = MutableStateFlow("")
    private val _mdText = MutableStateFlow("")
    /** incrementado a cada escrita externa (load, prefill, insert) */
    private val _mdRevision = MutableStateFlow(0)
    private val _saving = MutableStateFlow(false)
    private val _color = MutableStateFlow(NOTE_COLORS.first().value.toLong())
    private val _folderId = MutableStateFlow<String?>(null)
    private val _pinned = MutableStateFlow(false)
    val title = _title.asStateFlow()
    val mdText = _mdText.asStateFlow()
    val mdRevision = _mdRevision.asStateFlow()
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

    init {
        // reage a mudanças externas (pré-preenchimento do esboço, sync, outra tela)
        viewModelScope.launch {
            db.noteDao().observeById(noteId).collect { note ->
                if (note == null) return@collect
                if (!loadedOnce) {
                    applyExternal(note.title, note.mdText, note.folderId, note.colorArgb, note.pinned)
                    loadedOnce = true
                    return@collect
                }
                // eco do próprio save: ignora
                if (_saving.value) return@collect
                if (note.mdText != _mdText.value || note.title != _title.value) {
                    applyExternal(note.title, note.mdText, note.folderId, note.colorArgb, note.pinned)
                } else {
                    // metadados mudaram fora (cor, pasta, fixar)
                    if (note.colorArgb != 0L) _color.value = note.colorArgb
                    curFolderId = note.folderId
                    _folderId.value = note.folderId
                    curPinned = note.pinned
                    _pinned.value = note.pinned
                }
            }
        }
    }

    private fun applyExternal(title: String, md: String, folderId: String?, color: Long, pinned: Boolean) {
        _title.value = title
        _mdText.value = md
        _mdRevision.value = _mdRevision.value + 1
        curFolderId = folderId
        _folderId.value = folderId
        if (color != 0L) _color.value = color
        curPinned = pinned
        _pinned.value = pinned
    }

    fun onTitle(v: String) { _title.value = v; schedule() }

    /** Chamado pelo editor visual (debounce na tela) com o markdown exportado. */
    fun onMdText(v: String) {
        if (v == _mdText.value) return
        _mdText.value = v
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
            repo.save(noteId, _title.value, _mdText.value, curFolderId, _color.value, curPinned)
            _saving.value = false
        }
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

    /** Insere bloco markdown sob o título ## indicado (ou no fim). */
    fun insertUnderHeading(heading: String?, block: String) {
        val (t, _) = com.bettertalker.app.data.util.insertUnder(_mdText.value, heading, block)
        _mdText.value = t
        _mdRevision.value = _mdRevision.value + 1
        schedule()
    }

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
