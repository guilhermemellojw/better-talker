package com.bettertalker.app.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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

class EditorViewModel(private val appCtx: android.content.Context, private val db: AppDatabase, private val noteId: String) : ViewModel() {
    private val repo = NotesRepository(appCtx.applicationContext, db)
    private val _title = MutableStateFlow("")
    private val _md = MutableStateFlow(TextFieldValue(""))
    private val _saving = MutableStateFlow(false)
    private val _color = MutableStateFlow(NOTE_COLORS.first().value.toLong())
    val title = _title.asStateFlow()
    val md = _md.asStateFlow()
    val saving = _saving.asStateFlow()
    val color = _color.asStateFlow()
    val attachments = repo.attachmentsFor(noteId)

    private var folderId: String? = null
    private var pinned: Boolean = false
    private var job: Job? = null

    init {
        viewModelScope.launch {
            db.noteDao().get(noteId)?.let {
                _title.value = it.title
                _md.value = TextFieldValue(it.mdText)
                folderId = it.folderId
                if (it.colorArgb != 0L) _color.value = it.colorArgb
                pinned = it.pinned
            }
        }
    }

    fun onTitle(v: String) { _title.value = v; schedule() }
    fun onMd(v: TextFieldValue) {
        // evita loop: só agenda se texto mudou
        val changed = v.text != _md.value.text
        _md.value = v
        if (changed) schedule()
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
            repo.save(noteId, _title.value, _md.value.text, folderId, _color.value, pinned)
            _saving.value = false
        }
    }

    fun appendText(t: String) {
        val cur = _md.value
        val text = (cur.text + "\n\n" + t).trim()
        onMd(cur.copy(text = text, selection = TextRange(text.length)))
    }

    /** Envolve a seleção com prefixo/sufixo (negrito/itálico). */
    fun wrapSelection(prefix: String, suffix: String = prefix, placeholder: String = "texto") {
        val cur = _md.value
        val (start, end) = cur.selection.min to cur.selection.max
        val newText: String
        val newSel: TextRange
        if (start == end) {
            newText = cur.text.substring(0, start) + prefix + placeholder + suffix + cur.text.substring(end)
            val c = start + prefix.length
            newSel = TextRange(c, c + placeholder.length)
        } else {
            newText = cur.text.substring(0, start) + prefix + cur.text.substring(start, end) + suffix + cur.text.substring(end)
            newSel = TextRange(start + prefix.length, end + prefix.length)
        }
        onMd(cur.copy(text = newText, selection = newSel))
    }

    /** Prefixa cada linha tocada pela seleção (título/lista/checklist/citação). */
    fun prefixLines(prefix: String) {
        val cur = _md.value
        val text = cur.text
        val lineStart = text.lastIndexOf('\n', (cur.selection.min - 1).coerceAtLeast(0)).let { if (it == -1) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', cur.selection.max).let { if (it == -1) text.length else it }
        val block = text.substring(lineStart, lineEnd)
        val prefixed = block.lines().joinToString("\n") { line ->
            if (line.isBlank()) line else togglePrefix(line, prefix)
        }
        val newText = text.substring(0, lineStart) + prefixed + text.substring(lineEnd)
        onMd(cur.copy(text = newText, selection = TextRange(lineStart + prefixed.length)))
    }

    private fun togglePrefix(line: String, prefix: String): String {
        val t = line.trimStart()
        val indent = line.substring(0, line.length - t.length)
        // checklist alterna [ ] <-> [x]
        if (prefix == "- [ ] ") {
            return when {
                t.startsWith("- [x] ", ignoreCase = true) -> indent + "- [ ] " + t.drop(6)
                t.startsWith("- [ ] ") -> indent + "- [x] " + t.drop(6)
                t.startsWith("- ") -> indent + "- [ ] " + t.drop(2)
                else -> indent + prefix + t
            }
        }
        val p = prefix.trimStart()
        return if (t.startsWith(p)) indent + t else indent + prefix + t
    }

    fun unlinkAttachment(id: String) = viewModelScope.launch {
        db.attachmentDao().setNote(id, null)
        com.bettertalker.app.data.cloud.SyncScheduler.requestSync(getAppCtx())
    }

    private fun getAppCtx(): android.content.Context = appCtx

    class Factory(private val ctx: android.content.Context, private val db: AppDatabase, private val noteId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = EditorViewModel(ctx, db, noteId) as T
    }
}
