package com.bettertalker.app.ui.copilot

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bettertalker.app.data.copilot.ChatRunState
import com.bettertalker.app.data.copilot.ChatTurn
import com.bettertalker.app.data.copilot.EvidenceMeta
import com.bettertalker.app.data.copilot.FOLLOW_UP_SUGGESTIONS
import com.bettertalker.app.data.copilot.QUICK_ACTIONS
import com.bettertalker.app.data.copilot.buildTurnContext
import com.bettertalker.app.data.copilot.contextLabel
import com.bettertalker.app.data.copilot.friendlyChatError
import com.bettertalker.app.data.copilot.inferIntent
import com.bettertalker.app.data.copilot.MAX_SELECTION_CHARS
import com.bettertalker.app.data.copilot.provenanceSummary
import com.bettertalker.app.data.copilot.validateOutgoingMessage
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.db.ChatEntity
import com.bettertalker.app.data.db.OutlineEntity
import com.bettertalker.app.data.db.RoomTransactionRunner
import com.bettertalker.app.data.db.SpeechSectionEntity
import com.bettertalker.app.data.edit.CopilotEditProposal
import com.bettertalker.app.data.edit.hashText
import com.bettertalker.app.data.edit.EditBlock
import com.bettertalker.app.data.edit.EditOperation
import com.bettertalker.app.data.edit.EditProposalMode
import com.bettertalker.app.data.edit.applyEditProposal
import com.bettertalker.app.data.edit.parseEditProposal
import com.bettertalker.app.data.edit.renderAfterText
import com.bettertalker.app.data.edit.stripHtmlToText
import com.bettertalker.app.data.edit.validateEditProposal
import com.bettertalker.app.data.llm.GroundednessVerifier
import com.bettertalker.app.data.llm.LlmRequest
import com.bettertalker.app.data.llm.ProviderFactory
import com.bettertalker.app.data.llm.ResponseFormat
import com.bettertalker.app.data.prefs.SettingsStore
import com.bettertalker.app.data.repo.NotesRepository
import com.bettertalker.app.data.verify.TextVerification
import com.bettertalker.app.data.verify.verifyText
import com.bettertalker.app.data.repo.CopilotRepository
import com.bettertalker.app.data.repo.IdeaCard
import com.bettertalker.app.data.repo.ImportException
import com.bettertalker.app.data.repo.OutlineRepository
import com.bettertalker.app.data.repo.OutlineImportService
import com.bettertalker.app.data.repo.childTitles
import com.bettertalker.app.data.s34.S34ImportHook
import com.bettertalker.app.data.util.BASE_PUBS
import com.bettertalker.app.data.util.BasePub
import com.bettertalker.app.data.util.ChatCodec
import com.bettertalker.app.data.util.ChatIntent
import com.bettertalker.app.data.util.DocExtractors
import com.bettertalker.app.data.util.OutlineParser
import com.bettertalker.app.data.util.OutlineSection
import com.bettertalker.app.data.util.ParsedOutline
import com.bettertalker.app.data.util.PastedOutlineAnalyzer
import com.bettertalker.app.data.util.RefDetector
import com.bettertalker.app.data.util.S34Detector
import com.bettertalker.app.data.util.detectKind
import com.bettertalker.app.data.util.newId
import com.bettertalker.app.data.planning.truncateIfCut
import com.bettertalker.app.domain.planning.Dossier
import com.bettertalker.app.ui.editor.ChatContext
import com.bettertalker.app.ui.editor.DraftTarget
import com.bettertalker.app.ui.editor.roleLabel
import com.bettertalker.app.domain.speech.DiscourseType
import com.bettertalker.app.domain.speech.OutlineConversion
import com.bettertalker.app.domain.speech.OutlineConverter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class InsertRequest(val text: String, val heading: String?, val nonce: Long = System.nanoTime())

/**
 * T1 — corpus efetivamente injetado no prompt do turno (CONTENT + TRAINING +
 * estrutura do S-34: objetivo, conteúdo do ponto focado, subpontos e refs).
 * É contra ele que o pós-filtro de alucinação do chat confere a resposta.
 * Puro/testável.
 */
fun verificationCorpus(
    turnContext: com.bettertalker.app.data.copilot.ChatTurnContext
): List<String> = buildList {
    turnContext.pack.contentSources.forEach { add(it.text) }
    turnContext.pack.trainingSources.forEach { add(it.text) }
    turnContext.structural?.let { s ->
        s.objective?.let { add(it) }
        s.currentSection?.let { sec ->
            add(sec.content)
            sec.subsections.forEach { sub ->
                add(sub.content)
                sub.references.forEach { add(it.rawText) }
            }
            sec.references.forEach { add(it.rawText) }
        }
    }
}.filter { it.isNotBlank() }

/**
 * T2 — aviso não bloqueante de citações (padrão "⚠️ Revise"). `null` quando ok.
 * Consome o verificador existente [com.bettertalker.app.data.ai.checkCitations].
 * Puro/testável.
 */
fun citationRevisionNotice(text: String, corpus: List<String>): String? {
    val check = com.bettertalker.app.data.ai.checkCitations(text, corpus)
    if (check.ok) return null
    return "⚠️ Revise: " + check.violations.first()
}

/**
 * T2 — aviso não bloqueante de fidelidade oratória. `null` quando ok.
 * Consome o verificador existente
 * [com.bettertalker.app.data.copilot.OratoryFidelityCheck]. Puro/testável.
 */
fun fidelityRevisionNotice(
    report: com.bettertalker.app.data.copilot.OratoryFidelityCheck.Report
): String? {
    if (report.ok) return null
    val parts = buildList {
        if (report.inventedReferences.isNotEmpty()) {
            add("referências sem apoio: " + report.inventedReferences.joinToString(", "))
        }
        if (report.leakedReferences.isNotEmpty()) {
            add("referências de outro ponto: " + report.leakedReferences.joinToString(", "))
        }
        if (report.unsupportedNumbers.isNotEmpty()) {
            add("números sem apoio: " + report.unsupportedNumbers.joinToString(", "))
        }
    }
    return "⚠️ Revise: " + parts.joinToString("; ")
}

/** Tópico do draft em edição (mesmo formato persistido nas mensagens). */
typealias DraftSection = ChatCodec.DraftItem

class CopilotViewModel(ctx: android.content.Context, private val db: AppDatabase, private val noteId: String? = null) : ViewModel() {
    private val app = ctx.applicationContext
    private val repo = CopilotRepository(db)
    private val outlines = OutlineRepository(app, db)
    // 3.2.3c: persistência do esboço convertido (seções + sub-pontos).
    private val outlineImportService = OutlineImportService(
        transactionRunner = RoomTransactionRunner(db),
        sectionDao = db.speechSectionDao(),
        subPointDao = db.subPointDao(),
    )
    private val settings = SettingsStore(app)
    private val notes = NotesRepository(app, db)
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
    private val _dropped = MutableStateFlow(0)
    private val _merges = MutableStateFlow<List<PastedOutlineAnalyzer.MergeSuggestion>>(emptyList())
    /** pares dispensados (chave estável) para não ressugerir */
    private val dismissedMerges = mutableSetOf<String>()
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
                        // Corpo puro (sem título): foco de proposta/aceite. O título
                        // vive em campo separado e nunca está no richHtml — se o
                        // foco incluísse o título, o stale nunca bateria (§23).
                        _noteBody.value = n.mdText
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
    fun generateForSection(
        section: OutlineSection,
        neighbors: List<String>,
        subtopics: List<String> = emptyList()
    ) = viewModelScope.launch {
        _sectionBusy.value = section.title
        try {
            val scope = refScope()
            postSectionRefs(section)
            val cards = repo.ideasForSection(section, neighbors, _query.value, noteId,
                scope.keys.toList(), scope, subtopics)
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
            secs.getOrNull(idx - 1)?.title, secs.getOrNull(idx + 1)?.title),
            childTitles(secs, idx))
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

