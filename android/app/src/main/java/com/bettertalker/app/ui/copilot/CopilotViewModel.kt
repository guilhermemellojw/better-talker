package com.bettertalker.app.ui.copilot

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.OutlineEntity
import com.bettertalker.app.data.repo.CopilotRepository
import com.bettertalker.app.data.repo.IdeaCard
import com.bettertalker.app.data.repo.OutlineRepository
import com.bettertalker.app.data.util.BASE_PUBS
import com.bettertalker.app.data.util.BasePub
import com.bettertalker.app.data.util.OutlineParser
import com.bettertalker.app.data.util.OutlineSection
import com.bettertalker.app.data.util.PastedOutlineAnalyzer
import com.bettertalker.app.data.util.RefDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class InsertRequest(val text: String, val heading: String?)

data class DraftSection(var title: String, var minutes: Int?, var included: Boolean = true, var body: String = "")

class CopilotViewModel(ctx: android.content.Context, private val db: AppDatabase, private val noteId: String? = null) : ViewModel() {
    private val app = ctx.applicationContext
    private val repo = CopilotRepository(db)
    private val outlines = OutlineRepository(app, db)
    private val _query = MutableStateFlow("")
    private val _busy = MutableStateFlow(false)
    private val _summary = MutableStateFlow("")
    private val _ideas = MutableStateFlow<List<IdeaCard>>(emptyList())
    private val _sectionBusy = MutableStateFlow<String?>(null)
    private val _insert = MutableStateFlow<InsertRequest?>(null)
    private val _missing = MutableStateFlow<List<BasePub>>(emptyList())
    private val _refsBusy = MutableStateFlow(false)
    private val _refs = MutableStateFlow<List<RefDetector.RefStatus>?>(null)
    val query = _query.asStateFlow()
    val busy = _busy.asStateFlow()
    val summary = _summary.asStateFlow()
    val ideas = _ideas.asStateFlow()
    val sectionBusy = _sectionBusy.asStateFlow()
    val insertReq = _insert.asStateFlow()
    val missing = _missing.asStateFlow()
    val refsBusy = _refsBusy.asStateFlow()
    val refs = _refs.asStateFlow()

    // ---------- esboço ----------
    private val _outlineInfo = MutableStateFlow<OutlineEntity?>(null)
    val outlineInfo = _outlineInfo.asStateFlow()
    val outlineSections = MutableStateFlow<List<OutlineSection>>(emptyList())

    // prévia de importação/cola (editável antes de vincular)
    private val _draftName = MutableStateFlow("")
    private val _draftTitle = MutableStateFlow("")
    private val _draftTotal = MutableStateFlow<Int?>(null)
    private val _draftPreamble = MutableStateFlow("")
    private val _draft = MutableStateFlow<List<DraftSection>>(emptyList())
    private val _draftError = MutableStateFlow("")
    private val _draftBusy = MutableStateFlow(false)
    private val _dropped = MutableStateFlow(0)
    private val _merges = MutableStateFlow<List<PastedOutlineAnalyzer.MergeSuggestion>>(emptyList())
    private val _candTexts = MutableStateFlow<List<String>>(emptyList())
    private val _previewRefsJson = MutableStateFlow("[]")
    private val _outlineRefs = MutableStateFlow<List<RefDetector.RefStatus>?>(null)
    private val _dlError = MutableStateFlow("")
    val outlineRefs = _outlineRefs.asStateFlow()
    val dlError = _dlError.asStateFlow()
    val draftName = _draftName.asStateFlow()
    val draftTitle = _draftTitle.asStateFlow()
    val draftTotal = _draftTotal.asStateFlow()
    val draftPreamble = _draftPreamble.asStateFlow()
    val draft = _draft.asStateFlow()
    val draftError = _draftError.asStateFlow()
    val draftBusy = _draftBusy.asStateFlow()
    val dropped = _dropped.asStateFlow()
    val merges = _merges.asStateFlow()

    init {
        refreshBases()
        if (noteId != null) {
            viewModelScope.launch {
                outlines.observe(noteId).collect { list ->
                    val o = list.firstOrNull()
                    _outlineInfo.value = o
                    outlineSections.value =
                        if (o != null) OutlineParser.fromJson(o.sectionsJson) else emptyList()
                    refreshOutlineRefs()
                }
            }
        }
    }

    fun setQuery(v: String) { _query.value = v }
    fun consumeInsert() { _insert.value = null }

