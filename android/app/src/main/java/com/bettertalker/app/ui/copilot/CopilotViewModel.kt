package com.bettertalker.app.ui.copilot

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bettertalker.app.data.ai.RagContext
import com.bettertalker.app.data.ai.RagOrientation
import com.bettertalker.app.data.ai.RagPassage
import com.bettertalker.app.data.ai.buildRagPrompt
import com.bettertalker.app.data.ai.checkCitations
import com.bettertalker.app.data.ai.hasRepetition
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.ChatEntity
import com.bettertalker.app.data.db.OutlineEntity
import com.bettertalker.app.data.repo.CopilotRepository
import com.bettertalker.app.data.repo.IdeaCard
import com.bettertalker.app.data.repo.OutlineRepository
import com.bettertalker.app.data.util.BASE_PUBS
import com.bettertalker.app.data.util.BasePub
import com.bettertalker.app.data.util.ChatCodec
import com.bettertalker.app.data.util.ChatIntent
import com.bettertalker.app.data.util.OutlineParser
import com.bettertalker.app.data.util.OutlineSection
import com.bettertalker.app.data.util.PastedOutlineAnalyzer
import com.bettertalker.app.data.util.RefDetector
import com.bettertalker.app.data.util.newId
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class InsertRequest(val text: String, val heading: String?, val nonce: Long = System.nanoTime())

/** Tópico do draft em edição (mesmo formato persistido nas mensagens). */
typealias DraftSection = ChatCodec.DraftItem

class CopilotViewModel(ctx: android.content.Context, private val db: AppDatabase, private val noteId: String? = null) : ViewModel() {
    private val app = ctx.applicationContext
    private val repo = CopilotRepository(db)
    private val outlines = OutlineRepository(app, db)
    private val llm = com.bettertalker.app.data.ai.LlmService(app)