    /**
     * F2.3: manda um texto do chat para o tópico em foco. Entra na MESMA
     * fila de inserção do editor — o roteamento (tópico vs. legado) fica
     * no `queueInsertForTarget`.
     */
    fun insertTextIntoScope(text: String, heading: String? = null) {
        if (text.isBlank()) return
        pendingInserts += InsertRequest(text, heading)
        pumpInserts()
    }

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
        val draftItems: List<ChatCodec.DraftItem> = emptyList(),
        /** Origem da resposta (COPILOT default; LOCAL = retrieval/IA local). */
        val origin: MessageOrigin = MessageOrigin.COPILOT,
    )

    private val _chatBusy = MutableStateFlow(false)
    val chatBusy = _chatBusy.asStateFlow()
    private val _noteText = MutableStateFlow("")
    private val _noteTitle = MutableStateFlow("")
    private val _noteBody = MutableStateFlow("")

    // ---------- Fase 16: paridade com o chat da F15 ----------

    /**
     * Estados de geração do turno. `Error` e `Cancelled` liberam o composer —
     * é o bug que a F15 encontrou e corrigiu: o campo não pode ficar travado
     * depois de uma falha.
     */
    private val _runState = MutableStateFlow<ChatRunState>(ChatRunState.Idle)
    val runState = _runState.asStateFlow()

    /**
     * F2.1 — fases do provider on-device (só observa; o fluxo de send(),
     * answerRemote e persistência seguem intactos).
     */
    val localPhase: kotlinx.coroutines.flow.StateFlow<com.bettertalker.app.data.llm.LocalPhase> =
        com.bettertalker.app.data.llm.LocalProgress.phase
    val localPartial: kotlinx.coroutines.flow.StateFlow<String> =
        com.bettertalker.app.data.llm.LocalProgress.partialText

    /** Mensagem que originou o turno atual, para "Tentar novamente". */
    private var lastUserMessage: String? = null

    /** Proveniência do último turno, para "Fontes e apoio" (§14 F15). */
    private val _chatEvidence = MutableStateFlow<List<EvidenceMeta>>(emptyList())
    val chatEvidence = _chatEvidence.asStateFlow()

    val evidenceSummary: kotlinx.coroutines.flow.StateFlow<String> =
        MutableStateFlow(provenanceSummary(emptyList())).also { s ->
            viewModelScope.launch { _chatEvidence.collect { s.value = provenanceSummary(it) } }
        }.asStateFlow()

    private val _activeBlockTitle = MutableStateFlow<String?>(null)

    /** Bloco em foco; o usuário nunca digita id de bloco. */
    val activeBlockTitle = _activeBlockTitle.asStateFlow()

    /**
     * Fase 18 §17: trecho selecionado no editor. Prioridade máxima no
     * contexto (seleção > bloco > discurso) — e o prompt recebe o MESMO
     * texto do rótulo, nunca o bloco inteiro rotulado de "seleção" (§16).
     */
    private val _selection = MutableStateFlow("")
    val selection = _selection.asStateFlow()

    fun setSelection(text: String) {
        _selection.value = text.trim().take(MAX_SELECTION_CHARS)
    }

    /**
     * T2 (Bug #10) — foco do editor (seção ativa) informado pela rota do
     * editor. Sem isto, `currentFocusText()` caía no mdText (vazio nas notas
     * por seções) e "Criar proposta" sempre pedia seleção.
     */
    fun setActiveBlock(title: String?) {
        _activeBlockTitle.value = normalizeActiveBlockTitle(title)
    }

    /**
     * Provider do dossiê da seção ativa. Injetado pelo MainActivity via
     * `setDossierProvider { target -> editorVm.buildDossierFor(target) }`.
     *
     * Null quando o chat ainda não foi ligado a um editor.
     */
    private var dossierProvider: (suspend (DraftTarget?) -> Dossier?)? = null

    /**
     * Fase 3.5b.3: registra o provider do dossiê. Recebe o alvo explícito
     * (F2b); null = seleção viva do editor (comportamento anterior).
     */
    fun setDossierProvider(provider: suspend (DraftTarget?) -> Dossier?) {
        dossierProvider = provider
    }

    /** Monta o bloco de contexto do dossiê para o prompt do chat (F2b). Puro. */
    private val dossierPromptBuilder =
        com.bettertalker.app.data.planning.DefaultDossierPromptBuilder()

    /**
     * Último dossiê construído (para inspeção/debug; o consumo real via
     * prompt vem em 3.5d).
     */
    private var lastDossier: Dossier? = null

    /** Último dossiê adquirido no `send()` (null se indisponível). */
    fun lastBuiltDossier(): Dossier? = lastDossier

    /** Rótulo de contexto mostrado acima da conversa (§13 F15, §16 F18). */
    val contextLabelText: kotlinx.coroutines.flow.StateFlow<String> =
        MutableStateFlow(contextLabel(null, null)).also { s ->
        viewModelScope.launch {
            combine(_noteTitle, _activeBlockTitle, _selection) { title, block, sel ->
                contextLabel(block, title, sel)
            }.collect { s.value = it }
        }
    }.asStateFlow()

    /**
     * F20-D: última geração oratória da sessão (volátil por design).
     * É a fonte que faz "Melhore." continuar na mesma parte.
     */
    private var lastOratory: com.bettertalker.app.data.copilot.OratorySession.LastGeneration? = null

    /** Documento S-34 do turno corrente (para roteamento e geração). */
    private var _lastS34Document: com.bettertalker.app.data.s34.S34Document? = null

    /** Sugestões de continuação da F15, para reuso na UI. */
    val followUps: List<String> = FOLLOW_UP_SUGGESTIONS

    // ---------- Onboarding contextual F2a: prontidão + contexto empurrado ----------

    private val _readiness =
        MutableStateFlow<com.bettertalker.app.ui.editor.NoteReadiness?>(null)
    val readiness: StateFlow<com.bettertalker.app.ui.editor.NoteReadiness?> =
        _readiness.asStateFlow()

    private val _setupBannerDismissed = MutableStateFlow(false)
    val setupBannerDismissed: StateFlow<Boolean> = _setupBannerDismissed.asStateFlow()

    /**
     * Recebe o contexto empurrado pelo FAB do editor. A prontidão que
     * vem junto pode estar levemente defasada — [refreshReadiness]
     * confirma na entrada do chat.
     */
    /**
     * F2b: alvo empurrado pelo FAB (F2a). Volátil (vive no VM, como o
     * proposalUndo); `setPushedContext` é a única escrita. O `send()`
     * monta o dossiê a partir dele (precedência: alvo explícito >
     * seleção viva > nada). Fica até o próximo push; o VM é por nota,
     * então trocar de nota já limpa.
     */
    private var _pushedContext: ChatContext? = null

    /**
     * F2.2-fix: "alvo empurrado" = sectionId/subPointId presentes (não a
     * mera presença do objeto — o FAB empurra contexto até em chat genérico).
     * Sem alvo, o RAG amplo fica intacto (documentado no answerRemote).
     */
    private fun hasPushedTarget(): Boolean =
        _pushedContext?.sectionId != null || _pushedContext?.subPointId != null

    /**
     * F2.3: escopo da conversa atual. `null` = conversa global da nota;
     * caso contrário, o id da seção/tópico em foco. Mensagens de um tópico
     * não vazam para o histórico de outro.
     */
    private val _chatScope = MutableStateFlow<String?>(null)
    val chatScope: StateFlow<String?> = _chatScope.asStateFlow()

    /**
     * F2.3: alvo atual do chat, para rotear inserções para o tópico certo.
     * null = chat global (mantém o comportamento legado de inserção).
     */
    fun currentTarget(): DraftTarget? {
        val ctx = _pushedContext ?: return null
        val sectionId = ctx.sectionId ?: return null
        return if (ctx.subPointId != null) DraftTarget.SubPoint(sectionId, ctx.subPointId)
        else DraftTarget.Section(sectionId)
    }

    fun setPushedContext(ctx: ChatContext?) {
        _pushedContext = ctx
        // F2.3: a conversa segue o foco (tópico vs. global).
        _chatScope.value = ctx?.sectionId
        _readiness.value = ctx?.readiness
        // Nova entrada no chat → banner volta (se ainda incompleto).
        _setupBannerDismissed.value = false
        // Header "Conversando sobre": resolução assíncrona (1-2 queries
        // Room); reset imediato evita rótulo stale da entrada anterior.
        _conversationLabel.value = null
        if (ctx != null) {
            viewModelScope.launch {
                _conversationLabel.value = resolveConversationLabel(ctx)
            }
        }
    }

    /** Rótulo do alvo empurrado ("Conversando sobre: …"). Null = sem header. */
    private val _conversationLabel = MutableStateFlow<String?>(null)
    val conversationLabel: StateFlow<String?> = _conversationLabel.asStateFlow()

    /**
     * Resolve o rótulo legível do alvo via DAOs (sem depender do editor).
     * Null se os ids não existirem mais.
     */
    private suspend fun resolveConversationLabel(ctx: ChatContext): String? {
        val sectionId = ctx.sectionId ?: return null
        val section = db.speechSectionDao().get(sectionId) ?: return null
        val role = roleLabel(section.role)
        val subPointId = ctx.subPointId
        return if (subPointId != null) {
            val subPoints = db.subPointDao().forSection(sectionId)
            val subIndex = subPoints.indexOfFirst { it.id == subPointId }
            if (subIndex < 0) return null
            "Conversando sobre: Seção ${section.order + 1} · Sub-ponto ${subIndex + 1}"
        } else {
            "Conversando sobre: Seção ${section.order + 1} ($role)"
        }
    }

    /**
     * Relê a prontidão (após importar esboço, baixar bases, etc.).
     * Se o estado voltou a incompleto, o banner reaparece.
     */
    suspend fun refreshReadiness() {
        val nid = noteId ?: return
        val fresh = repo.readNoteReadiness(
            noteId = nid,
            hasSections = db.speechSectionDao().forNote(nid).isNotEmpty(),
            outlineRefsJson = outlines.get(nid)?.refsJson.orEmpty(),
        )
        if (fresh != _readiness.value) _setupBannerDismissed.value = false
        _readiness.value = fresh
    }

    fun dismissSetupBanner() { _setupBannerDismissed.value = true }

    /** Atalhos da F15 — todos entram pelo mesmo `send()`. */
    val quickActions = QUICK_ACTIONS

    /** Dispensa o erro e devolve o composer. */
    fun dismissError() {
        _runState.value = ChatRunState.Idle
    }

    /** Reenvia a última mensagem pelo mesmo pipeline. */
    fun retry() {
        val msg = lastUserMessage ?: return
        dismissError()
        send(msg)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val messages = if (noteId != null) {
        _chatScope.flatMapLatest { scope ->
            db.chatDao().observeScoped(noteId, scope)
        }.map { list ->
            list.map { e ->
                when (e.kind) {
                    "ideas" -> {
                        val m = ChatCodec.unescMap(e.payload)
                        ChatItem(e.id, e.fromMe, e.kind,
                            section = m["section"].orEmpty(),
                            cards = ChatCodec.cardsFromJson(m["cards"].orEmpty()),
                            origin = messageOriginOf(m))
                    }
                    "refs" -> {
                        val m = ChatCodec.unescMap(e.payload)
                        ChatItem(e.id, e.fromMe, e.kind, text = m["title"].orEmpty(),
                            detectedJson = m["detected"].orEmpty(),
                            origin = messageOriginOf(m))
                    }
                    "bases" -> {
                        val m = ChatCodec.unescMap(e.payload)
                        ChatItem(e.id, e.fromMe, e.kind, text = m["text"].orEmpty(),
                            slots = m["slots"].orEmpty().split(",").filter { it.isNotBlank() },
                            origin = messageOriginOf(m))
                    }
                    "sections" -> {
                        val m = ChatCodec.unescMap(e.payload)
                        ChatItem(e.id, e.fromMe, e.kind, text = m["title"].orEmpty(),
                            secItems = ChatCodec.sectionsFromJson(m["items"].orEmpty()),
                            origin = messageOriginOf(m))
                    }
                    "draft" -> {
                        val (title, items) = ChatCodec.draftFromJson(e.payload)
                        ChatItem(e.id, e.fromMe, e.kind, draftTitle = title, draftItems = items)
                    }
                    else -> {
                        val m = ChatCodec.unescMap(e.payload)
                        ChatItem(e.id, e.fromMe, "text",
                            text = m["text"].orEmpty(), origin = messageOriginOf(m))
                    }
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    } else {
        MutableStateFlow(emptyList())
    }

    private suspend fun post(
        fromMe: Boolean,
        kind: String,
        payload: String,
        origin: MessageOrigin = MessageOrigin.COPILOT,
    ) {
        val nid = noteId ?: return
        // Origem embutida no JSON do payload (sem migration; histórico
        // antigo sem a chave decodifica como COPILOT).
        val withOrigin = ChatCodec.unescMap(payload)
            .toMutableMap().also { it["origin"] = origin.name }
        db.chatDao().put(
            ChatEntity(
                newId("msg"), nid, fromMe, kind, ChatCodec.escMap(withOrigin),
                System.currentTimeMillis(),
                // F2.3: a mensagem pertence ao escopo da conversa atual.
                sectionId = _chatScope.value,
            )
        )
    }

    private fun postText(t: String, origin: MessageOrigin = MessageOrigin.COPILOT) = viewModelScope.launch {
        post(false, "text", ChatCodec.escMap(mapOf("text" to t)), origin)
    }

    private suspend fun greetText(
        readiness: com.bettertalker.app.ui.editor.NoteReadiness? = null,
    ): String {
        val nid = noteId
        val title = nid?.let { db.noteDao().get(it)?.title }.orEmpty().ifBlank { "sua nota" }
        val outline = nid?.let { outlines.get(it) }
        val secs = if (outline != null) OutlineParser.fromJson(outline.sectionsJson) else emptyList()
        val missing = repo.missingBases().map { repo.baseTitle(it) }
        // Abertura da F15: convite, não formulário. O usuário não escolhe
        // intent, fonte nem provider — escreve e o pipeline resolve.
        val sb = StringBuilder("Como posso ajudar com este discurso?\n\n")
        sb.append("Escreva o que você quer. Eu cuido do contexto, das fontes e do bloco em foco.")
        if (secs.isNotEmpty()) {
            sb.append("\n\nEsboço com ${secs.size} seções — sigo a linha de raciocínio dele.")
        } else {
            sb.append("\n\nToque no + abaixo para importar ou colar o esboço — assim sigo a linha de raciocínio.")
        }
        if (missing.isNotEmpty()) {
            sb.append("\n\nFaltam as bases: ${missing.joinToString("; ")}.")
        }
        // Onboarding F2a: menciona o que falta quando o setup está incompleto.
        if (readiness != null && !readiness.isSetupComplete) {
            sb.append("\n\nAinda não temos o material de apoio completo — o banner acima mostra o que falta.")
        }
        return sb.toString()
    }

    private suspend fun ensureGreeting() {
        val nid = noteId ?: return
        if (db.chatDao().all(nid).isEmpty()) {
            val readiness = runCatching {
                repo.readNoteReadiness(
                    noteId = nid,
                    hasSections = db.speechSectionDao().forNote(nid).isNotEmpty(),
                    outlineRefsJson = outlines.get(nid)?.refsJson.orEmpty(),
                )
            }.getOrNull()
            post(false, "text", ChatCodec.escMap(mapOf("text" to greetText(readiness))))
        }
    }

    fun clearChat() = viewModelScope.launch {
        val nid = noteId ?: return@launch
        // F2.3: limpa só a conversa do escopo atual (tópico ou global).
        db.chatDao().clearScoped(nid, _chatScope.value)
        if (_chatScope.value == null) ensureGreeting()
    }

    /** Resolve refs salvas numa mensagem contra os anexos atuais (p/ renderizar). */
    suspend fun resolveChatRefs(detectedJson: String): List<RefDetector.RefStatus> =
        repo.outlineRefs(detectedJson)

    private suspend fun lastIdeaCards(): List<IdeaCard> {
        val nid = noteId ?: return emptyList()
        val last = db.chatDao().allScoped(nid, _chatScope.value)
            .lastOrNull { it.kind == "ideas" } ?: return emptyList()
        return ChatCodec.cardsFromJson(ChatCodec.unescMap(last.payload)["cards"].orEmpty())
    }

    /**
     * Dispensa um cartão da mensagem (números exibidos == lista real).
     * Atualiza o payload no banco; mensagem vazia é apagada.
     */
    fun dismissIdeaCard(messageId: String, index: Int) = viewModelScope.launch {
        val nid = noteId ?: return@launch
        val e = db.chatDao().allScoped(nid, _chatScope.value)
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

    /**
     * Ponto de entrada ÚNICO da conversa (§4 F15): mensagem livre, quick action
     * e sugestão de continuação entram todos por aqui, com o mesmo contexto.
     * Não há caminho alternativo que monte prompt diferente.
     */
    fun send(raw: String) {
        // Mensagem vazia nunca sai: mesmo gate do web.
        val text = validateOutgoingMessage(raw) ?: return
        if (noteId == null) return
        // mínimo anti rajada/toque duplo: a UI também desabilita o envio com chatBusy
        if (_chatBusy.value) return
        lastUserMessage = text
        viewModelScope.launch {
            _runState.value = ChatRunState.Sending
            // Histórico e first ANTES do post: a pergunta atual não contamina
            // a continuidade, e a primeira mensagem é detectada de verdade.
            val historyBefore = conversationTurnsScoped()
            val isFirst = historyBefore.none { it.fromMe }
            // §16: seleção > bloco > discurso. O prompt recebe exatamente o
            // que o rótulo anuncia — sem rotular bloco de "seleção".
            // Fase 3.5b.3: enriquece o contexto com o dossiê (quando disponível).
            // Adquirido e armazenado; a injeção no prompt vem em 3.5d.
            // F2b: alvo fixo do FAB (precedência: explícito > seleção viva > nada).
            val target: DraftTarget? = _pushedContext?.let { ctx ->
                val sectionId = ctx.sectionId
                when {
                    sectionId != null && ctx.subPointId != null ->
                        DraftTarget.SubPoint(sectionId, ctx.subPointId)
                    sectionId != null ->
                        DraftTarget.Section(sectionId)
                    else -> null
                }
            }
            val dossier = try {
                dossierProvider?.invoke(target)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            lastDossier = dossier
            val contextBlock = dossier?.let { dossierPromptBuilder.buildContextBlock(it) }
            val blockText = currentFocusText()
            post(true, "text", ChatCodec.escMap(mapOf("text" to text)))
            _chatBusy.value = true
            // F2.1-fix: o try cobre TUDO a partir daqui (intenção, RAG,
            // roteamento e geração). Antes, os return@launch das rotas
            // (OutOfScope/NothingToRefine/ProposalReply/Oratory) ficavam fora
            // do try e pulavam o finally — travando composer e runState.
            try {
            // T3 (Mini Discurso): comando determinístico de inserção — nunca
            // vai ao LLM (funciona com qualquer provider).
            if (resolveInsertCommand(text)) return@launch
            // A intenção é interna: escolhe a trilha de recuperação e o foco do
            // prompt, e nunca é exibida ao usuário.
            val intent = inferIntent(text, isFirst)
            // F2.1 — marca a fase real de leitura das fontes (RAG Room) quando
            // a rota é o Gemma local. Só observa; o fluxo segue intacto.
            if (ProviderFactory.resolveRemote(settings, app).providerId ==
                ProviderFactory.PROVIDER_LOCAL_GEMMA
            ) {
                com.bettertalker.app.data.llm.LocalProgress.set(
                    com.bettertalker.app.data.llm.LocalPhase.ReadingSources
                )
            }
            val turnContext = buildTurnFor(text, intent.trainingCategory, historyBefore, isFirst, blockText)
            _chatEvidence.value = turnContext.evidence
            _runState.value = ChatRunState.Generating
            // F20-D: roteamento natural ANTES do chat — oratória, consulta
            // estrutural, réplica de proposta ou chat geral.
            val route = com.bettertalker.app.data.copilot.ChatRouter.route(
                text,
                _lastS34Document,
                turnContext.structural?.currentSection?.id,
                lastOratory
            )
            when (route) {
                is com.bettertalker.app.data.copilot.ChatRouter.Route.OutOfScope -> {
                    postText(route.message)
                    return@launch
                }
                is com.bettertalker.app.data.copilot.ChatRouter.Route.NothingToRefine -> {
                    postText(route.message)
                    return@launch
                }
                is com.bettertalker.app.data.copilot.ChatRouter.Route.ProposalReply -> {
                    postText(
                        if (route.accept) "Use o botão Aceitar no cartão da proposta."
                        else "Use o botão Rejeitar no cartão da proposta."
                    )
                    return@launch
                }
                is com.bettertalker.app.data.copilot.ChatRouter.Route.Oratory -> {
                    lastOratory = com.bettertalker.app.data.copilot.OratorySession.LastGeneration(
                        route.mode, route.sectionId
                    )
                    // F20-F1: provider selecionado (Gemini ou Groq/Qwen) + sua chave.
                    val remote = ProviderFactory.resolveRemote(settings, app)
                    if (!ProviderFactory.useRemoteRoute(remote)) {
                        postText("Configure a chave de IA na tela Modelo IA para gerar esta parte.")
                        return@launch
                    }
                    // F2e: sem S-34 mas com esboço vinculado (seções
                    // convertidas) → degrada para o chat normal, que já
                    // tem dossiê injetado. Sem esboço nenhum, o
                    // generateOratory mantém a mensagem "Importe o S-34".
                    val outlineNid = noteId ?: return@launch
                    if (shouldDegradeOratoryToChat(
                            hasS34Document = _lastS34Document != null,
                            hasLinkedOutline = outlines.get(outlineNid) != null,
                        )
                    ) {
                        answerRemote(text, turnContext, historyBefore, isFirst, blockText, remote, contextBlock, hasPushedTarget())
                        return@launch
                    }
                    generateOratory(route, text, remote, contextBlock)
                    return@launch
                }
                is com.bettertalker.app.data.copilot.ChatRouter.Route.StructuralQuery,
                is com.bettertalker.app.data.copilot.ChatRouter.Route.General -> Unit
            }

            // Fase 18: com chave BYOD, a rota nova (LLM real) decide.
            // Sem chave, o motor legado local continua (comportamento atual).
            // F20-F1: provider selecionado (Gemini ou Groq/Qwen) + sua chave.
            // (try aberto acima cobre também as rotas com return@launch.)
            val remote = ProviderFactory.resolveRemote(settings, app)
                if (ProviderFactory.useRemoteRoute(remote)) {
                    // T3 (Bug #7): comandos de referência são determinísticos e
                    // precisam funcionar com provider ativo — postam o card
                    // (📖 No acervo / ⚠️ sem fonte) em vez de irem ao modelo.
                    val refsIntent = ChatIntent.classify(
                        text,
                        outlineSections.value.map { ChatIntent.SectionRef(it.title, it.body) }
                    )
                    if (isRefsCommand(refsIntent)) {
                        if (refsIntent is ChatIntent.Intent.OutlineRefs) answerOutlineRefs()
                        else answerCheckRefs()
                        return@launch
                    }
                    answerRemote(text, turnContext, historyBefore, isFirst, blockText, remote, contextBlock, hasPushedTarget())
                } else {
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
                        is ChatIntent.Intent.Compose -> answerCompose()
                        ChatIntent.Intent.Guided -> startGuided()
                        is ChatIntent.Intent.Develop -> answerDevelop(intent.sectionHint, text)
                        ChatIntent.Intent.Summarize -> answerSummarize()
                        ChatIntent.Intent.CheckRefs -> answerCheckRefs()
                        ChatIntent.Intent.OutlineRefs -> answerOutlineRefs()
                        ChatIntent.Intent.Sections -> answerSections()
                        ChatIntent.Intent.Sync -> answerSync()
                        ChatIntent.Intent.Unlink -> answerUnlink()
                        ChatIntent.Intent.Bases -> answerBases()
                        is ChatIntent.Intent.Insert -> answerInsert(intent.index)
                        ChatIntent.Intent.Help -> post(false, "text", ChatCodec.escMap(mapOf("text" to helpText())))
                        ChatIntent.Intent.Thanks -> post(false, "text",
                            ChatCodec.escMap(mapOf("text" to "Por nada! Seguimos juntos no discurso. 🙌")))
                    }
                }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelamento não é erro: o discurso fica intacto e o
                // composer volta (§25 F15).
                _runState.value = ChatRunState.Cancelled()
                throw e
            } catch (e: Exception) {
                // Só texto humano. Nem HTTP, nem stack, nem nome de exceção.
                // Log técnico (classe/código/tentativas, sem chave e sem conteúdo).
                val code = (e as? com.bettertalker.app.data.llm.ProviderError)?.code
                android.util.Log.e("CopilotLLM", "send failed " +
                    e.javaClass.simpleName + " code=$code attempts=" +
                    ((e as? com.bettertalker.app.data.llm.ProviderError)?.attempts))
                val msg = friendlyChatError(e)
                _runState.value = ChatRunState.Error(msg)
                postText(msg)
            } finally {
                // O composer SEMPRE destrava aqui — inclusive depois de erro.
                _chatBusy.value = false
                if (_runState.value is ChatRunState.Generating || _runState.value is ChatRunState.Sending) {
                    _runState.value = ChatRunState.Success
                }
            }
        }
    }

    /**
     * Contexto do turno: bloco em foco, histórico e as trilhas separadas.
     * CONTENT responde pelo "quê"; TRAINING só pelo "como apresentar" e nunca
     * entra como fonte factual.
     *
     * [history]/[isFirst]/[blockText] chegam capturados ANTES do post da
     * mensagem atual: o histórico nunca inclui a própria pergunta, e a
     * primeira mensagem é detectada de verdade.
     */
    private suspend fun buildTurnFor(
        text: String,
        trainingCategory: com.bettertalker.app.data.domain.TrainingCategory?,
        history: List<ChatTurn>,
        isFirst: Boolean,
        blockText: String
    ): com.bettertalker.app.data.copilot.ChatTurnContext {
        // RAG real (Fase 18 §9): RoomContextPackRepository + HybridRetrieval,
        // escopo por discurso (bases prontas + vinculados + citados).
        // Verificação factual pede CONTENT: treinamento fora, sempre.
        val cited = refScope()
        val scope = noteScope(cited.keys.toList())
        val packRepo = com.bettertalker.app.data.repo.RoomContextPackRepository(
            com.bettertalker.app.data.repo.RoomRetrievalRepository(
                db.passageDao(), db.attachmentDao()),
            com.bettertalker.app.data.repo.RoomTrainingRepository(
                db.passageDao(), db.attachmentDao())
        )
        val pack = packRepo.buildPack(
            query = (blockText + " " + text).take(2000),
            scope = scope,
            includeTraining = trainingCategory != null,
            trainingCategory = trainingCategory
        )
        // F19-B.5: S-34 estrutural do attachment em foco (se houver).
        // Consome a B.4; NoOutline mantém o caminho legado intacto.
        val (structural, oratory) = structuralFor(cited.keys.toList(), text)
        return com.bettertalker.app.data.copilot.buildTurnContextFromPack(
            message = text,
            history = history,
            isFirstMessage = isFirst,
            pack = pack,
            blockTitle = _activeBlockTitle.value,
            blockMinutes = null,
            blockText = blockText,
            structural = structural,
            oratory = oratory
        )
    }

    /**
     * Estrutura do S-34 para este turno (B.4 → B.5). O ponto atual é a
     * seleção/bloco em foco; sem correspondência o estado é explícito.
     */
    private suspend fun structuralFor(
        candidateIds: List<String>,
        text: String
    ): Pair<
        com.bettertalker.app.data.copilot.OutlineStructureContext?,
        com.bettertalker.app.data.copilot.OratoryStructure.Inferred?
        > {
        val retriever = com.bettertalker.app.data.repo.S34StructuralRetriever(
            com.bettertalker.app.data.repo.S34OutlineRepository(db.s34Dao())
        )
        // F20-F1: anexos vinculados à nota (Anexos da nota) também são
        // candidatos — antes, só anexos CITADOS no texto alcançavam o chat
        // (evidência física: S-34 vinculado respondia "Não tenho a estrutura").
        // Citados primeiro (mais específicos), vinculados depois, sem duplicar.
        val linkedIds = noteId?.let { nid ->
            db.attachmentDao().all()
                .filter { it.noteId == nid } // T1: inclui o S-34 importado (indexed=false)
                .map { it.id }
        }.orEmpty()
        for (id in s34CandidateIds(candidateIds, linkedIds)) {
            val result = retriever.retrieve(
                sourceAttachmentId = id,
                sectionHint = _activeBlockTitle.value ?: _selection.value,
                query = text
            )
                if (result !is com.bettertalker.app.data.repo.S34StructuralRetriever.Result.NoOutline) {
                val doc = com.bettertalker.app.data.repo.S34OutlineRepository(db.s34Dao())
                    .getBySource(id)
                        val structural = com.bettertalker.app.data.copilot.structuralContextOf(result, doc)
                _lastS34Document = doc
                // F20-A: planejamento oratório derivado do MESMO documento.
                val oratory = doc?.let { d ->
                    (com.bettertalker.app.data.copilot.OratoryStructure.inferFrom(d, result)
                        as? com.bettertalker.app.data.copilot.OratoryStructure.Result.Ok)?.structure
                }
                return structural to oratory
            }
        }
        return null to null
    }

    /**
     * F20-D: geração oratória pelo MESMO pipeline F5 — proposta, nunca
     * editor. O alvo é o ponto resolvido pelo router (o modelo nunca escolhe).
     */
    private suspend fun generateOratory(
        route: com.bettertalker.app.data.copilot.ChatRouter.Route.Oratory,
        message: String,
        remote: ProviderFactory.RemoteConfig,
        contextBlock: String? = null,
    ) {
        val doc = _lastS34Document
        if (doc == null) {
            postText("Não tenho a estrutura deste discurso. Importe o S-34 para eu gerar com fidelidade.")
            return
        }
        val view = route.sectionId?.let {
            com.bettertalker.app.data.s34.S34StructuralRetrieval.scopeToSection(doc, it)
        }
        val spec = com.bettertalker.app.data.copilot.OratoryGeneration.spec(
            route.mode, doc, view, route.action
        )
        if (spec is com.bettertalker.app.data.copilot.OratoryGeneration.Result.CannotGenerate) {
            postText(spec.blocked.message)
            return
        }
        val ready = (spec as com.bettertalker.app.data.copilot.OratoryGeneration.Result.Ready).spec
        val focus = currentFocusText()
        // T2 (Bug #14): foco por título de seção → proposta aplica na seção.
        val section = sectionForFocus(focus)
        val secId = section?.id
        val secHash = section?.let {
            hashText(sectionProposalBlock(it.title, it.contentHtml))
        }
        val targetId = "focus"
        val mode = if (route.action == com.bettertalker.app.data.copilot.OratoryGeneration.Action.REPLACE) {
            com.bettertalker.app.data.edit.EditProposalMode.IMPROVE
        } else {
            com.bettertalker.app.data.edit.EditProposalMode.INSERT
        }
        try {
            val provider = ProviderFactory.createFor(remote, app)
            val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                provider.generate(
                    com.bettertalker.app.data.llm.LlmRequest(
                        text = focus,
                        action = com.bettertalker.app.data.llm.LlmAction.CHAT,
                        message = message,
                        responseFormat = ResponseFormat.EDIT_PROPOSAL,
                        editMode = mode,
                        oratorySpec = ready,
                        // F20-E/F1: 1000 truncava o JSON; 2048 reserva ~1541 e
                        // SEMPRE toma 429 no OTPM 1000/min do plano gratuito
                        // (comprovado no aparelho). 1280 nunca truncou em 23
                        // execuções reais (saídas observadas <= 1207).
                        maxOutputTokens = 1280,
                        contextBlock = contextBlock,
                    )
                )
            }
            val blocks = listOf(com.bettertalker.app.data.edit.EditBlock(targetId, focus))
            when (val r = com.bettertalker.app.data.edit.parseEditProposal(
                res.text, blocks, targetId, mode
            )) {
                is com.bettertalker.app.data.edit.ParseResult.Ok -> {
                    // T2 — fidelidade oratória (não bloqueante): confere refs e
                    // números do TEXTO GERADO contra o Spec; não olha o foco
                    // do usuário para não acusar números que já eram dele.
                    val generated = r.proposal.operations.joinToString("\n\n") { op ->
                        when (op) {
                            is EditOperation.Insert -> stripHtmlToText(op.contentHtml)
                            is EditOperation.Replace -> stripHtmlToText(op.contentHtml)
                            is EditOperation.Delete -> ""
                        }
                    }.trim().ifBlank { renderAfterText(r.proposal, focus) }
                    val fidelity = com.bettertalker.app.data.copilot.OratoryFidelityCheck
                        .check(generated, ready)
                    val notice = fidelityRevisionNotice(fidelity)
                    if (notice != null) {
                        android.util.Log.w(
                            "CopilotLLM",
                            "oratória: aviso de fidelidade (invented=${fidelity.inventedReferences.size} " +
                                "leaked=${fidelity.leakedReferences.size} unsupported=${fidelity.unsupportedNumbers.size})"
                        )
                    }
                    _proposal.value = ProposalUi(
                        r.proposal, focus, sectionId = secId, sectionBaseHash = secHash,
                        notice = notice
                    )
                }
                is com.bettertalker.app.data.edit.ParseResult.Invalid -> postText(
                    "A resposta do modelo veio em formato inválido. Tente gerar novamente."
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            _runState.value = ChatRunState.Cancelled()
            throw e
        } catch (e: Exception) {
                postText(friendlyChatError(e))
        }
    }

    /**
     * Escopo por discurso (§9): bases prontas + anexos vinculados à nota +
     * citados na nota/esboço — mesma composição do askScoped legado, mas
     * particionado estruturalmente (sourceType) em vez de por título.
     */
    private suspend fun noteScope(extraIds: List<String>): com.bettertalker.app.data.domain.RetrievalScope {
        val full = com.bettertalker.app.data.repo.RoomPublicationRepository(db.attachmentDao()).scope()
        val allowed = mutableSetOf<String>()
        for (pub in BASE_PUBS) {
            db.attachmentDao().baseReady(pub.slot)?.let { allowed += it.id }
        }
        if (noteId != null) {
            allowed += db.attachmentDao().all()
                .filter { it.noteId == noteId && it.indexed }.map { it.id }
        }
        allowed += extraIds
        return com.bettertalker.app.data.domain.RetrievalScope(
            contentSourceIds = full.contentSourceIds.filter { it in allowed },
            trainingSourceIds = full.trainingSourceIds.filter { it in allowed }
        )
    }

    /**
     * Rota nova (Fase 18): inferIntent → ContextPack → provider real.
     * Sem fallback silencioso para o motor legado: se o provider falhar,
     * o erro honesto sobe para o catch do send() (§5).
     */
    private suspend fun answerRemote(
        text: String,
        turnContext: com.bettertalker.app.data.copilot.ChatTurnContext,
        history: List<ChatTurn>,
        isFirst: Boolean,
        blockText: String,
        remote: ProviderFactory.RemoteConfig,
        contextBlock: String? = null,
        // FOCO: com alvo empurrado, o dossiê é o contexto principal e o
        // legado (nota inteira + RAG amplo) recua. Sem alvo, intacto.
        hasTarget: Boolean = false,
    ) {
        val provider = com.bettertalker.app.data.llm.ProviderFactory.createFor(remote, app)
        // HTTP fora da Main (§50): NetworkOnMainThreadException virava erro
        // genérico silencioso. Retrieval Room permanece onde está (provado
        // em aparelho); só a rede desce para IO.
        val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            provider.generate(
                com.bettertalker.app.data.llm.LlmRequest(
                    text = if (hasTarget) "" else blockText,
                    action = com.bettertalker.app.data.llm.LlmAction.CHAT,
                    message = text,
                    history = history,
                    isFirstMessage = isFirst,
                    // F2.3 §10: o RAG NUNCA desaparece com foco. O dossiê do
                    // tópico e o ContextPack coexistem no mesmo prompt.
                    contextPack = turnContext.pack,
                    blockTitle = if (hasTarget) null else _activeBlockTitle.value,
                    structural = turnContext.structural,
                    oratory = turnContext.oratory,
                    contextBlock = contextBlock,
                )
            )
        }
        // Quirk do qwen3.8-27b: para no meio sem pontuação (finish_reason
        // "stop" prematuro). Trunca até a última frase completa — nunca
        // entrega texto quebrado. Só chat geral (oratória/proposta têm schema).
        val text = truncateIfCut(res.text, res.meta.finishReason)
        // T1 — gate de alucinação universal (mesmo pós-filtro do Gemma local):
        // confere citações/refs contra o corpus injetado. Não bloqueia: remove
        // só frases claramente sem apoio e anexa o aviso padrão.
        val corpus = verificationCorpus(turnContext)
        val verified = GroundednessVerifier.verify(text, corpus)
        if (verified.hasRemovals) {
            android.util.Log.w(
                "CopilotLLM",
                "chat remoto: verificador removeu ${verified.removed.size} trecho(s) sem apoio"
            )
        }
        // T2 — aviso não bloqueante de citações (padrão "⚠️ Revise").
        val revision = citationRevisionNotice(verified.text, corpus)
        if (revision != null) {
            android.util.Log.w("CopilotLLM", "chat remoto: aviso de citação exibido")
        }
        val finalText = if (revision == null) verified.text else verified.text + "\n\n" + revision
        // T3 — dica contextual: insuficiência + acervo com publicações soltas.
        val tip = com.bettertalker.app.data.copilot.contextualScopeTip(
            responseText = verified.text,
            unlinkedPublications = if (verified.text.contains(
                    com.bettertalker.app.data.copilot.INSUFFICIENT_EVIDENCE_MESSAGE
                )
            ) {
                db.attachmentDao().all().count { it.noteId == null && it.status == "ready" }
            } else {
                0
            },
            scopeEmpty = turnContext.pack.contentSources.isEmpty(),
        )
        val withTip = if (tip == null) finalText else finalText + tip
        post(false, "text", ChatCodec.escMap(mapOf("text" to withTip)), MessageOrigin.COPILOT)
    }

    /**
     * F2.3: histórico apenas do escopo atual (tópico ou global). Um tópico
     * nunca vê as mensagens de outro.
     */
    private suspend fun conversationTurnsScoped(): List<ChatTurn> {
        val nid = noteId ?: return emptyList()
        return db.chatDao().allScoped(nid, _chatScope.value).mapNotNull { e ->
            val t = when (e.kind) {
                "text" -> ChatCodec.unescMap(e.payload)["text"].orEmpty()
                else -> e.kind
            }
            if (t.isBlank()) null else ChatTurn(e.fromMe, t)
        }
    }

    // ---------- Fase 18 BLOCO B: proposta / verificação / aceite ----------

    /**
     * Proposta em exibição: o que o modelo sugeriu, sobre qual foco, com
     * verificação opcional e aviso de resultado. Nunca aplicada sem aceite.
     */
    data class ProposalUi(
        val proposal: CopilotEditProposal,
        val focusText: String,
        /**
         * T2 (Bug #14): quando o foco é o título de uma seção, a proposta é
         * aplicada NA SEÇÃO (contentHtml) — o título nunca existe no richHtml
         * da nota. Null = caminho legado (nota/seleção).
         */
        val sectionId: String? = null,
        /** FNV-1a do bloco da seção na geração (stale real na aceitação). */
        val sectionBaseHash: String? = null,
        val verification: TextVerification? = null,
        val verifying: Boolean = false,
        val notice: String? = null,
        val applied: Boolean = false
    )

    private val _proposal = MutableStateFlow<ProposalUi?>(null)
    val proposal = _proposal.asStateFlow()

    /** Snapshots para Desfazer após aceite (nota ou seção). Volátil, cap 50. */
    private sealed interface ProposalSnapshot {
        data class Note(val html: String, val md: String) : ProposalSnapshot
        data class Section(val entity: SpeechSectionEntity) : ProposalSnapshot
    }

    private val proposalUndo = ArrayDeque<ProposalSnapshot>()

    val canUndoProposal: Boolean get() = proposalUndo.isNotEmpty()

    // ---------- T2 (Anexar do acervo): publicações no chat ----------

    /** Publicações do acervo (todas; o sheet filtra por busca). */
    val publications = db.attachmentDao().observe()

    /** Nota atual (para o badge "Nesta nota" e o relink). */
    val currentNoteId: String? get() = noteId

    /**
     * Vincula/desvincula uma publicação do acervo à nota atual.
     * [targetNoteId] null = desvincular.
     */
    fun linkPublication(attachmentId: String, targetNoteId: String?) {
        viewModelScope.launch {
            try {
                com.bettertalker.app.data.repo.LibraryRepository(app, db)
                    .linkToNote(attachmentId, targetNoteId)
                postText(if (targetNoteId != null) "Vinculado ✓. Já posso citar." else "Desvinculado.")
            } catch (_: Exception) {
                postText("Não consegui atualizar o vínculo agora.")
            }
        }
    }

    /** Retenta o registro de um download falho (sheet do acervo). */
    fun retryRegisterPublication(attachmentId: String) {
        viewModelScope.launch {
            if (!com.bettertalker.app.data.repo.LibraryRepository(app, db)
                    .retryRegister(attachmentId)
            ) {
                postText("Sem download para retentar. Baixe de novo pela Biblioteca.")
            }
        }
    }

    /** Texto em foco agora (seleção > bloco > discurso). Mesma regra do send(). */
    private fun currentFocusText(): String {
        val selection = _selection.value.trim()
        return when {
            selection.isNotEmpty() -> selection
            _activeBlockTitle.value != null -> _activeBlockTitle.value!!
            else -> _noteBody.value.take(2000)
        }
    }

    /**
     * T2 (Bug #14): seção cujo título é o foco atual (null = foco de
     * nota/seleção). Só considera foco vindo de `_activeBlockTitle`.
     */
    private suspend fun sectionForFocus(focus: String): SpeechSectionEntity? {
        val nid = noteId ?: return null
        if (focus.isBlank() || focus != _activeBlockTitle.value) return null
        return db.speechSectionDao().forNote(nid).firstOrNull { it.title == focus }
    }

    /** Brief: mensagem do usuário anterior à resposta (ou a própria resposta). */
    private suspend fun briefFor(messageId: String): String {
        val nid = noteId ?: return ""
        val all = db.chatDao().allScoped(nid, _chatScope.value)
        val idx = all.indexOfFirst { it.id == messageId }
        if (idx <= 0) return ""
        for (i in idx - 1 downTo 0) {
            val e = all[i]
            if (!e.fromMe) continue
            val t = ChatCodec.unescMap(e.payload)["text"].orEmpty()
            if (t.isNotBlank()) return t
        }
        return ""
    }

    /**
     * Cria proposta a partir de uma resposta do chat (§21). Modo DELETE é
     * local e determinístico (sem LLM); demais modos exigem chave BYOD.
     * Nunca insere direto no editor.
     */
    fun createProposal(messageId: String, mode: EditProposalMode = EditProposalMode.IMPROVE) {
        if (noteId == null || _chatBusy.value) return
        viewModelScope.launch {
            _chatBusy.value = true
            try {
                val focus = currentFocusText()
                if (focus.isBlank()) {
                    postText("Selecione um trecho ou abra um bloco para propor uma alteração.")
                    return@launch
                }
                // T2 (Bug #14): foco por título de seção → proposta aplica na
                // seção; guarda o id + hash do bloco para o stale real.
                val section = sectionForFocus(focus)
                val secId = section?.id
                val secHash = section?.let {
                    hashText(sectionProposalBlock(it.title, it.contentHtml))
                }
                val blocks = listOf(EditBlock("focus", focus))
                if (mode == EditProposalMode.DELETE) {
                    when (val r = parseEditProposal("", blocks, "focus", mode)) {
                        is com.bettertalker.app.data.edit.ParseResult.Ok ->
                            _proposal.value = ProposalUi(
                                r.proposal, focus, sectionId = secId, sectionBaseHash = secHash
                            )
                        else -> postText("Não consegui montar a proposta agora. Tente novamente.")
                    }
                    return@launch
                }
                // F20-F1: provider selecionado (Gemini ou Groq/Qwen) + sua chave.
                val remote = ProviderFactory.resolveRemote(settings, app)
                if (!ProviderFactory.useRemoteRoute(remote)) {
                    postText("Configure a chave de IA na tela Modelo IA para gerar propostas.")
                    return@launch
                }
                val brief = briefFor(messageId)
                val history = conversationTurnsScoped()
                val turnContext = buildTurnFor(
                    brief.ifBlank { focus }, inferIntent(brief.ifBlank { focus }, false).trainingCategory,
                    history, history.none { it.fromMe }, focus)
                _chatEvidence.value = turnContext.evidence
                android.util.Log.e("CopilotLLM", "proposal context ok focus=" + focus.length)
                val provider = ProviderFactory.createFor(remote, app)
                // HTTP fora da Main (§50), igual ao answerRemote.
                val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    provider.generate(LlmRequest(
                        text = focus,
                        action = com.bettertalker.app.data.llm.LlmAction.CHAT,
                        message = brief.ifBlank { focus },
                        history = history,
                        isFirstMessage = false,
                        contextPack = turnContext.pack,
                        blockTitle = _activeBlockTitle.value,
                        responseFormat = ResponseFormat.EDIT_PROPOSAL,
                        editMode = mode,
                        brief = brief,
                        // F2b: dossiê do último send() (a proposta nasce do turno).
                        contextBlock = lastDossier?.let { dossierPromptBuilder.buildContextBlock(it) },
                    ))
                }
                android.util.Log.e("CopilotLLM", "proposal generated len=" + res.text.length +
                    " offline=" + res.meta.offline)
                when (val r = parseEditProposal(res.text, blocks, "focus", mode)) {
                    is com.bettertalker.app.data.edit.ParseResult.Ok ->
                        _proposal.value = ProposalUi(
                            r.proposal, focus, sectionId = secId, sectionBaseHash = secHash
                        )
                    is com.bettertalker.app.data.edit.ParseResult.Invalid -> {
                        android.util.Log.e("CopilotLLM", "proposal parse invalid reason=" + r.reason)
                        postText("A resposta do modelo veio em formato inválido. Tente gerar novamente.")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _runState.value = ChatRunState.Cancelled()
                throw e
            } catch (e: Exception) {
                android.util.Log.e("CopilotLLM", "proposal failed " + e.javaClass.simpleName +
                    " code=" + (e as? com.bettertalker.app.data.llm.ProviderError)?.code)
                postText(friendlyChatError(e))
            } finally {
                _chatBusy.value = false
            }
        }
    }

    /** Retrieval para o verificador (Room, escopo por discurso). */
    private suspend fun verifyRetrieve(
        query: String,
        training: Boolean
    ): List<com.bettertalker.app.data.domain.RetrievalCandidate> {
        val cited = refScope()
        val scope = noteScope(cited.keys.toList())
        return if (training) {
            com.bettertalker.app.data.repo.RoomTrainingRepository(
                db.passageDao(), db.attachmentDao())
                .retrieveTraining(query, null, scope, 3).hits
        } else {
            com.bettertalker.app.data.repo.RoomRetrievalRepository(
                db.passageDao(), db.attachmentDao())
                .retrieve(query, scope, 5).hits
        }
    }

    /** Verifica a proposta (F6): claims → evidência → 4 estados. */
    fun verifyProposal() {
        val ui = _proposal.value ?: return
        if (ui.verifying) return
        viewModelScope.launch {
            _proposal.value = ui.copy(verifying = true, notice = null)
            try {
                val after = renderAfterText(ui.proposal, ui.focusText)
                val v = verifyText(after, blockId = null, scopeKey = noteId ?: "",
                    retrieve = ::verifyRetrieve)
                _proposal.value = _proposal.value?.copy(verification = v, verifying = false)
            } catch (_: Exception) {
                _proposal.value = _proposal.value?.copy(verifying = false,
                    notice = "Não foi possível verificar agora (acervo indisponível). Tente novamente.")
            }
        }
    }

    /**
     * Aceita a proposta (§22-23): revalida contra o estado atual (stale
     * bloqueia), aplica atomicamente e guarda snapshot para Desfazer.
     *
     * T2 (Bug #14): proposta nascida com foco em SEÇÃO (título) é aplicada na
     * seção (contentHtml) — o título nunca está no richHtml da nota, então o
     * caminho antigo caía em "Proposta obsoleta" para sempre. O stale continua
     * via FNV-1a (hash do bloco da seção capturado na geração).
     */
    fun acceptProposal() {
        val ui = _proposal.value ?: return
        if (ui.applied) return
        val nid = noteId ?: return
        viewModelScope.launch {
            // ---- T2 (#14): foco em seção → aplica na seção ----
            if (ui.sectionId != null) {
                val section = db.speechSectionDao().get(ui.sectionId)
                if (section == null) {
                    _proposal.value = ui.copy(
                        notice = "Proposta obsoleta: o bloco mudou depois da geração. Gere novamente.")
                    return@launch
                }
                val block = sectionProposalBlock(section.title, section.contentHtml)
                if (ui.sectionBaseHash != null && hashText(block) != ui.sectionBaseHash) {
                    _proposal.value = ui.copy(
                        notice = "Proposta obsoleta: o bloco mudou depois da geração. Gere novamente.")
                    return@launch
                }
                if (validateEditProposal(listOf(EditBlock("focus", ui.focusText)), ui.proposal)
                    !is com.bettertalker.app.data.edit.ValidationResult.Ok
                ) {
                    _proposal.value = ui.copy(notice = "Proposta inválida para o estado atual. Gere novamente.")
                    return@launch
                }
                val newContent = applyProposalToSectionBlock(
                    section.title, section.contentHtml, ui.focusText, ui.proposal
                )
                if (newContent == null) {
                    _proposal.value = ui.copy(
                        notice = "Proposta obsoleta: o bloco mudou depois da geração. Gere novamente.")
                    return@launch
                }
                proposalUndo.addLast(ProposalSnapshot.Section(section))
                if (proposalUndo.size > 50) proposalUndo.removeFirst()
                db.speechSectionDao().upsert(
                    section.copy(contentHtml = newContent, updatedAt = System.currentTimeMillis())
                )
                _proposal.value = ui.copy(notice = "Proposta aplicada.", applied = true)
                return@launch
            }
            // ---- caminho da nota (seleção/corpo) ----
            val note = db.noteDao().get(nid) ?: run {
                _proposal.value = ui.copy(notice = "Proposta inválida para o estado atual. Gere novamente.")
                return@launch
            }
            val curPlain = stripHtmlToText(note.richHtml)
            // Stale: o foco não está mais no texto atual.
            if (!curPlain.contains(ui.focusText)) {
                _proposal.value = ui.copy(
                    notice = "Proposta obsoleta: o bloco mudou depois da geração. Gere novamente.")
                return@launch
            }
            if (validateEditProposal(listOf(EditBlock("focus", ui.focusText)), ui.proposal)
                !is com.bettertalker.app.data.edit.ValidationResult.Ok
            ) {
                _proposal.value = ui.copy(notice = "Proposta inválida para o estado atual. Gere novamente.")
                return@launch
            }
            val applied = applyProposalOps(note.richHtml, note.mdText, ui.focusText, ui.proposal)
            if (applied == null) {
                _proposal.value = ui.copy(
                    notice = "Proposta obsoleta: o bloco mudou depois da geração. Gere novamente.")
                return@launch
            }
            proposalUndo.addLast(ProposalSnapshot.Note(note.richHtml, note.mdText))
            if (proposalUndo.size > 50) proposalUndo.removeFirst()
            notes.save(nid, note.title, applied.second, applied.first,
                note.folderId, note.colorArgb, note.pinned)
            _proposal.value = ui.copy(notice = "Proposta aplicada.", applied = true)
        }
    }

    /** Desfaz o último aceite (snapshot pré-apply — nota ou seção). */
    fun undoProposal() {
        val nid = noteId ?: return
        val snap = proposalUndo.removeLastOrNull() ?: return
        viewModelScope.launch {
            when (snap) {
                is ProposalSnapshot.Section -> {
                    // T2 (#14): restaura o contentHtml da seção.
                    db.speechSectionDao().upsert(snap.entity)
                }
                is ProposalSnapshot.Note -> {
                    val note = db.noteDao().get(nid) ?: return@launch
                    notes.save(nid, note.title, snap.md, snap.html,
                        note.folderId, note.colorArgb, note.pinned)
                }
            }
            _proposal.value = _proposal.value?.copy(notice = "Proposta desfeita.", applied = false)
        }
    }

    /** Rejeita: editor intacto, conversa continua. */
    fun rejectProposal() {
        _proposal.value = null
    }

    fun dismissProposalNotice() {
        _proposal.value = _proposal.value?.copy(notice = null)
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
     * T3 (Mini Discurso) — "insira isso no mini discurso", "coloque no
     * discurso" etc. Insere a última resposta de TEXTO do Copilot no tópico
     * em foco (mesmo pipeline do botão "Inserir no tópico") e confirma.
     * Determinístico: nunca vai ao LLM. Retorna true se consumiu a mensagem.
     */
    private suspend fun resolveInsertCommand(text: String): Boolean {
        if (!com.bettertalker.app.data.copilot.ChatRouter.isInsertIntoSpeechCommand(text)) {
            return false
        }
        val last = lastCopilotText()
        if (last.isBlank()) {
            postText("Não há uma resposta minha recente para inserir. Gere o texto e tente de novo.")
            return true
        }
        insertTextIntoScope(last)
        val topic = currentTopicName()
        postText(
            if (topic != null) "Inserido no tópico “$topic”. ✔"
            else "Inserido na nota. ✔"
        )
        return true
    }

    /** Última resposta de texto do Copilot no escopo atual ("" se não houver). */
    private suspend fun lastCopilotText(): String {
        val nid = noteId ?: return ""
        return db.chatDao().allScoped(nid, _chatScope.value)
            .asReversed()
            .firstOrNull { !it.fromMe && it.kind == "text" }
            ?.let { ChatCodec.unescMap(it.payload)["text"].orEmpty() }
            ?.trim()
            .orEmpty()
    }

    /** Título do tópico em foco (null = conversa sem alvo). */
    private suspend fun currentTopicName(): String? {
        val sid = _pushedContext?.sectionId ?: return null
        return db.speechSectionDao().get(sid)?.title?.takeIf { it.isNotBlank() }
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
            "mostrar as seções, desvincular o esboço, " +
            "mostrar as bases e inserir sugestões na nota. " +
            "Respondo continues: “a 2”, “mais”, “fala mais sobre fé”. " +
            "Com prévia ativa: “vincular”, “tópico 2 chama X”, “10 min no 3”, " +
            "“remover o 2”, “fundir 1 e 2”, “sincronizar esboço”, “desenvolva a introdução”. Exemplos:\n" +
            "• “ideias para a conclusão”\n• “exemplo para a conclusão”\n• “como introduzir?”\n• “o que dizem sobre fé?”\n• “o que diz Gênesis 1:26?”\n• “insere a segunda”\n• “quais refs faltam?”\n• “refs do esboço”\n• “mostre as seções”"

    private suspend fun answerAsk(topic: String) {
        if (needOutline()) {
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Para responder com base nos materiais, toque no + e vincule um esboço.")),
                MessageOrigin.LOCAL)
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
                    (if (missing.isNotBlank()) "\n\n$missing" else "") + staleBodiesHint())),
                MessageOrigin.LOCAL)
            val slots = repo.missingBases()
            if (slots.isNotEmpty()) {
                post(false, "bases", ChatCodec.escMap(mapOf(
                    "text" to "Faltam estas bases:",
                    "slots" to slots.joinToString(","))),
                    MessageOrigin.LOCAL)
            }
            return
        }
        // SÓ conteúdo (citadas): be/th instruem a estrutura, nunca são matéria
        val content = com.bettertalker.app.data.repo.contentHits(hits)
        if (content.isEmpty()) {
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Sobre “$topic”, só tenho as publicações-guia (estrutura, não matéria). " +
                    "Baixe no site a matéria citada no esboço para eu responder com ela.")),
                MessageOrigin.LOCAL)
            val missing = repo.missingBases()
            if (missing.isNotEmpty()) {
                post(false, "bases", ChatCodec.escMap(mapOf(
                    "text" to "Faltam estas bases:",
                    "slots" to missing.joinToString(","))),
                    MessageOrigin.LOCAL)
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
        post(false, "text", ChatCodec.escMap(mapOf("text" to sb.toString().trim())), MessageOrigin.LOCAL)
    }

    /**
     * Refs do corpo da seção na conversa: trechos exatos das capituladas
     * ("Da referência") + mensagem com todas p/ baixar e citar.
     * No máximo 1 bloco exato + 1 mensagem de refs por resposta.
     */
    private suspend fun postSectionRefs(target: OutlineSection) {
        val refs = RefDetector.detect(target.title + "\n" + target.body)
        if (refs.isEmpty()) return
        for (ref in refs) {
            if (RefDetector.chapterOf(ref.raw) == null) continue
            val passages = repo.refPassages(ref, 2)
            if (passages.isEmpty()) continue
            val p = passages.first()
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Da referência (${ref.label}):\n“${p.passage.text.take(220)}” [${p.source}]")),
                MessageOrigin.LOCAL)
            break
        }
        val statuses = repo.checkRefsList(refs)
        if (statuses.isNotEmpty()) {
            post(false, "refs", ChatCodec.escMap(mapOf(
                "title" to "Referências de “${target.title}”",
                "detected" to RefDetector.detectedToJson(statuses.map { it.ref }))),
                MessageOrigin.LOCAL)
        }
    }

    private suspend fun answerIdeas(sectionHint: String?, raw: String) {
        if (needOutline()) {
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Vincule um esboço primeiro — assim gero ideias por seção, na ordem do raciocínio.")),
                MessageOrigin.LOCAL)
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
                    "text" to "Para qual seção? Diga o número ou o nome:\n$opts")),
                    MessageOrigin.LOCAL)
            } else {
                post(false, "text", ChatCodec.escMap(mapOf(
                    "text" to "Não achei a seção. Diga o nome dela como está no esboço.")),
                    MessageOrigin.LOCAL)
            }
            return
        }
        val idx = secs.indexOf(target)
        val neighbors = listOfNotNull(
            secs.getOrNull(idx - 1)?.title, secs.getOrNull(idx + 1)?.title)
        val scope = refScope()
        postSectionRefs(target)
        val cards = repo.ideasForSection(target, neighbors, sectionHint ?: raw, noteId,
            scope.keys.toList(), scope, childTitles(secs, idx))
        if (cards.isEmpty()) {
            val missing = missingMatterText()
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Não achei trechos nos materiais para “${target.title}”." +
                    (if (missing.isNotBlank()) "\n\n$missing" else "") + staleBodiesHint())),
                MessageOrigin.LOCAL)
            return
        }
        lastSection = target.title
        lastExampleKind = null
        lastWasDevelop = false
        pendingAsk = null
        _ideas.value = _ideas.value.filter { it.sectionTitle != target.title } + cards
        post(false, "ideas", ChatCodec.escMap(mapOf(
            "section" to target.title,
            "cards" to ChatCodec.cardsToJson(cards))),
            MessageOrigin.LOCAL)
    }

    private suspend fun answerExample(kindHint: String?, sectionHint: String?, raw: String) {
        if (needOutline()) {
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Vincule um esboço primeiro — assim gero exemplos por seção. Toque no + para importar ou colar.")),
                MessageOrigin.LOCAL)
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
                    "text" to "Exemplo para qual seção? Diga o número ou o nome:\n$opts")),
                    MessageOrigin.LOCAL)
            } else {
                post(false, "text", ChatCodec.escMap(mapOf(
                    "text" to "Não achei a seção. Diga o nome dela como está no esboço.")),
                    MessageOrigin.LOCAL)
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
        postSectionRefs(target)
        val cards = repo.exampleForSection(target, idx == 0, idx == secs.lastIndex, kind,
            neighbors, noteId, scope.keys.toList(), scope, childTitles(secs, idx))
        if (cards.isEmpty()) {
            val missing = missingMatterText()
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Não achei trechos nos materiais para “${target.title}”." +
                    (if (missing.isNotBlank()) "\n\n$missing" else "") + staleBodiesHint())),
                MessageOrigin.LOCAL)
            return
        }
        lastSection = target.title
        lastExampleKind = (kind ?: repo.kindFor(idx == 0, idx == secs.lastIndex, "")).name.lowercase()
        lastWasDevelop = false
        pendingAsk = null
        _ideas.value = _ideas.value.filter { it.sectionTitle != target.title } + cards
        post(false, "ideas", ChatCodec.escMap(mapOf(
            "section" to target.title,
            "cards" to ChatCodec.cardsToJson(cards))),
            MessageOrigin.LOCAL)
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
                "text" to "Vincule um esboço primeiro — assim desenvolvo cada parte. Toque no + para importar ou colar.")),
                MessageOrigin.LOCAL)
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
                    "text" to "Desenvolver qual parte? Diga o número ou o nome:\n$opts")),
                    MessageOrigin.LOCAL)
            } else {
                post(false, "text", ChatCodec.escMap(mapOf(
                    "text" to "Não achei a seção. Diga o nome dela como está no esboço.")),
                    MessageOrigin.LOCAL)
            }
            return
        }
        val idx = secs.indexOf(target)
        val neighbors = listOfNotNull(
            secs.getOrNull(idx - 1)?.title, secs.getOrNull(idx + 1)?.title)
        val kind = exampleKindOf(ChatIntent.exampleKindHint(raw)?.takeIf { it != "any" })
        val scope = refScope()
        postSectionRefs(target)
        val cards = repo.exampleForSection(target, idx == 0, idx == secs.lastIndex, kind,
            neighbors, noteId, scope.keys.toList(), scope, childTitles(secs, idx))
        if (cards.isEmpty()) {
            val missing = missingMatterText()
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Não achei trechos nos materiais para “${target.title}”." +
                    (if (missing.isNotBlank()) "\n\n$missing" else "") + staleBodiesHint())),
                MessageOrigin.LOCAL)
            return
        }
        lastSection = target.title
        lastExampleKind = null
        lastWasDevelop = true
        pendingAsk = null
        _ideas.value = _ideas.value.filter { it.sectionTitle != target.title } + cards
        post(false, "ideas", ChatCodec.escMap(mapOf(
            "section" to target.title,
            "cards" to ChatCodec.cardsToJson(cards))),
            MessageOrigin.LOCAL)
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
     * T4 — composição local. O legado MediaPipe/Qwen foi removido; a geração
     * local agora acontece pelo provider Gemma LiteRT (rota unificada). Sem
     * provedor disponível, responde orientação honesta (determinístico).
     */
    private suspend fun answerCompose() {
        if (needOutline()) {
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Vincule um esboço primeiro. Toque no + para importar ou colar.")))
            return
        }
        post(false, "text", ChatCodec.escMap(mapOf(
            "text" to "Para compor texto, selecione o modelo local (Gemma) na tela Modelo IA " +
                "ou configure uma chave de IA.")))
    }

    /** Últimas trocas resumidas em 1 linha para o prompt RAG. */
    private suspend fun recentHistory(): List<String> {
        return try {
            val nid = noteId ?: return emptyList()
            db.chatDao().allScoped(nid, _chatScope.value).takeLast(8).map { e ->
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
                "text" to "Vincule um esboço primeiro para eu resumir com contexto.")),
                MessageOrigin.LOCAL)
            return
        }
        // resume a NOTA (o chat não tem campo de busca; _query ficaria sempre vazio)
        val scope = refScope()
        val hits = repo.askScoped(_noteText.value.take(500).ifBlank { "discurso" }, noteId,
            extraIds = scope.keys.toList(), extraLabels = scope)
        val s = repo.summary(hits)
        _summary.value = s
        post(false, "text", ChatCodec.escMap(mapOf("text" to s)), MessageOrigin.LOCAL)
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
        try {
            val preview = outlines.previewFile(uri)
            _draftName.value = preview.fileName
            _draftTitle.value = preview.parsed.title
            _draftTotal.value = preview.parsed.totalMinutes
            _draft.value = preview.parsed.sections.map { DraftSection(it.title, it.minutes, true, it.body, it.level) }
            _draftPreamble.value = preview.parsed.preamble
            _previewRefsJson.value = preview.refsJson
            syncDraftMessage()
            // T1 (Bug #8): S-34 importado precisa persistir na estrutura
            // (s34_*), não só no draft legado. Nunca derruba o import.
            persistS34FromImport(uri, preview.fileName)
        } catch (e: ImportException) {
            // T2: falha de import visível (nada de silêncio).
            android.util.Log.i("Import", "falha no import: reason=${e.reason}")
            postText(importFailureMessage(e))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("Import", "falha inesperada no import: ${e.javaClass.simpleName}")
            postText(importFailureMessage(e))
        }
    }

    /**
     * T1 (Bug #8) — roda o [S34ImportHook] no import via chat.
     *
     * O hook exige a identidade de um attachment; criamos/reutilizamos um
     * vinculado à nota (indexed=false: o S-34 é estrutura, não publicação
     * indexada) para que a busca estrutural (`structuralFor`) o encontre.
     * O texto bruto é re-extraído do URI (o preview não o expõe).
     */
    private suspend fun persistS34FromImport(uri: Uri, fileName: String) {
        val nid = noteId
        if (nid == null) {
            android.util.Log.i("S34Import", "chat import: sem nota em foco; S-34 não persistido")
            return
        }
        val kind = try {
            detectKind(fileName)
        } catch (_: Exception) {
            android.util.Log.i("S34Import", "chat import: kind não detectado ($fileName)")
            return
        }
        if (!DocExtractors.supports(kind)) {
            android.util.Log.i("S34Import", "chat import: kind não suportado ($kind)")
            return
        }
        val raw = try {
            val tmp = java.io.File(app.cacheDir, "s34-${System.currentTimeMillis()}-$fileName")
            val copied = app.contentResolver.openInputStream(uri)?.use { ins ->
                tmp.outputStream().use { out -> ins.copyTo(out) }
                true
            } ?: false
            if (!copied) {
                android.util.Log.i("S34Import", "chat import: cópia falhou ($fileName)")
                return
            }
            try {
                DocExtractors.extract(app, tmp, kind)
            } finally {
                tmp.delete()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.i(
                "S34Import",
                "chat import: extração falhou (${e.javaClass.simpleName})"
            )
            return
        }
        if (raw.isBlank() || !S34Detector.isS34(raw, fileName)) {
            // Normal para esboços que não são S-34: segue só com o draft legado.
            android.util.Log.i("S34Import", "chat import: não é S-34 (segue só o draft)")
            return
        }
        val existing = db.attachmentDao().all()
            .firstOrNull { it.noteId == nid && it.fileName == fileName }
        val attId = existing?.id ?: newId("att")
        if (existing == null) {
            db.attachmentDao().upsert(
                AttachmentEntity(
                    id = attId,
                    noteId = nid,
                    fileName = fileName,
                    kind = kind.name.lowercase(),
                    sizeBytes = raw.length.toLong(),
                    appPath = "",
                    indexed = false,
                    addedAt = System.currentTimeMillis(),
                    status = "ready",
                )
            )
        }
        val outcome = S34ImportHook.onExtracted(db.s34Dao(), attId, raw, fileName = fileName)
        android.util.Log.i("S34Import", "chat import outcome=$outcome attachment=$attId")
        if (outcome is S34ImportHook.Outcome.ParseFailed) {
            // T2: detectado como S-34 mas sem estrutura extraível — visível.
            postText(S34_PARSE_FAILURE_MESSAGE)
        }
    }

    fun pasteOutline(text: String) = viewModelScope.launch {
        val (titled, rest) = PastedOutlineAnalyzer.splitTitle(text)
        val (cands, droppedCount) = PastedOutlineAnalyzer.candidates(rest.ifBlank { text })
        if (cands.isEmpty()) {
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
            return@launch
        }
        try {
            outlines.link(
                nid, _draftName.value.ifBlank { "esboço" }, _draftTitle.value,
                _draftTotal.value, sections, _previewRefsJson.value, _draftPreamble.value
            )
        } catch (_: Exception) {
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Não consegui vincular o esboço. Tente de novo.")))
            return@launch
        }

        // 3.2.3c: converte e persiste seções + sub-pontos do esboço.
        // Best-effort: falha aqui é silenciosa e não afeta o fluxo principal
        // (link já concluído). Débito documentado em
        // buildOutlineConversion.
        try {
            val conversion = buildOutlineConversion(
                title = _draftTitle.value,
                totalMinutes = _draftTotal.value,
                sections = sections,
                preamble = _draftPreamble.value,
                noteId = nid,
            )
            // 3.2.5f-pre: validação informativa (não bloqueia); o EditorViewModel
            // revalida ao observar as seções e mostra o aviso quando a nota abrir.
            @Suppress("UNUSED_VARIABLE")
            val validationResult = outlineImportService.persist(
                conversion,
                com.bettertalker.app.domain.speech.DiscourseType.S34_DISCOURSE,
            )
        } catch (_: Exception) {
            // silencioso por design (apenas erros de persistência; validação não lança)
        }

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
            "text" to "Esboço vinculado ✓ (${sections.size} seções)." +
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
        // Onboarding F2a: o estado mudou (esboço vinculado) — revalida o banner.
        viewModelScope.launch { refreshReadiness() }
    }

    /** Título da nota (tema do esboço manda): entregue ao editor via MainActivity. */
    private val _titleEvent = MutableStateFlow<String?>(null)
    val titleEvent = _titleEvent.asStateFlow()
    fun consumeTitle() { _titleEvent.value = null }

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

/**
 * F20-F1 — candidatos S-34 do turno: anexos citados no texto primeiro (mais
 * específicos), depois os vinculados à nota, sem duplicar. Puro/testável.
 */
fun s34CandidateIds(citedIds: List<String>, linkedIds: List<String>): List<String> =
    (citedIds + linkedIds).distinct()

/**
 * T3 (Bug #7) — comandos de referência são determinísticos: com provider
 * ativo continuam postando o card (📖 No acervo / ⚠️ sem fonte) em vez de
 * serem respondidos pelo modelo. Puro/testável.
 */
fun isRefsCommand(intent: ChatIntent.Intent): Boolean =
    intent is ChatIntent.Intent.OutlineRefs || intent is ChatIntent.Intent.CheckRefs

/** T2 (Bug #10) — normaliza o título do bloco ativo (branco → null). Puro/testável. */
fun normalizeActiveBlockTitle(title: String?): String? =
    title?.trim()?.takeIf { it.isNotBlank() }

/**
 * Monta o [OutlineConversion] a partir do draft para persistir via
 * `OutlineImportService`. Pura, testável sem Context/Room.
 *
 * DÉBITO (3.2.3c):
 * - Erros de conversão são silenciados no caller (try/catch que não
 *   propaga); telemetria futura, se conveniente.
 * - Draft sem seções (`size < 2`) não chega ao converter — herdado do fluxo.
 * - Sync de seções/sub-pontos para a nuvem ainda não existe (roadmap; sync
 *   hoje cobre só nota + OutlineEntity).
 */
internal fun buildOutlineConversion(
    title: String,
    totalMinutes: Int?,
    sections: List<OutlineSection>,
    preamble: String,
    noteId: String,
    discourseType: DiscourseType = DiscourseType.S34_DISCOURSE,
    converter: OutlineConverter = OutlineConverter(),
): OutlineConversion {
    val parsed = ParsedOutline(
        title = title,
        totalMinutes = totalMinutes,
        sections = sections,
        preamble = preamble,
    )
    return converter.convert(parsed, noteId, discourseType)
}

/**
 * Decide se a rota Oratory deve degradar para o chat normal.
 *
 * Motivo: esboços colados/importados que não passam pelo
 * `S34ImportHook` nunca populam `s34_outlines` → `_lastS34Document`
 * fica null → o usuário via "Importe o S-34" mesmo com esboço válido
 * vinculado. Todo o resto do produto (dossiê, `buildContextBlock`,
 * greeting) já é agnóstico ao tipo.
 *
 * Degrada **apenas** quando há esboço vinculado (seções convertidas
 * disponíveis). Sem esboço nenhum, mantém a mensagem original.
 *
 * Pura, testável.
 */
internal fun shouldDegradeOratoryToChat(
    hasS34Document: Boolean,
    hasLinkedOutline: Boolean,
): Boolean = !hasS34Document && hasLinkedOutline

/**
 * T2 — mensagem visível de falha de import (pura/testável). Usa o texto do
 * [com.bettertalker.app.data.repo.ImportException] quando ele é humano
 * (ex.: formato não suportado); senão, orientação genérica.
 */
internal fun importFailureMessage(e: Throwable): String =
    (e as? com.bettertalker.app.data.repo.ImportException)
        ?.message?.takeIf { it.isNotBlank() }
        ?: "Não consegui importar o esboço. Tente converter para DOCX, PDF ou RTF."

/** T2 — S-34 detectado, mas sem estrutura extraível (parser não achou seções). */
internal const val S34_PARSE_FAILURE_MESSAGE =
    "Detectei um esboço S-34, mas não consegui extrair as seções. Verifique o formato."

/**
 * Aplica as operações nas strings (html autoritativo, md acompanhando).
 * null = âncora não encontrada (stale) — nunca aplica parcial. Puro/testável.
 *
 * O html é decodificado antes do match: o editor persiste acentos como
 * entidades e a âncora (texto puro) nunca bateria no cru (§23).
 */
internal fun applyProposalOps(
    html: String,
    md: String,
    focus: String,
    proposal: CopilotEditProposal
): Pair<String, String>? {
    var h = com.bettertalker.app.data.edit.unescapeHtmlEntities(html)
    var m = md
    for (op in proposal.operations) {
        when (op) {
            is EditOperation.Replace -> {
                if (!h.contains(focus) || !m.contains(focus)) return null
                h = h.replaceFirst(focus, op.contentHtml)
                m = m.replaceFirst(focus, stripHtmlToText(op.contentHtml))
            }
            is EditOperation.Insert -> {
                val anchor = h.indexOf(focus)
                if (anchor < 0 || !m.contains(focus)) return null
                val at = if (op.position ==
                    com.bettertalker.app.data.edit.InsertPosition.BEFORE) anchor
                else anchor + focus.length
                h = h.substring(0, at) + op.contentHtml + h.substring(at)
                val mAnchor = m.indexOf(focus)
                val mAt = if (op.position ==
                    com.bettertalker.app.data.edit.InsertPosition.BEFORE) mAnchor
                else mAnchor + focus.length
                val mdNew = stripHtmlToText(op.contentHtml)
                m = m.substring(0, mAt) + mdNew + m.substring(mAt)
            }
            is EditOperation.Delete -> {
                if (!h.contains(focus) || !m.contains(focus)) return null
                h = h.replaceFirst(focus, "")
                m = m.replaceFirst(focus, "")
            }
        }
    }
    return h to m
}

/**
 * T2 (Bug #14) — bloco textual da seção para o fluxo de proposta: título
 * (âncora do foco) + conteúdo (alvo real; contentHtml cru preserva o HTML).
 * Puro/testável.
 */
internal fun sectionProposalBlock(title: String, contentHtml: String): String =
    if (contentHtml.isBlank()) title else "$title\n\n$contentHtml"

/**
 * T2 (Bug #14) — aplica a proposta ao bloco da seção e devolve o NOVO
 * conteúdo (sem a linha do título). null = âncora ausente (stale). Puro/testável.
 */
internal fun applyProposalToSectionBlock(
    title: String,
    contentHtml: String,
    focus: String,
    proposal: CopilotEditProposal
): String? {
    val block = sectionProposalBlock(title, contentHtml)
    if (!block.contains(focus)) return null
    val applied = applyProposalOps(block, block, focus, proposal) ?: return null
    return applied.first.removePrefix(title).trimStart('\n', ' ', '\t')
}