    fun refreshBases() = viewModelScope.launch {
        val slots = repo.missingBases().toSet()
        _missing.value = BASE_PUBS.filter { slots.contains(it.slot) }
    }

    private suspend fun needOutline(): Boolean {
        if (noteId == null) return true
        return !repo.hasOutline(noteId)
    }

    fun summarize() = viewModelScope.launch {
        if (needOutline()) {
            _summary.value = ""
            return@launch
        }
        _busy.value = true
        val hits = repo.askScoped(_query.value.ifBlank { "discurso" }, noteId)
        _summary.value = repo.summary(hits)
        _busy.value = false
    }

    fun ideas() = viewModelScope.launch {
        if (needOutline()) return@launch
        _busy.value = true
        val topic = _query.value.ifBlank { "discurso" }
        val hits = repo.askScoped(topic, noteId)
        _ideas.value = repo.ideasFor(topic, hits)
        _busy.value = false
    }

    /** Gera ideias para UMA seção (avulsa), substituindo as dela na lista. */
    fun generateForSection(section: OutlineSection, neighbors: List<String>) = viewModelScope.launch {
        _sectionBusy.value = section.title
        val cards = repo.ideasForSection(section, neighbors, _query.value, noteId)
        _ideas.value = _ideas.value.filter { it.sectionTitle != section.title } + cards
        _sectionBusy.value = null
    }

    fun dismissIdea(card: IdeaCard) {
        _ideas.value = _ideas.value.filter { it !== card }
    }

    fun insert(card: IdeaCard, editedBody: String, heading: String?) {
        val body = editedBody.ifBlank { card.body }
        _insert.value = InsertRequest(
            "## ${card.title}\n\n$body\n\n> ${card.snippet}" +
                (if (card.source.isNotEmpty()) "\n> Fonte: ${card.source}" else ""),
            heading ?: card.sectionTitle.ifEmpty { null }
        )
    }

    // ---------- importação / cola do esboço ----------

    fun clearDraft() {
        _draft.value = emptyList()
        _draftError.value = ""
        _draftName.value = ""
        _draftTitle.value = ""
        _draftTotal.value = null
        _draftPreamble.value = ""
        _dropped.value = 0
        _merges.value = emptyList()
        _candTexts.value = emptyList()
        _previewRefsJson.value = "[]"
    }

    fun importOutlineFile(uri: Uri) = viewModelScope.launch {
        _draftBusy.value = true
        _draftError.value = ""
        try {
            val preview = outlines.previewFile(uri)
            _draftName.value = preview.fileName
            _draftTitle.value = preview.parsed.title
            _draftTotal.value = preview.parsed.totalMinutes
            _draft.value = preview.parsed.sections.map { DraftSection(it.title, it.minutes, true, it.body) }
            _draftPreamble.value = preview.parsed.preamble
            _previewRefsJson.value = preview.refsJson
        } catch (e: com.bettertalker.app.data.repo.ImportException) {
            _draftError.value = e.message ?: "Falha ao importar."
        } catch (_: Exception) {
            _draftError.value = "Falha ao importar o esboço."
        }
        _draftBusy.value = false
    }

    fun pasteOutline(text: String) = viewModelScope.launch {
        _draftBusy.value = true
        _draftError.value = ""
        val (cands, droppedCount) = PastedOutlineAnalyzer.candidates(text)
        if (cands.isEmpty()) {
            _draftError.value = "Não encontrei tópicos no texto colado."
            _draftBusy.value = false
            return@launch
        }
        _candTexts.value = cands.map { it.text }
        _draft.value = cands.map { DraftSection(it.text, null, it.suggested) }
        _dropped.value = droppedCount
        _merges.value = PastedOutlineAnalyzer.suggestMerges(cands)
        _draftName.value = "texto colado"
        _draftTitle.value = cands.firstOrNull()?.text?.take(60) ?: "Esboço"
        _draftTotal.value = null
        _previewRefsJson.value = RefDetector.detectedToJson(RefDetector.detect(text))
        _draftBusy.value = false
    }

    fun updateDraftTitle(i: Int, t: String) {
        _draft.value = _draft.value.toMutableList().also { it[i] = it[i].copy(title = t) }
    }

    fun setDraftTitle(t: String) { _draftTitle.value = t }
    fun setDraftTotal(m: Int?) { _draftTotal.value = m }

    fun updateDraftMinutes(i: Int, m: Int?) {
        _draft.value = _draft.value.toMutableList().also { it[i] = it[i].copy(minutes = m) }
    }