    override fun onCleared() {
        super.onCleared()
        llm.close()
    }
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
    /** pares dispensados (chave estável) para não ressugerir */
    private val dismissedMerges = mutableSetOf<String>()
    private val _candTexts = MutableStateFlow<List<String>>(emptyList())
    private val _previewRefsJson = MutableStateFlow("[]")
    private val _outlineRefs = MutableStateFlow<List<RefDetector.RefStatus>?>(null)
    private val _dlError = MutableStateFlow("")
    /** Esqueleto markdown a inserir no editor (via fila, preserva estilos). */
    private val _skeletonEvent = MutableStateFlow<String?>(null)
    val skeletonEvent = _skeletonEvent.asStateFlow()
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
                db.noteDao().observeById(noteId).collect { n ->
                    if (n != null) {
                        _noteText.value = (n.title + "\n" + n.mdText)
                        _noteTitle.value = n.title
                        // título da nota é mestre: propaga ao esboço vinculado
                        val o = outlines.get(noteId)
                        if (o != null && n.title.isNotBlank() && o.title != n.title) {
                            outlines.updateTitle(noteId, n.title)
                        }
                        suggestSyncIfEdited(n.mdText)
                    }
                }
            }
            viewModelScope.launch {
                outlines.observe(noteId).collect { list ->
                    val o = list.firstOrNull()
                    _outlineInfo.value = o
                    outlineSections.value =
                        if (o != null) OutlineParser.fromJson(o.sectionsJson) else emptyList()
                    refreshOutlineRefs()
                }
            }
            viewModelScope.launch {
                // espera a primeira leitura do banco para saudar com contexto
                // (a saudação lê nota/esboço direto do DAO, sem race)
                kotlinx.coroutines.flow.combine(
                    db.noteDao().observeById(noteId),
                    outlines.observe(noteId)
                ) { n, _ -> n }.first()
                // limpa prévias de draft órfãs (processo morreu no meio da edição)
                try {
                    db.chatDao().all(noteId).filter { it.kind == "draft" }
                        .forEach { db.chatDao().delete(it.id) }
                } catch (_: Exception) {
                }
                ensureGreeting()
            }
        }
    }

    fun setQuery(v: String) { _query.value = v }
    fun consumeInsert() {
        _insert.value = null
        pumpInserts()
    }

    /**
     * Sugere sincronizar quando os títulos vinculados somem da nota.
     * Só sugere (nunca escreve); uma vez por mudança nos títulos.
     */
    private suspend fun suggestSyncIfEdited(mdText: String) {
        val linked = outlineSections.value.map { it.title }
        if (linked.isEmpty() || _draft.value.isNotEmpty()) return
        val heads = com.bettertalker.app.data.util.headingsOf(mdText)
        // sem títulos na nota (esqueleto ainda a caminho): nada a sugerir
        if (heads.isEmpty()) return
        val norm = { s: String -> com.bettertalker.app.data.util.normalizeText(s) }
        val mismatch = linked.any { l -> heads.none { norm(it) == norm(l) } }
        if (!mismatch) return
        val key = heads.joinToString("|")
        if (key == syncSuggestKey) return
        syncSuggestKey = key
        post(false, "text", ChatCodec.escMap(mapOf(
            "text" to "Vi que você editou os tópicos na nota. Diga “sincronizar” para atualizar o esboço.")))
    }

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
        // genéricas entram em grupo próprio, sem apagar as por seção
        val generic = repo.ideasFor(topic, hits).map { it.copy(sectionTitle = "") }
        _ideas.value = _ideas.value.filter { it.sectionTitle.isNotEmpty() } + generic
        _busy.value = false
    }

    /** Gera ideias para UMA seção (avulsa), substituindo as dela na lista. */
    fun generateForSection(section: OutlineSection, neighbors: List<String>) = viewModelScope.launch {
        _sectionBusy.value = section.title
        try {
            val scope = refScope()
            val cards = repo.ideasForSection(section, neighbors, _query.value, noteId,
                scope.keys.toList(), scope)
            _ideas.value = _ideas.value.filter { it.sectionTitle != section.title } + cards
            if (cards.isNotEmpty()) {
                lastSection = section.title
                pendingAsk = null
                post(false, "ideas", ChatCodec.escMap(mapOf(
                    "section" to section.title,
                    "cards" to ChatCodec.cardsToJson(cards))))
            }
        } finally {
            _sectionBusy.value = null
        }
    }

    /** Atalho da conversa: gera para a seção pelo título (botão Gerar). */
    fun generateForSectionTitle(title: String) = viewModelScope.launch {
        val secs = outlineSections.value
        val idx = secs.indexOfFirst { it.title == title }
        if (idx < 0) {
            postText("Essa seção não existe mais no esboço.")
            return@launch
        }
        val s = secs[idx]
        generateForSection(s, listOfNotNull(
            secs.getOrNull(idx - 1)?.title, secs.getOrNull(idx + 1)?.title))
    }

    fun dismissIdea(card: IdeaCard) {
        _ideas.value = _ideas.value.filter { it !== card }
    }

    fun insert(card: IdeaCard, editedBody: String, heading: String?) {
        val body = editedBody.ifBlank { card.body }
        insertedTitles += com.bettertalker.app.data.util.normalizeText(card.title)
        pendingInserts += InsertRequest(
            "## ${card.title}\n\n$body\n\n> ${card.snippet}" +
                (if (card.source.isNotEmpty()) "\n> Fonte: ${card.source}" else ""),
            heading ?: card.sectionTitle.ifEmpty { null }
        )
        pumpInserts()
    }

    /** Fila de inserções: o slot único (_insert) perdia pedidos em rajada. */
    private val pendingInserts = ArrayDeque<InsertRequest>()

    private fun pumpInserts() {
        if (_insert.value == null) _insert.value = pendingInserts.removeFirstOrNull()
    }

    // ---------- conversa (chat colaborativo, salvo por nota) ----------

    data class ChatItem(
        val id: String,
        val fromMe: Boolean,
        val kind: String, // text | ideas | refs | bases | sections | draft
        val text: String = "",
        val section: String = "",
        val cards: List<IdeaCard> = emptyList(),
        val detectedJson: String = "",
        val slots: List<String> = emptyList(),
        val secItems: List<ChatCodec.SecItem> = emptyList(),
        val draftTitle: String = "",
        val draftItems: List<ChatCodec.DraftItem> = emptyList()
    )

    private val _chatBusy = MutableStateFlow(false)
    val chatBusy = _chatBusy.asStateFlow()
    private val _noteText = MutableStateFlow("")
    private val _noteTitle = MutableStateFlow("")

    val messages = if (noteId != null) {
        db.chatDao().observe(noteId).map { list ->
            list.map { e ->
                when (e.kind) {
                    "ideas" -> {
                        val m = ChatCodec.unescMap(e.payload)
                        ChatItem(e.id, e.fromMe, e.kind,
                            section = m["section"].orEmpty(),
                            cards = ChatCodec.cardsFromJson(m["cards"].orEmpty()))
                    }
                    "refs" -> {
                        val m = ChatCodec.unescMap(e.payload)
                        ChatItem(e.id, e.fromMe, e.kind, text = m["title"].orEmpty(),
                            detectedJson = m["detected"].orEmpty())
                    }
                    "bases" -> {
                        val m = ChatCodec.unescMap(e.payload)
                        ChatItem(e.id, e.fromMe, e.kind, text = m["text"].orEmpty(),
                            slots = m["slots"].orEmpty().split(",").filter { it.isNotBlank() })
                    }
                    "sections" -> {
                        val m = ChatCodec.unescMap(e.payload)
                        ChatItem(e.id, e.fromMe, e.kind, text = m["title"].orEmpty(),
                            secItems = ChatCodec.sectionsFromJson(m["items"].orEmpty()))
                    }
                    "draft" -> {
                        val (title, items) = ChatCodec.draftFromJson(e.payload)
                        ChatItem(e.id, e.fromMe, e.kind, draftTitle = title, draftItems = items)
                    }
                    else -> ChatItem(e.id, e.fromMe, "text",
                        text = ChatCodec.unescMap(e.payload)["text"].orEmpty())
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    } else {
        MutableStateFlow(emptyList())
    }

    private suspend fun post(fromMe: Boolean, kind: String, payload: String) {
        val nid = noteId ?: return
        db.chatDao().put(
            ChatEntity(newId("msg"), nid, fromMe, kind, payload, System.currentTimeMillis())
        )
    }

    private fun postText(t: String) = viewModelScope.launch {
        post(false, "text", ChatCodec.escMap(mapOf("text" to t)))
    }

    private suspend fun greetText(): String {
        val nid = noteId
        val title = nid?.let { db.noteDao().get(it)?.title }.orEmpty().ifBlank { "sua nota" }
        val outline = nid?.let { outlines.get(it) }
        val secs = if (outline != null) OutlineParser.fromJson(outline.sectionsJson) else emptyList()
        val missing = repo.missingBases().map { repo.baseTitle(it) }
        val sb = StringBuilder("Olá! Vamos desenvolver “$title” juntos. Sou seu assistente de oratória (100% offline).")
        if (secs.isNotEmpty()) {
            sb.append("\n\nEsboço com ${secs.size} seções — peça ideias por seção ou pergunte sobre um tema.")
        } else {
            sb.append("\n\nToque no + abaixo para importar ou colar o esboço — assim sigo a linha de raciocínio.")
        }
        if (missing.isNotEmpty()) {
            sb.append("\n\nFaltam as bases: ${missing.joinToString("; ")}.")
        }
        return sb.toString()
    }

    private suspend fun ensureGreeting() {
        val nid = noteId ?: return
        if (db.chatDao().all(nid).isEmpty()) {
            post(false, "text", ChatCodec.escMap(mapOf("text" to greetText())))
        }
    }

    fun clearChat() = viewModelScope.launch {
        val nid = noteId ?: return@launch
        db.chatDao().clear(nid)
        ensureGreeting()
    }

    /** Resolve refs salvas numa mensagem contra os anexos atuais (p/ renderizar). */
    suspend fun resolveChatRefs(detectedJson: String): List<RefDetector.RefStatus> =
        repo.outlineRefs(detectedJson)

    private suspend fun lastIdeaCards(): List<IdeaCard> {
        val nid = noteId ?: return emptyList()
        val last = db.chatDao().all(nid).lastOrNull { it.kind == "ideas" } ?: return emptyList()
        return ChatCodec.cardsFromJson(ChatCodec.unescMap(last.payload)["cards"].orEmpty())
    }

    /**
     * Dispensa um cartão da mensagem (números exibidos == lista real).
     * Atualiza o payload no banco; mensagem vazia é apagada.
     */
    fun dismissIdeaCard(messageId: String, index: Int) = viewModelScope.launch {
        val nid = noteId ?: return@launch
        val e = db.chatDao().all(nid)
            .firstOrNull { it.id == messageId && it.kind == "ideas" } ?: return@launch
        val m = ChatCodec.unescMap(e.payload)
        val cards = ChatCodec.cardsFromJson(m["cards"].orEmpty()).toMutableList()
        if (index !in cards.indices) return@launch
        cards.removeAt(index)
        if (cards.isEmpty()) {
            db.chatDao().delete(messageId)
        } else {
            db.chatDao().put(e.copy(payload = ChatCodec.escMap(mapOf(
                "section" to m["section"].orEmpty(),
                "cards" to ChatCodec.cardsToJson(cards)))))
        }
    }

    /** Escopo das publicações citadas (nota + esboço), mesmo sem vínculo. */
    private suspend fun refScope(): Map<String, String> {
        val nid = noteId ?: return emptyMap()
        return repo.refScopeIds(_noteText.value, outlines.get(nid)?.refsJson.orEmpty())
    }

    /** Ponto de entrada da conversa: classifica e executa. */
    private val sendMutex = Mutex()

    /** Memória curta da conversa: só a pergunta mais recente vale. */
    private sealed interface PendingAsk {
        data class Sections(val options: List<String>) : PendingAsk
        data class Insert(val count: Int) : PendingAsk
    }
    private var pendingAsk: PendingAsk? = null
    private var lastTopic: String? = null
    private var lastSection: String? = null
    private var lastExampleKind: String? = null
    private var lastWasDevelop: Boolean = false
    /** Confirmação pendente ("Adiciono X?") com a ação do "sim". */
    private var pendingConfirm: (suspend () -> Unit)? = null
    /** Títulos de cartões já inseridos (sync nunca os vira tópico). */
    private val insertedTitles = mutableSetOf<String>()
    /** Últimos títulos da nota que geraram sugestão de sync (não repete). */
    private var syncSuggestKey: String? = null

    fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || noteId == null) return
        // mínimo anti rajada/toque duplo: a UI também desabilita o envio com chatBusy
        if (_chatBusy.value) return
        viewModelScope.launch {
            post(true, "text", ChatCodec.escMap(mapOf("text" to text)))
            _chatBusy.value = true
            try {
                sendMutex.withLock {
                    if (resolveConfirm(text)) return@withLock
                    if (resolveGuide(text)) {
                        pendingConfirm = null
                        return@withLock
                    }
                    if (resolveDraftCommand(text) || resolveFollowUp(text) || resolveMore(text)) {
                        // usuário seguiu outro rumo: confirmação pendente expira
                        pendingConfirm = null
                        return@withLock
                    }
                    pendingAsk = null // comando explícito novo invalida a pergunta pendente
                    pendingConfirm = null
                    when (val intent = ChatIntent.classify(text, outlineSections.value.map {
                        ChatIntent.SectionRef(it.title, it.body)
                    })) {
                        is ChatIntent.Intent.Ask -> answerAsk(intent.topic)
                        is ChatIntent.Intent.Ideas -> answerIdeas(intent.sectionHint, text)
                        is ChatIntent.Intent.Example -> answerExample(intent.kind, intent.sectionHint, text)
                        is ChatIntent.Intent.Compose -> answerCompose(intent.sectionHint, text)
                        ChatIntent.Intent.Guided -> startGuided()
                        is ChatIntent.Intent.Develop -> answerDevelop(intent.sectionHint, text)
                        ChatIntent.Intent.Summarize -> answerSummarize()
                        ChatIntent.Intent.CheckRefs -> answerCheckRefs()
                        ChatIntent.Intent.OutlineRefs -> answerOutlineRefs()
                        ChatIntent.Intent.Sections -> answerSections()
                        ChatIntent.Intent.Sync -> answerSync()
                        ChatIntent.Intent.Skeleton -> answerSkeleton()
                        ChatIntent.Intent.Unlink -> answerUnlink()
                        ChatIntent.Intent.Bases -> answerBases()
                        is ChatIntent.Intent.Insert -> answerInsert(intent.index)
                        ChatIntent.Intent.Help -> post(false, "text", ChatCodec.escMap(mapOf("text" to helpText())))
                        ChatIntent.Intent.Thanks -> post(false, "text",
                            ChatCodec.escMap(mapOf("text" to "Por nada! Seguimos juntos no discurso. 🙌")))
                    }
                }
            } catch (_: Exception) {
                postText("Algo falhou aqui no aparelho. Tente de novo em instantes.")
            } finally {
                _chatBusy.value = false
            }
        }
    }

    /**
     * Responde "sim"/"não" à confirmação pendente. Retorna true se consumiu.
     * Qualquer outra coisa devolve false (o fluxo normal invalida o pendente).
     */
    private suspend fun resolveConfirm(text: String): Boolean {
        val action = pendingConfirm ?: return false
        return when {
            ChatIntent.isYes(text) -> {
                pendingConfirm = null
                action()
                true
            }
            ChatIntent.isNo(text) -> {
                pendingConfirm = null
                postText("Tudo bem — o esboço continua como está.")
                true
            }
            else -> false
        }
    }

    /**
     * Comandos sobre a prévia do esboço ("vincular", "tópico 2 chama X",
     * "10 min no 3", "remover o 2", "fundir 1 e 2", "descartar").
     * Retorna true se consumiu a mensagem.
     */
    private suspend fun resolveDraftCommand(text: String): Boolean {
        val t = com.bettertalker.app.data.util.normalizeText(text)
        fun has(vararg ws: String) = ws.any { w -> t.contains(w) }
        val draftActive = _draft.value.isNotEmpty()

        if (has("vincul")) {
            if (!draftActive) {
                postText("Nada para vincular — toque no + para importar ou colar o esboço.")
                return true
            }
            pendingAsk = null
            linkDraft()
            return true
        }
        if ((has("cancel", "descart") && (has("esboc", "rascunho", "previa") || draftActive)) ||
            ((t == "cancelar" || t == "cancela" || t == "descarta") && draftActive)
        ) {
            if (!draftActive) return false
            pendingAsk = null
            clearDraft()
            postText("Prévia descartada.")
            return true
        }
        if (!draftActive) return false

        val nums = Regex("""\b([1-9]|10)\b""").findAll(t)
            .map { it.groupValues[1].toInt() - 1 }.toList()
        // fundir/juntar/mesclar 1 e 2
        if (has("fund", "junt", "mescl", "unir", "combin")) {
            if (nums.size >= 2) {
                val cur = _draft.value
                if (nums[0] in cur.indices && nums[1] in cur.indices && nums[0] != nums[1]) {
                    acceptMerge(nums[0], nums[1])
                    postText("Tópicos fundidos ✓")
                } else {
                    postText("Diga dois números válidos (1 a ${cur.size}).")
                }
            } else {
                postText("Quais tópicos? Ex: “fundir 1 e 2”.")
            }
            return true
        }
        // remover/excluir/apagar/tirar o tópico N (sem "esboço" -> não é unlink)
        if (has("remov", "exclu", "apag") && !has("esboc")) {
            val i = nums.firstOrNull()
            if (i != null && i in _draft.value.indices) {
                removeDraft(i)
            } else {
                postText("Qual tópico? Ex: “remover o 2”.")
            }
            return true
        }
        // incluir/marcar/pular/ignorar o N
        if (has("inclu", "marc", "pul", "ignor", "tir")) {
            val i = nums.firstOrNull()
            if (i != null && i in _draft.value.indices) {
                val want = has("inclu", "marc")
                val cur = _draft.value[i].included
                if (cur != want) toggleDraftInclude(i)
            } else {
                postText("Qual tópico? Ex: “incluir o 4”.")
            }
            return true
        }
        // minutos: "10 min no 3", "tópico 3 tem 5 minutos"
        if (has("min")) {
            val minMatch = Regex("""\b(\d{1,3})\s*min""").find(t)
            val mins = minMatch?.groupValues?.get(1)?.toIntOrNull()
            // o número dos minutos não é índice: remove antes de procurar o tópico
            val rest = minMatch?.let { t.removeRange(it.range) } ?: t
            val i = Regex("""\b([1-9]|10)\b""").findAll(rest)
                .map { it.groupValues[1].toInt() - 1 }.firstOrNull()
            if (mins != null && i != null && i in _draft.value.indices) {
                updateDraftMinutes(i, mins)
            } else {
                postText("Ex: “10 min no 3”.")
            }
            return true
        }
        // renomear: "tópico 2 chama X", "renomear 2 para X", "2 passa a se chamar X"
        if (has("chama", "renome", "titulo", "nome", "chamar")) {
            val i = nums.firstOrNull()
            val name = Regex(
                """(?i)(?:chama(?:-se)?|renome\w*|t[íi]tulo|nome|chamar)\s+(?:de\s+|para\s+|como\s+)?(.+)"""
            ).find(text)?.groupValues?.get(1)?.trim()?.trimEnd('.', '!', '?').orEmpty()
            if (i != null && i in _draft.value.indices && name.isNotBlank()) {
                updateDraftTitle(i, name)
            } else {
                postText("Ex: “tópico 2 chama Conclusão”.")
            }
            return true
        }
        return false
    }

    /**
     * Resolve respostas curtas à pergunta pendente ("a 2", "conclusão", "3").
     * Retorna true se consumiu a mensagem.
     */
    private suspend fun resolveFollowUp(text: String): Boolean {        return when (val pend = pendingAsk) {
            is PendingAsk.Sections -> {
                val num = ChatIntent.parseNumber(text)
                val target = num?.let { pend.options.getOrNull(it) }
                    ?: ChatIntent.matchSection(text, outlineSections.value.map {
                        ChatIntent.SectionRef(it.title, it.body)
                    })
                if (target != null) {
                    pendingAsk = null
                    answerIdeas(target, text)
                    true
                } else if (num != null) {
                    post(false, "text", ChatCodec.escMap(mapOf(
                        "text" to "Só tenho ${pend.options.size} seções (1 a ${pend.options.size}). Qual delas?")))
                    true
                } else {
                    false
                }
            }
            is PendingAsk.Insert -> {
                val num = ChatIntent.parseNumber(text)
                if (num != null) {
                    if (num in 0 until pend.count) {
                        pendingAsk = null
                        answerInsert(num)
                    } else {
                        post(false, "text", ChatCodec.escMap(mapOf(
                            "text" to "Só tenho ${pend.count} ideias (1 a ${pend.count}). Qual delas?")))
                    }
                    true
                } else {
                    false
                }
            }
            null -> false
        }
    }

    /**
     * "mais", "outra", "fala mais (sobre X)": regenera ideias da última seção
     * ou aprofunda o último tópico. Retorna true se consumiu.
     */
    private suspend fun resolveMore(text: String): Boolean {
        if (!ChatIntent.isFollowUpMore(text)) return false
        val n = com.bettertalker.app.data.util.normalizeText(text)
        val continuationFirst =
            Regex("""^(outr[oa]s?|mais)\b""").containsMatchIn(n)
        // pedido explícito ("exemplo para X") não é continuação -> classifica
        if (!continuationFirst && ChatIntent.exampleKindHint(text) != null) return false
        // "desenvolva ..." explícito também classifica (não é continuação)
        if (!continuationFirst && ChatIntent.hasDevelopVerbs(text)) return false
        val extra = ChatIntent.stripMoreWords(text)
            .replace(Regex("""\b(sobre|de|do|da|em|para|pra)\b"""), " ")
            .replace(Regex("""\s+"""), " ").trim()
        if (extra.isNotEmpty()) {
            val topic = listOfNotNull(lastTopic, extra).joinToString(" ").trim()
            answerAsk(topic)
            return true
        }
        val sec = lastSection
        if (sec != null && outlineSections.value.any { it.title == sec }) {
            // "outro exemplo" regenera exemplo; "desenvolva mais" desenvolve;
            // "mais" após rascunho continua o rascunho; senão, ideias
            val hasExample = n.contains("exemplo")
            val hasDevelop = ChatIntent.hasDevelopVerbs(text)
            when {
                hasExample -> answerExample(lastExampleKind, sec, text)
                hasDevelop || lastWasDevelop -> answerDevelop(sec, text)
                lastExampleKind != null -> answerExample(lastExampleKind, sec, text)
                else -> answerIdeas(sec, text)
            }
        } else {
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Sobre o quê? Diga o tema ou a seção.")))
        }
        return true
    }

    private fun helpText(): String =
        "Posso: responder sobre um tema (com citações dos seus materiais), " +
            "gerar ideias por seção, resumir, verificar referências da nota e do esboço, " +
            "mostrar as seções, (re)inserir o esqueleto, desvincular o esboço, " +
            "mostrar as bases e inserir sugestões na nota. " +
            "Respondo continues: “a 2”, “mais”, “fala mais sobre fé”. " +
            "Com prévia ativa: “vincular”, “tópico 2 chama X”, “10 min no 3”, " +
            "“remover o 2”, “fundir 1 e 2”, “sincronizar esboço”, “desenvolva a introdução”. Exemplos:\n" +
            "• “ideias para a conclusão”\n• “exemplo para a conclusão”\n• “como introduzir?”\n• “o que dizem sobre fé?”\n• “o que diz Gênesis 1:26?”\n• “insere a segunda”\n• “quais refs faltam?”\n• “refs do esboço”\n• “mostre as seções”"

    private suspend fun answerAsk(topic: String) {
        if (needOutline()) {
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Para responder com base nos materiais, toque no + e vincule um esboço.")))
            return
        }
        lastTopic = topic
        val scope = refScope()
        val hits = repo.askScoped(topic, noteId, 6,
            extraIds = scope.keys.toList(), extraLabels = scope)
        if (hits.isEmpty()) {
            val missing = missingMatterText()
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Não encontrei trechos sobre “$topic” nos materiais baixados." +
                    (if (missing.isNotBlank()) "\n\n$missing" else "") + staleBodiesHint())))
            val slots = repo.missingBases()
            if (slots.isNotEmpty()) {
                post(false, "bases", ChatCodec.escMap(mapOf(
                    "text" to "Faltam estas bases:",
                    "slots" to slots.joinToString(","))))
            }
            return
        }
        // SÓ conteúdo (citadas): be/th instruem a estrutura, nunca são matéria
        val content = com.bettertalker.app.data.repo.contentHits(hits)
        if (content.isEmpty()) {
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Sobre “$topic”, só tenho as publicações-guia (estrutura, não matéria). " +
                    "Baixe no site a matéria citada no esboço para eu responder com ela.")))
            val missing = repo.missingBases()
            if (missing.isNotEmpty()) {
                post(false, "bases", ChatCodec.escMap(mapOf(
                    "text" to "Faltam estas bases:",
                    "slots" to missing.joinToString(","))))
            }
            return
        }
        val sb = StringBuilder("Sobre “$topic”, nos seus materiais:\n")
        // versículos citados na pergunta, direto da TNM indexada
        for (b in RefDetector.detectBible(topic).take(2)) {
            val verses = repo.biblePassages(b.bookNorm, b.chapter, b.verse, 2)
            for (v in verses) {
                sb.append("\nDa Bíblia (TNM 2015) — ${b.label} ${b.chapter}:${b.verse}:\n“${v.passage.text.take(220)}”\n")
            }
        }
        content.take(4).forEachIndexed { i, h ->
            sb.append("\n${i + 1}. [${h.source}] ${h.passage.text.take(180)}\n")
        }
        sb.append("\nQuer que eu gere ideias com isso? Diga a seção.")
        post(false, "text", ChatCodec.escMap(mapOf("text" to sb.toString().trim())))
    }

    private suspend fun answerIdeas(sectionHint: String?, raw: String) {
        if (needOutline()) {
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Vincule um esboço primeiro — assim gero ideias por seção, na ordem do raciocínio.")))
            return
        }
        val secs = outlineSections.value
        val exact = sectionHint?.let { h -> secs.firstOrNull { it.title == h } }
        val target = exact ?: when {
            sectionHint != null -> null // citou uma seção que não existe
            secs.size == 1 -> secs.first()
            else -> null // várias seções: precisa escolher
        }
        if (target == null) {
            if (sectionHint == null && secs.size > 1) {
                val opts = secs.mapIndexed { i, s -> "${i + 1}. ${s.title}" }.joinToString("\n")
                pendingAsk = PendingAsk.Sections(secs.map { it.title })
                post(false, "text", ChatCodec.escMap(mapOf(
                    "text" to "Para qual seção? Diga o número ou o nome:\n$opts")))
            } else {
                post(false, "text", ChatCodec.escMap(mapOf(
                    "text" to "Não achei a seção. Diga o nome dela como está no esboço.")))
            }
            return
        }
        val idx = secs.indexOf(target)
        val neighbors = listOfNotNull(
            secs.getOrNull(idx - 1)?.title, secs.getOrNull(idx + 1)?.title)
        val scope = refScope()
        val cards = repo.ideasForSection(target, neighbors, sectionHint ?: raw, noteId,
            scope.keys.toList(), scope)
        if (cards.isEmpty()) {
            val missing = missingMatterText()
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Não achei trechos nos materiais para “${target.title}”." +
                    (if (missing.isNotBlank()) "\n\n$missing" else "") + staleBodiesHint())))
            return
        }
        lastSection = target.title
        lastExampleKind = null
        lastWasDevelop = false
        pendingAsk = null
        _ideas.value = _ideas.value.filter { it.sectionTitle != target.title } + cards
        post(false, "ideas", ChatCodec.escMap(mapOf(
            "section" to target.title,
            "cards" to ChatCodec.cardsToJson(cards))))
    }

    private suspend fun answerExample(kindHint: String?, sectionHint: String?, raw: String) {
        if (needOutline()) {
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Vincule um esboço primeiro — assim gero exemplos por seção. Toque no + para importar ou colar.")))
            return
        }
        val secs = outlineSections.value
        val exact = sectionHint?.let { h -> secs.firstOrNull { it.title == h } }
        val target = exact ?: when {
            sectionHint != null -> null
            secs.size == 1 -> secs.first()
            else -> null
        }
        if (target == null) {
            if (sectionHint == null && secs.size > 1) {
                val opts = secs.mapIndexed { i, s -> "${i + 1}. ${s.title}" }.joinToString("\n")
                pendingAsk = PendingAsk.Sections(secs.map { it.title })
                post(false, "text", ChatCodec.escMap(mapOf(
                    "text" to "Exemplo para qual seção? Diga o número ou o nome:\n$opts")))
            } else {
                post(false, "text", ChatCodec.escMap(mapOf(
                    "text" to "Não achei a seção. Diga o nome dela como está no esboço.")))
            }
            return
        }
        val idx = secs.indexOf(target)
        val neighbors = listOfNotNull(
            secs.getOrNull(idx - 1)?.title, secs.getOrNull(idx + 1)?.title)
        val kind = when (kindHint) {
            "intro" -> com.bettertalker.app.data.repo.CopilotRepository.ExampleKind.INTRO
            "illustration" -> com.bettertalker.app.data.repo.CopilotRepository.ExampleKind.ILLUSTRATION
            "conclusion" -> com.bettertalker.app.data.repo.CopilotRepository.ExampleKind.CONCLUSION
            "question" -> com.bettertalker.app.data.repo.CopilotRepository.ExampleKind.QUESTION
            else -> null
        }
        val scope = refScope()
        val cards = repo.exampleForSection(target, idx == 0, idx == secs.lastIndex, kind,
            neighbors, noteId, scope.keys.toList(), scope)
        if (cards.isEmpty()) {
            val missing = missingMatterText()
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Não achei trechos nos materiais para “${target.title}”." +
                    (if (missing.isNotBlank()) "\n\n$missing" else "") + staleBodiesHint())))
            return
        }
        lastSection = target.title
        lastExampleKind = (kind ?: repo.kindFor(idx == 0, idx == secs.lastIndex, "")).name.lowercase()
        lastWasDevelop = false
        pendingAsk = null
        _ideas.value = _ideas.value.filter { it.sectionTitle != target.title } + cards
        post(false, "ideas", ChatCodec.escMap(mapOf(
            "section" to target.title,
            "cards" to ChatCodec.cardsToJson(cards))))
    }

    private fun exampleKindOf(hint: String?): com.bettertalker.app.data.repo.CopilotRepository.ExampleKind? =
        when (hint) {
            "intro" -> com.bettertalker.app.data.repo.CopilotRepository.ExampleKind.INTRO
            "illustration" -> com.bettertalker.app.data.repo.CopilotRepository.ExampleKind.ILLUSTRATION
            "conclusion" -> com.bettertalker.app.data.repo.CopilotRepository.ExampleKind.CONCLUSION
            "question" -> com.bettertalker.app.data.repo.CopilotRepository.ExampleKind.QUESTION
            else -> null
        }

    private suspend fun answerDevelop(sectionHint: String?, raw: String) {
        if (needOutline()) {
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Vincule um esboço primeiro — assim desenvolvo cada parte. Toque no + para importar ou colar.")))
            return
        }
        val secs = outlineSections.value
        val exact = sectionHint?.let { h -> secs.firstOrNull { it.title == h } }
        val target = exact ?: when {
            sectionHint != null -> null
            secs.size == 1 -> secs.first()
            else -> null
        }
        if (target == null) {
            if (sectionHint == null && secs.size > 1) {
                val opts = secs.mapIndexed { i, s -> "${i + 1}. ${s.title}" }.joinToString("\n")
                pendingAsk = PendingAsk.Sections(secs.map { it.title })
                post(false, "text", ChatCodec.escMap(mapOf(
                    "text" to "Desenvolver qual parte? Diga o número ou o nome:\n$opts")))
            } else {
                post(false, "text", ChatCodec.escMap(mapOf(
                    "text" to "Não achei a seção. Diga o nome dela como está no esboço.")))
            }
            return
        }
        val idx = secs.indexOf(target)
        val neighbors = listOfNotNull(
            secs.getOrNull(idx - 1)?.title, secs.getOrNull(idx + 1)?.title)
        val kind = exampleKindOf(ChatIntent.exampleKindHint(raw)?.takeIf { it != "any" })
        val scope = refScope()
        val cards = repo.exampleForSection(target, idx == 0, idx == secs.lastIndex, kind,
            neighbors, noteId, scope.keys.toList(), scope)
        if (cards.isEmpty()) {
            val missing = missingMatterText()
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Não achei trechos nos materiais para “${target.title}”." +
                    (if (missing.isNotBlank()) "\n\n$missing" else "") + staleBodiesHint())))
            return
        }
        lastSection = target.title
        lastExampleKind = null
        lastWasDevelop = true
        pendingAsk = null
        _ideas.value = _ideas.value.filter { it.sectionTitle != target.title } + cards
        post(false, "ideas", ChatCodec.escMap(mapOf(
            "section" to target.title,
            "cards" to ChatCodec.cardsToJson(cards))))
    }

    /** Índice da seção atual no modo guiado (null = desligado). */
    private var guideIndex: Int? = null

    private suspend fun startGuided() {
        if (needOutline()) {
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Vincule um esboço primeiro — o modo guiado percorre seção por seção. Toque no + para importar ou colar.")))
            return
        }
        if (outlineSections.value.isEmpty()) {
            post(false, "text", ChatCodec.escMap(mapOf("text" to "O esboço vinculado está vazio.")))
            return
        }
        guideIndex = 0
        guideStep()
    }

    private suspend fun guideStep() {
        val secs = outlineSections.value
        val i = guideIndex ?: return
        if (i >= secs.size) {
            guideIndex = null
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Guia concluído ✓ — passamos por todas as ${secs.size} seções.")))
            return
        }
        answerDevelop(secs[i].title, "desenvolver ${secs[i].title}")
        if (i + 1 < secs.size) {
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Parte ${i + 1} de ${secs.size}. Diga “próximo”, “refazer” ou “parar”.")))
        } else {
            guideIndex = null
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Última parte pronta ✓ — guia concluído.")))
        }
    }

    /**
     * Comandos do modo guiado. Retorna true se consumiu.
     * Roda antes das confirmações? Não: confirmação pendente tem prioridade
     * (chamador ordena); aqui só vale com guia ligado.
     */
    private suspend fun resolveGuide(text: String): Boolean {
        val i = guideIndex ?: return false
        val t = com.bettertalker.app.data.util.normalizeText(text)
        fun has(vararg ws: String) = ws.any { w -> t.contains(w) }
        return when {
            ChatIntent.isYes(text) || has("proximo", "proxima", "seguinte", "vamos", "vai", "continua") -> {
                guideIndex = i + 1
                guideStep()
                true
            }
            has("refazer", "repete", "de novo", "outra vez", "novamente") -> {
                guideStep()
                true
            }
            has("parar", "chega", "sair", "cancela", "fim", "basta", "para tudo", "para por aqui") -> {
                guideIndex = null
                post(false, "text", ChatCodec.escMap(mapOf(
                    "text" to "Guia pausado. Diga “me guie” para recomeçar do início.")))
                true
            }
            else -> false
        }
    }

    /**
     * Compõe com o modelo local via RAG estrito. Sem modelo/RAM, cai no
     * rascunho determinístico com aviso. Retorna true se consumiu.
     */
    private suspend fun answerCompose(sectionHint: String?, raw: String) {
        if (needOutline()) {
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Vincule um esboço primeiro. Toque no + para importar ou colar.")))
            return
        }
        val secs = outlineSections.value
        val exact = sectionHint?.let { h -> secs.firstOrNull { it.title == h } }
        val target = exact ?: when {
            sectionHint != null -> null
            secs.size == 1 -> secs.first()
            else -> null
        }
        if (target == null) {
            if (sectionHint == null && secs.size > 1) {
                val opts = secs.mapIndexed { i, s -> "${i + 1}. ${s.title}" }.joinToString("\n")
                pendingAsk = PendingAsk.Sections(secs.map { it.title })
                post(false, "text", ChatCodec.escMap(mapOf(
                    "text" to "Compor qual parte com a IA? Diga o número ou o nome:\n$opts")))
            } else {
                post(false, "text", ChatCodec.escMap(mapOf(
                    "text" to "Não achei a seção. Diga o nome dela como está no esboço.")))
            }
            return
        }
        if (!llm.isReady() || !com.bettertalker.app.data.ai.LlmConfig.ramOk(ramTotal())) {
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Modelo IA indisponível (veja a tela Modelo IA). Gerei o rascunho determinístico:")))
            answerDevelop(target.title, raw)
            return
        }
        val idx = secs.indexOf(target)
        val kindHint = ChatIntent.exampleKindHint(raw)?.takeIf { it != "any" }
        val kind = exampleKindOf(kindHint)
            ?: repo.kindFor(idx == 0, idx == secs.lastIndex, "")
        val scope = refScope()
        val q = listOf(target.title, target.body.take(800)).filter { it.isNotBlank() }.joinToString(" ")
        val content = com.bettertalker.app.data.repo.contentHits(
            repo.askScoped(q.ifBlank { target.title }, noteId, 6, maxWords = 8,
                scope.keys.toList(), scope)
        )
        if (content.isEmpty()) {
            val missing = missingMatterText()
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Sem matéria para compor “${target.title}”." +
                    (if (missing.isNotBlank()) "\n\n$missing" else ""))))
            return
        }
        val guideHits = repo.askScoped(
            repo.guideQuery(kind), noteId, 2,
            extraIds = scope.keys.toList(), extraLabels = scope
        )
        val kindLabel = when (kind) {
            com.bettertalker.app.data.repo.CopilotRepository.ExampleKind.INTRO -> "introdução"
            com.bettertalker.app.data.repo.CopilotRepository.ExampleKind.ILLUSTRATION -> "ilustração"
            com.bettertalker.app.data.repo.CopilotRepository.ExampleKind.CONCLUSION -> "conclusão"
            else -> "pergunta inicial"
        }
        val ctx = RagContext(
            sectionTitle = target.title,
            minutes = target.minutes,
            passages = content.take(5).mapIndexed { pi, h ->
                RagPassage(pi + 1, h.passage.text.take(300), h.source)
            },
            orientation = guideHits.firstOrNull()?.let {
                RagOrientation(it.source, kindLabel)
            },
            history = recentHistory(),
            taskKind = kindLabel
        )
        val prompt = buildRagPrompt(ctx)
        val res = try {
            llm.generate(prompt)
        } catch (_: Exception) {
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "A IA falhou aqui no aparelho. Gerei o rascunho determinístico:")))
            answerDevelop(target.title, raw)
            return
        }
        val text = res.getOrNull()
        if (text.isNullOrBlank() || hasRepetition(text)) {
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "A IA repetiu/travou — segue o rascunho determinístico:")))
            answerDevelop(target.title, raw)
            return
        }
        val check = checkCitations(text, content.take(5).map { it.passage.text })
        lastSection = target.title
        lastExampleKind = null
        lastWasDevelop = true
        pendingAsk = null
        val warn = if (check.ok) "" else "\n\n⚠️ Revise: ${check.violations.first()}"
        post(false, "text", ChatCodec.escMap(mapOf(
            "text" to "Redigido com IA local (revise):\n\n$text$warn")))
    }

    private fun ramTotal(): Long {
        return try {
            val am = app.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val info = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.totalMem
        } catch (_: Exception) {
            0L
        }
    }

    /** Últimas trocas resumidas em 1 linha para o prompt RAG. */
    private suspend fun recentHistory(): List<String> {
        return try {
            val nid = noteId ?: return emptyList()
            db.chatDao().all(nid).takeLast(8).map { e ->
                val who = if (e.fromMe) "você" else "copilot"
                val t = when (e.kind) {
                    "text" -> ChatCodec.unescMap(e.payload)["text"].orEmpty()
                    "ideas" -> "ideias p/ ${ChatCodec.unescMap(e.payload)["section"].orEmpty()}"
                    else -> e.kind
                }.replace(Regex("\\s+"), " ").trim().take(120)
                "$who: $t"
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun answerSummarize() {
        if (needOutline()) {
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Vincule um esboço primeiro para eu resumir com contexto.")))
            return
        }
        // resume a NOTA (o chat não tem campo de busca; _query ficaria sempre vazio)
        val scope = refScope()
        val hits = repo.askScoped(_noteText.value.take(500).ifBlank { "discurso" }, noteId,
            extraIds = scope.keys.toList(), extraLabels = scope)
        val s = repo.summary(hits)
        _summary.value = s
        post(false, "text", ChatCodec.escMap(mapOf("text" to s)))
    }

    private suspend fun answerCheckRefs() {
        val nid = noteId
        val fromNote = RefDetector.detect(_noteText.value)
        val fromOutline = if (nid == null) emptyList()
        else RefDetector.detectedFromJson(outlines.get(nid)?.refsJson.orEmpty())
        val statuses = repo.checkRefsList(RefDetector.unionRefs(fromNote, fromOutline))
        _refs.value = statuses
        if (statuses.isEmpty()) {
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Não encontrei menções a publicações nem na nota nem no esboço vinculado " +
                    "(ex: Sentinela, Despertai!, be, th).")))
            return
        }
        val title = if (fromOutline.isNotEmpty()) "Referências da nota e do esboço"
        else "Referências da nota"
        post(false, "refs", ChatCodec.escMap(mapOf(
            "title" to title,
            "detected" to RefDetector.detectedToJson(
                statuses.map { it.ref }))))
    }

    /**
     * Diz exatamente o que falta: matéria citada (não baixada) + bases.
     * Nunca manda "baixar" o que já está pronto.
     */
    private suspend fun missingMatterText(): String {
        val nid = noteId
        val missingRefs = if (nid == null) emptyList()
        else repo.outlineRefs(outlines.get(nid)?.refsJson.orEmpty()).filter { !it.resolved }
        val missingBases = repo.missingBases().map { repo.baseTitle(it) }
        val parts = mutableListOf<String>()
        if (missingRefs.isNotEmpty()) {
            parts += "Falta baixar a matéria citada: " +
                missingRefs.take(3).joinToString("; ") { it.ref.label } + "."
        }
        if (missingBases.isNotEmpty()) {
            parts += "Faltam as bases: ${missingBases.joinToString("; ")}."
        }
        return parts.joinToString("\n")
    }

    /** Esboço antigo (seções sem texto)? Sugere trazer o conteúdo da nota. */
    private fun staleBodiesHint(): String =
        if (outlineSections.value.isNotEmpty() && outlineSections.value.all { it.body.isBlank() }) {
            "\n\nAs seções estão sem texto — diga “sincronizar” para trazer o conteúdo da nota."
        } else ""

    private suspend fun answerInsert(index: Int?) {
        val cards = lastIdeaCards()
        if (cards.isEmpty()) {
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Ainda não gerei ideias. Peça “ideias para …” primeiro.")))
            return
        }
        if (index == null || index !in cards.indices) {
            pendingAsk = PendingAsk.Insert(cards.size)
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Qual delas? Diga o número (1 a ${cards.size}).")))
            return
        }
        val card = cards[index]
        pendingAsk = null
        insert(card, card.body, null)
        val dest = card.sectionTitle.ifBlank { null }?.let { " em “$it”" } ?: ""
        post(false, "text", ChatCodec.escMap(mapOf(
            "text" to "Inserido “${card.title}” na nota$dest. ✔")))
    }

    private suspend fun answerSections() {
        val nid = noteId
        if (nid == null || needOutline()) {
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Nenhum esboço vinculado. Toque no + para importar ou colar.")))
            return
        }
        val o = outlines.get(nid)
        val secs = if (o != null) OutlineParser.fromJson(o.sectionsJson) else outlineSections.value
        if (secs.isEmpty()) {
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "O esboço vinculado está vazio.")))
            return
        }
        val title = o?.title?.ifBlank { "Esboço" } ?: "Esboço"
        pendingAsk = PendingAsk.Sections(secs.map { it.title })
        post(false, "sections", ChatCodec.escMap(mapOf(
            "title" to "$title — toque em Gerar ou diga o número:",
            "items" to ChatCodec.sectionsToJson(
                secs.map { ChatCodec.SecItem(it.title, it.minutes, it.level) }))))
    }

    private suspend fun answerOutlineRefs() {
        val nid = noteId
        if (nid == null || needOutline()) {
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Nenhum esboço vinculado. Toque no + para importar ou colar.")))
            return
        }
        val refsJson = outlines.get(nid)?.refsJson.orEmpty()
        val statuses = repo.outlineRefs(refsJson)
        if (statuses.isEmpty()) {
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "O esboço não cita publicações para verificar.")))
            return
        }
        post(false, "refs", ChatCodec.escMap(mapOf(
            "title" to "Referências do esboço",
            "detected" to RefDetector.detectedToJson(statuses.map { it.ref }))))
    }

    private suspend fun answerSync() {
        val nid = noteId
        if (nid == null || needOutline()) {
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Nenhum esboço vinculado. Toque no + para importar ou colar.")))
            return
        }
        val result = outlines.refreshFromMarkdown(nid, _noteText.value, insertedTitles)
        val parts = mutableListOf<String>()
        if (result.updated.isNotEmpty()) {
            parts += "Atualizei ${result.updated.size} ${if (result.updated.size == 1) "seção" else "seções"} ✓"
        }
        if (result.missing.isNotEmpty()) {
            parts += "Não achei na nota (mantidas): ${result.missing.joinToString("; ")}."
        }
        if (parts.isEmpty() && result.added.isEmpty()) {
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Esboço e nota já estão iguais. ✓")))
            return
        }
        if (parts.isNotEmpty()) {
            post(false, "text", ChatCodec.escMap(mapOf("text" to parts.joinToString("\n"))))
        }
        askNextNew(nid, result.added)
    }

    /** Pergunta pelos tópicos novos um a um (sim/não). */
    private suspend fun askNextNew(nid: String, remaining: List<String>) {
        val next = remaining.firstOrNull() ?: return
        pendingConfirm = {
            outlines.addTopics(nid, listOf(next))
            postText("“$next” entrou para o esboço. ✓")
            askNextNew(nid, remaining.drop(1))
        }
        post(false, "text", ChatCodec.escMap(mapOf(
            "text" to "Adiciono “$next” como tópico? (sim/não)")))
    }

    private suspend fun answerSkeleton() {
        if (skeletonNow()) {
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Esqueleto enviado para a nota. ✔")))
        } else {
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Nenhum esboço vinculado. Toque no + para importar ou colar.")))
        }
    }

    private suspend fun answerUnlink() {
        val nid = noteId ?: return
        if (!repo.hasOutline(nid)) {
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Já não há esboço vinculado.")))
            return
        }
        unlinkNow()
        lastSection = null
        lastExampleKind = null
        pendingAsk = null
        pendingConfirm = null
        syncSuggestKey = null
        post(false, "text", ChatCodec.escMap(mapOf(
            "text" to "Esboço desvinculado. Toque no + para anexar outro.")))
    }

    private suspend fun answerBases() {
        val missing = repo.missingBases()
        if (missing.isEmpty()) {
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Bases prontas ✓ — Beneficie-se e Melhore já estão baixadas.")))
            return
        }
        post(false, "bases", ChatCodec.escMap(mapOf(
            "text" to "Faltam estas bases:",
            "slots" to missing.joinToString(","))))
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
        dismissedMerges.clear()
        _candTexts.value = emptyList()
        _previewRefsJson.value = "[]"
        refreshDraftMessage()
    }

    /** Recalcula fusões do draft atual (chamado após qualquer mutação). */
    private fun refreshMerges() {
        val cur = _draft.value
        val cands = cur.mapIndexed { i, d ->
            PastedOutlineAnalyzer.Candidate(d.title, i, d.included)
        }
        _merges.value = PastedOutlineAnalyzer.suggestMerges(cands)
            .filterNot { dismissedMerges.contains(mergeKey(cur[it.a].title, cur[it.b].title)) }
    }

    private fun mergeKey(a: String, b: String): String {
        val pair = listOf(
            com.bettertalker.app.data.util.normalizeText(a),
            com.bettertalker.app.data.util.normalizeText(b)
        ).sorted()
        return pair.joinToString("||")
    }

    fun importOutlineFile(uri: Uri) = viewModelScope.launch {
        _draftBusy.value = true
        _draftError.value = ""
        try {
            val preview = outlines.previewFile(uri)
            _draftName.value = preview.fileName
            _draftTitle.value = preview.parsed.title
            _draftTotal.value = preview.parsed.totalMinutes
            _draft.value = preview.parsed.sections.map { DraftSection(it.title, it.minutes, true, it.body, it.level) }
            _draftPreamble.value = preview.parsed.preamble
            _previewRefsJson.value = preview.refsJson
            syncDraftMessage()
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
        val (titled, rest) = PastedOutlineAnalyzer.splitTitle(text)
        val (cands, droppedCount) = PastedOutlineAnalyzer.candidates(rest.ifBlank { text })
        if (cands.isEmpty()) {
            _draftError.value = "Não encontrei tópicos no texto colado."
            _draftBusy.value = false
            return@launch
        }
        _candTexts.value = cands.map { it.text }
        // corpos: subtópicos e conteúdo entre tópicos viram corpo da seção
        val bodies = PastedOutlineAnalyzer.bodies(cands)
        _draft.value = cands.mapIndexed { i, c ->
            DraftSection(c.text, null, c.suggested, bodies.getOrNull(i).orEmpty(), c.level)
        }
        _dropped.value = droppedCount
        dismissedMerges.clear()
        refreshMerges()
        _draftName.value = "texto colado"
        _draftTitle.value = titled ?: cands.firstOrNull()?.text?.take(60) ?: "Esboço"
        _draftTotal.value = null
        _previewRefsJson.value = RefDetector.detectedToJson(RefDetector.detect(text))
        _draftBusy.value = false
        syncDraftMessage()
    }

    fun updateDraftTitle(i: Int, t: String) {
        val cur = _draft.value.toMutableList()
        if (i !in cur.indices) return
        cur[i] = cur[i].copy(title = t)
        _draft.value = cur
        refreshMerges()
        refreshDraftMessage()
    }

    fun setDraftTitle(t: String) { _draftTitle.value = t }
    fun setDraftTotal(m: Int?) { _draftTotal.value = m }

    fun updateDraftMinutes(i: Int, m: Int?) {
        val cur = _draft.value.toMutableList()
        if (i !in cur.indices) return
        cur[i] = cur[i].copy(minutes = m)
        _draft.value = cur
        refreshDraftMessage()
    }

    fun toggleDraftInclude(i: Int) {
        val cur = _draft.value.toMutableList()
        if (i !in cur.indices) return
        cur[i] = cur[i].copy(included = !cur[i].included)
        _draft.value = cur
        refreshDraftMessage()
    }

    fun removeDraft(i: Int) {
        val cur = _draft.value.toMutableList()
        if (i !in cur.indices) return
        cur.removeAt(i)
        _draft.value = cur
        refreshMerges()
        refreshDraftMessage()
    }

    fun acceptMerge(a: Int, b: Int) {
        val cur = _draft.value.toMutableList()
        if (a !in cur.indices || b !in cur.indices || a == b) return
        val ma = cur[a].minutes
        val mb = cur[b].minutes
        val la = cur[a].level
        val lb = cur[b].level
        val merged = DraftSection(
            PastedOutlineAnalyzer.mergeTitles(cur[a].title, cur[b].title),
            if (ma != null && mb != null) ma + mb else (ma ?: mb),
            included = cur[a].included || cur[b].included,
            body = listOf(cur[a].body, cur[b].body).filter { it.isNotBlank() }.joinToString("\n"),
            level = minOf(la, lb)
        )
        val lo = minOf(a, b)
        cur[lo] = merged
        cur.removeAt(maxOf(a, b))
        _draft.value = cur
        refreshMerges()
        refreshDraftMessage()
    }

    fun dismissMerge(a: Int, b: Int) {
        val cur = _draft.value
        if (a !in cur.indices || b !in cur.indices) return
        dismissedMerges += mergeKey(cur[a].title, cur[b].title)
        refreshMerges()
        refreshDraftMessage()
    }

    /** Recria a mensagem do draft (apaga a anterior): feedback inline na conversa. */
    private var lastDraftMsgId: String? = null

    /** Recria a mensagem do draft in-place (mesmo id): a bolha não pisca e o foco fica. */
    private var draftSyncJob: Job? = null

    private fun refreshDraftMessage() {
        // debounce: digitar no título não reposta a cada tecla
        draftSyncJob?.cancel()
        draftSyncJob = viewModelScope.launch {
            delay(800)
            syncDraftMessage()
        }
    }

    private suspend fun syncDraftMessage() {
        val nid = noteId ?: return
        val items = _draft.value
        if (items.isEmpty()) {
            lastDraftMsgId?.let { id ->
                try {
                    db.chatDao().delete(id)
                } catch (_: Exception) {
                }
                lastDraftMsgId = null
            }
            return
        }
        // in-place quando já existe (REPLACE): LazyColumn mantém a bolha e o foco
        val id = lastDraftMsgId ?: newId("msg")
        db.chatDao().put(
            ChatEntity(id, nid, false, "draft",
                ChatCodec.draftToJson(_draftTitle.value, items),
                System.currentTimeMillis())
        )
        lastDraftMsgId = id
    }

    fun linkDraft() = viewModelScope.launch {
        val nid = noteId ?: return@launch
        val sections = _draft.value.filter { it.included && it.title.isNotBlank() }
            .mapIndexed { i, d -> OutlineSection(d.title.trim(), d.minutes, i, d.body, d.level) }
        if (sections.size < 2) {
            _draftError.value = "Marque ao menos 2 tópicos para vincular."
            return@launch
        }
        try {
            outlines.link(
                nid, _draftName.value.ifBlank { "esboço" }, _draftTitle.value,
                _draftTotal.value, sections, _previewRefsJson.value, _draftPreamble.value
            )
        } catch (_: Exception) {
            _draftError.value = "Falha ao vincular o esboço. Tente de novo."
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Não consegui vincular o esboço. Tente de novo.")))
            return@launch
        }
        _skeletonEvent.value = com.bettertalker.app.data.util.skeletonMarkdown(
            sections, _draftPreamble.value
        )
        val refsJson = _previewRefsJson.value
        val theme = _draftTitle.value.trim()
        clearDraft()
        pendingAsk = null
        pendingConfirm = null
        lastSection = null
        // tema do esboço vira título da nota (anuncia; entrega via titleEvent)
        if (theme.isNotBlank() && theme != _noteTitle.value) {
            _titleEvent.value = theme
        }
        post(false, "text", ChatCodec.escMap(mapOf(
            "text" to "Esboço vinculado ✓ (${sections.size} seções) — o esqueleto foi para a nota." +
                (if (theme.isNotBlank()) "\nTítulo da nota: “$theme”." else "") +
                " Peça ideias por seção ou pergunte sobre um tema.")))
        // auto-verificação: só anuncia se faltar ref (direciona ao download)
        val missing = repo.outlineRefs(refsJson).filter { !it.resolved }
        if (missing.isNotEmpty()) {
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "O esboço cita ${missing.size} ${if (missing.size == 1) "publicação que ainda não está" else "publicações que ainda não estão"} baixada(s). Baixe no site para eu citar a matéria:")))
            post(false, "refs", ChatCodec.escMap(mapOf(
                "title" to "Referências do esboço",
                "detected" to RefDetector.detectedToJson(missing.map { it.ref }))))
        }
    }

    fun consumeSkeleton() { _skeletonEvent.value = null }

    /** Título da nota (tema do esboço manda): entregue ao editor via MainActivity. */
    private val _titleEvent = MutableStateFlow<String?>(null)
    val titleEvent = _titleEvent.asStateFlow()
    fun consumeTitle() { _titleEvent.value = null }

    /** Reinsere o esqueleto do esboço vinculado (recupera após morte do processo etc.). */
    fun reinsertSkeleton() = viewModelScope.launch { skeletonNow() }

    /** Lógica compartilhada com o chat; retorna false se não há esboço. */
    private suspend fun skeletonNow(): Boolean {
        val nid = noteId ?: return false
        val o = outlines.get(nid) ?: return false
        val (preamble, sections) = OutlineParser.parseEnvelope(
            o.sectionsJson.takeIf { it.isNotBlank() } ?: "[]"
        )
        if (sections.isEmpty()) return false
        _skeletonEvent.value = com.bettertalker.app.data.util.skeletonMarkdown(sections, preamble)
        return true
    }

    fun unlinkOutline() = viewModelScope.launch { unlinkNow() }

    /** Lógica compartilhada com o chat. */
    private suspend fun unlinkNow() {
        val nid = noteId ?: return
        outlines.unlink(nid)
        _ideas.value = emptyList()
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
                lib.bindDownload(ph, dmId, direct.url)
                lib.enqueueRegisterDownload(dmId, direct.fileName, ph)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        app, "Download iniciado — acompanhe na Biblioteca",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
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