    fun toggleDraftInclude(i: Int) {
        _draft.value = _draft.value.toMutableList().also { it[i] = it[i].copy(included = !it[i].included) }
    }

    fun removeDraft(i: Int) {
        _draft.value = _draft.value.toMutableList().also { it.removeAt(i) }
        dropMergesWith(i)
    }

    fun acceptMerge(a: Int, b: Int) {
        val cur = _draft.value.toMutableList()
        if (a !in cur.indices || b !in cur.indices || a == b) return
        val ma = cur[a].minutes
        val mb = cur[b].minutes
        val merged = DraftSection(
            PastedOutlineAnalyzer.mergeTitles(cur[a].title, cur[b].title),
            if (ma != null && mb != null) ma + mb else (ma ?: mb)
        )
        val lo = minOf(a, b)
        cur[lo] = merged
        cur.removeAt(maxOf(a, b))
        _draft.value = cur
        _merges.value = emptyList()
    }

    fun dismissMerge(a: Int, b: Int) {
        _merges.value = _merges.value.filterNot { it.a == a && it.b == b }
    }

    private fun dropMergesWith(i: Int) {
        _merges.value = _merges.value.filterNot { it.a == i || it.b == i }
    }

    fun linkDraft() = viewModelScope.launch {
        val nid = noteId ?: return@launch
        val sections = _draft.value.filter { it.included && it.title.isNotBlank() }
            .mapIndexed { i, d -> OutlineSection(d.title.trim(), d.minutes, i, d.body) }
        if (sections.size < 2) {
            _draftError.value = "Marque ao menos 2 tópicos para vincular."
            return@launch
        }
        outlines.link(
            nid, _draftName.value.ifBlank { "esboço" }, _draftTitle.value,
            _draftTotal.value, sections, _previewRefsJson.value, _draftPreamble.value
        )
        clearDraft()
    }

    fun unlinkOutline() = viewModelScope.launch {
        val nid = noteId ?: return@launch
        outlines.unlink(nid)
    }

    /** Resolve as referências citadas no esboço contra os anexos atuais. */
    fun refreshOutlineRefs() = viewModelScope.launch {
        val o = _outlineInfo.value
        if (o == null || o.refsJson.isBlank()) {
            _outlineRefs.value = null
            return@launch
        }
        _outlineRefs.value = repo.outlineRefs(o.refsJson)
    }

    /** Verifica referências da nota sob demanda: edição exata baixada ou faltando. */
    fun checkRefs(noteText: String) = viewModelScope.launch {
        _refsBusy.value = true
        _refs.value = repo.checkRefs(noteText)
        _refsBusy.value = false
    }

    fun consumeDlError() { _dlError.value = "" }

    /**
     * Baixa a edição em 1 toque via arquivo direto da API oficial.
     * Retorna true se enfileirou (placeholder "Baixando…" criado),
     * false se deve abrir a página (chamador abre openDownload).
     */
    suspend fun downloadEdition(st: RefDetector.RefStatus): Boolean {
        if (st.apiPub == null && st.probePub == null) return false
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            _busy.value = true
            try {
                val direct = if (st.apiPub != null) {
                    com.bettertalker.app.data.util.JwMediaApi.resolveFile(st.apiPub, st.apiIssue)
                } else {
                    // numerada sem código derivável: sonda os meses do ano em paralelo
                    com.bettertalker.app.data.util.JwMediaApi.probeIssueFile(st.probePub!!, st.probeYear!!)
                } ?: return@withContext false.also { _busy.value = false }
                val lib = com.bettertalker.app.data.repo.LibraryRepository(app, db)
                val ph = lib.insertPlaceholder(direct.fileName)
                    ?: return@withContext false.also { _busy.value = false }
                val dmId = com.bettertalker.app.ui.jw.DownloadHelper.enqueue(app, direct.url, direct.fileName)
                if (dmId == null) {
                    _dlError.value = "Não foi possível iniciar o download."
                    return@withContext false.also { _busy.value = false }
                }
                lib.enqueueRegisterDownload(dmId, direct.fileName, ph)
                true.also { _busy.value = false }
            } catch (_: Exception) {
                _busy.value = false
                false
            }
        }
    }

    class Factory(
        private val ctx: android.content.Context,
        private val db: AppDatabase,
        private val noteId: String? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CopilotViewModel(ctx, db, noteId) as T
    }
}
