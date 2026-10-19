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
import com.bettertalker.app.data.copilot.clipboardMatches
import com.bettertalker.app.data.copilot.contextLabel
import com.bettertalker.app.data.copilot.friendlyChatError
import com.bettertalker.app.data.copilot.inferIntent
import com.bettertalker.app.data.copilot.MAX_SELECTION_CHARS
import com.bettertalker.app.data.copilot.OutlineStructureContext
import com.bettertalker.app.data.copilot.provenanceSummary
import com.bettertalker.app.data.copilot.validateOutgoingMessage
import com.bettertalker.app.data.db.AppDatabase
import com.bettertalker.app.data.db.AttachmentEntity
import com.bettertalker.app.data.db.ChatEntity
import com.bettertalker.app.data.db.OutlineEntity
import com.bettertalker.app.data.db.RoomTransactionRunner
import com.bettertalker.app.data.db.SpeechSectionEntity
import com.bettertalker.app.data.db.toDomain
import com.bettertalker.app.data.db.toEntity
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
import com.bettertalker.app.data.llm.VerificationCategory
import com.bettertalker.app.data.llm.VerificationResult
import com.bettertalker.app.data.prefs.SettingsStore
import com.bettertalker.app.data.repo.NotesRepository
import com.bettertalker.app.data.verify.TextVerification
import com.bettertalker.app.data.verify.verifyText
import com.bettertalker.app.data.repo.CopilotRepository
import com.bettertalker.app.data.repo.IdeaCard
import com.bettertalker.app.data.repo.ImportException
import com.bettertalker.app.data.repo.MIN_RELEVANT_HITS
import com.bettertalker.app.data.repo.OutlineRepository
import com.bettertalker.app.data.repo.OutlineImportService
import com.bettertalker.app.data.repo.ScopedHit
import com.bettertalker.app.data.repo.childTitles
import com.bettertalker.app.data.s34.S34ImportHook
import com.bettertalker.app.data.s34.normalizeBibleBookName
import com.bettertalker.app.data.util.BASE_PUBS
import com.bettertalker.app.data.util.BasePub
import com.bettertalker.app.data.util.ChatCodec
import com.bettertalker.app.data.util.ChatIntent
import com.bettertalker.app.data.util.DocExtractors
import com.bettertalker.app.data.util.OutlineParser
import com.bettertalker.app.data.util.OutlineSection
import com.bettertalker.app.data.util.ParsedOutline
import com.bettertalker.app.data.util.PasteKind
import com.bettertalker.app.data.util.PastePlan
import com.bettertalker.app.data.util.PasteTextNormalizer
import com.bettertalker.app.data.util.PastedOutlineAnalyzer
import com.bettertalker.app.data.util.PublicationDetector
import com.bettertalker.app.data.util.RefDetector
import com.bettertalker.app.data.util.S34Detector
import com.bettertalker.app.data.util.detectKind
import com.bettertalker.app.data.util.extractSnippet
import com.bettertalker.app.data.util.htmlToPlainText
import com.bettertalker.app.data.util.materialMinutesOf
import com.bettertalker.app.data.util.newId
import com.bettertalker.app.data.util.parseInsertSnippet
import com.bettertalker.app.data.util.planPaste
import com.bettertalker.app.data.util.snippetLabel
import com.bettertalker.app.data.util.stripInternalTags
import com.bettertalker.app.data.util.stripMetaPreamble
import com.bettertalker.app.data.util.stripPromptTags
import com.bettertalker.app.domain.speech.toJson
import com.bettertalker.app.data.util.vmRejectionMessage
import com.bettertalker.app.data.planning.truncateIfCut
import com.bettertalker.app.data.planning.OutlineGeneratorImpl
import com.bettertalker.app.data.planning.OutlineProposerFactory
import com.bettertalker.app.domain.planning.Audience
import com.bettertalker.app.domain.planning.Dossier
import com.bettertalker.app.domain.planning.OutlineAngle
import com.bettertalker.app.domain.planning.OutlineGenerationRequest
import com.bettertalker.app.domain.planning.OutlineProposal
import com.bettertalker.app.domain.planning.OutlineProposalValidator
import com.bettertalker.app.domain.planning.PublicationRef
import com.bettertalker.app.domain.planning.SectionPlan
import com.bettertalker.app.ui.editor.ChatContext
import com.bettertalker.app.ui.editor.DraftTarget
import com.bettertalker.app.ui.editor.roleLabel
import com.bettertalker.app.domain.speech.DiscourseType
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.OutlineConversion
import com.bettertalker.app.domain.speech.OutlineConverter
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SubPoint
import com.bettertalker.app.domain.speech.canonicalPublicationSymbol
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class InsertRequest(
    val text: String,
    val heading: String?,
    /** T5: tópico escolhido no seletor do card (null = alvo em foco/legado). */
    val targetSectionId: String? = null,
    val nonce: Long = System.nanoTime(),
)

/**
 * T1 — corpus efetivamente injetado no prompt do turno (CONTENT + TRAINING +
 * estrutura do S-34: objetivo, conteúdo do ponto focado, subpontos e refs).
 * É contra ele que o pós-filtro de alucinação do chat confere a resposta.
 * [extra] — T3 (acesso bíblico): textos resolvidos FORA do pack (ex.:
 * versículos citados na pergunta). Puro/testável.
 */
fun verificationCorpus(
    turnContext: com.bettertalker.app.data.copilot.ChatTurnContext,
    extra: List<String> = emptyList(),
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
    extra.forEach { add(it) }
}.filter { it.isNotBlank() }

/**
 * T3 (acesso bíblico) — linhas "ref + texto" para o CORPUS do gate: cobre a
 * ref citada e o texto para o pós-filtro de citações. [rawQuestion] adiciona
 * os aliases usados na pergunta ("jer. 29:11"): o modelo repete a forma do
 * usuário e o pós-check compara livro+cap+versos normalizados — sem isso, a
 * forma escrita dispara o aviso falso "versículo fora dos trechos" mesmo com
 * o versículo injetado. Puro/testável.
 */
fun questionVerseLines(verses: List<ScopedHit>, rawQuestion: String = ""): List<String> {
    val lines = verses.map { "[${it.passage.ref}] ${it.passage.text}" }.toMutableList()
    val aliases = RefDetector.detectBible(rawQuestion)
        .map { "${it.bookNorm} ${it.chapter} ${it.verse}" }
        .distinct()
    if (aliases.isNotEmpty()) lines += aliases.joinToString(" ")
    return lines
}

/**
 * T3 (acesso bíblico) — bloco "## TEXTOS BÍBLICOS (referências da pergunta)"
 * com o texto literal dos versículos citados NA MENSAGEM. Vazio = não injeta
 * (P4: nunca varrer a Bíblia sem citação). Puro/testável.
 */
fun questionBibleBlock(verses: List<ScopedHit>): String {
    if (verses.isEmpty()) return ""
    val lines = verses.map { v ->
        val ref = v.passage.ref.ifBlank { "?" }
        "- $ref: \"${v.passage.text.take(MAX_QUESTION_VERSE_CHARS)}\""
    }
    return "## TEXTOS BÍBLICOS (referências da pergunta)\n" + lines.joinToString("\n")
}

/** Teto por versículo no bloco da pergunta (o prompt trunca de novo). */
const val MAX_QUESTION_VERSE_CHARS = 600

/**
 * T1 (expert em publicações) — bloco "## TRECHOS DE PUBLICAÇÕES (referências
 * da pergunta)" com o texto literal dos trechos citados NA MENSAGEM. Espelho
 * do bloco bíblico. Vazio = não injeta. Puro/testável.
 */
fun questionPublicationBlock(pubs: List<ScopedHit>): String {
    if (pubs.isEmpty()) return ""
    val lines = pubs.map { p ->
        val ref = p.passage.ref.ifBlank { "?" }
        "- $ref: \"${p.passage.text.take(MAX_QUESTION_PUB_CHARS)}\""
    }
    return "## TRECHOS DE PUBLICAÇÕES (referências da pergunta)\n" + lines.joinToString("\n")
}

/** Teto por trecho de publicação no bloco da pergunta (o prompt trunca de novo). */
const val MAX_QUESTION_PUB_CHARS = 600

/**
 * T3 — dossiê (contexto do tópico) + versículos da pergunta; null quando não
 * há nada a injetar. T1 (publicações): [pubs] entra depois da Bíblia.
 * Puro/testável.
 */
fun combineContextBlocks(
    dossierBlock: String?,
    verses: List<ScopedHit>,
    pubs: List<ScopedHit> = emptyList(),
): String? {
    val bible = questionBibleBlock(verses).takeIf { it.isNotBlank() }
    val pubBlock = questionPublicationBlock(pubs).takeIf { it.isNotBlank() }
    return listOfNotNull(dossierBlock?.takeIf { it.isNotBlank() }, bible, pubBlock)
        .joinToString("\n\n")
        .ifBlank { null }
}

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

private fun categoryLabel(category: VerificationCategory, count: Int): String = when (category) {
    VerificationCategory.QUOTE -> if (count == 1) "1 citação" else "$count citações"
    VerificationCategory.REF -> if (count == 1) "1 referência" else "$count referências"
    VerificationCategory.VERSE -> if (count == 1) "1 versículo" else "$count versículos"
    VerificationCategory.NUMBER -> if (count == 1) "1 número" else "$count números"
}

/**
 * Aviso ÚNICO do chat (warn-only): o texto é mantido; a linha resume o que
 * foi sinalizado e o que a checagem de citações viu, sem repetir o aviso
 * genérico de remoção. Puro/testável.
 */
fun chatVerificationNotice(
    text: String,
    corpus: List<String>,
    result: VerificationResult,
): String? {
    val flags = result.metrics.flaggedByCategory
    val check = com.bettertalker.app.data.ai.checkCitations(text, corpus)
    val reasons = buildList {
        if (flags.isNotEmpty()) {
            val total = result.metrics.total
            val head = if (total == 1) "1 trecho sinalizado" else "$total trechos sinalizados"
            val parts = flags.entries
                .sortedBy { it.key.name }
                .joinToString(", ") { categoryLabel(it.key, it.value) }
            add("$head: $parts")
        }
        if (!check.ok) {
            add("há citação(ões)/versículo(s) fora dos trechos")
        }
    }
    if (reasons.isEmpty()) return null
    return "⚠️ Revise: " + reasons.joinToString("; ") + ". O texto foi mantido."
}

/** Resultado da política do chat: texto intacto + aviso único (se houver). */
data class ChatVerificationOutcome(
    val result: VerificationResult,
    val notice: String?,
) {
    val text: String get() = result.text
}

/**
 * Política do chat remoto: warn-only, texto sempre intacto e um único aviso.
 * Pura/testável — o ViewModel só publica o resultado.
 */
fun verifyChatText(text: String, corpus: List<String>): ChatVerificationOutcome {
    val result = GroundednessVerifier.verify(text, corpus, warnOnly = true)
    return ChatVerificationOutcome(result, chatVerificationNotice(text, corpus, result))
}

/**
 * #11-fix: orçamento de saída do chat — maior quando a pergunta cita refs do
 * acervo (o modelo vai citar 600 chars + comentar; 1000 é apertado). Puro.
 */
internal fun answerOutputBudget(hasResolvedRefs: Boolean): Int =
    if (hasResolvedRefs) {
        maxOf(com.bettertalker.app.data.llm.DEFAULT_LLM_MAX_OUTPUT_TOKENS, 2048)
    } else {
        com.bettertalker.app.data.llm.DEFAULT_LLM_MAX_OUTPUT_TOKENS
    }

/**
 * T2 (chat minimalista): banner de setup só quando o setup está
 * incompleto (sem vínculos), não dispensado e antes do primeiro uso.
 * Após o 1º envio, nunca mais. Puro/testável.
 */
fun shouldShowSetupBanner(
    isSetupComplete: Boolean,
    dismissed: Boolean,
    seen: Boolean,
): Boolean = !isSetupComplete && !dismissed && !seen

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

/**
 * T4 (Mini Discurso): texto pronto para inserir no editor — remove as tags
 * `〈sugestão〉…〈/sugestão〉` (marcador interno do verificador, nunca conteúdo)
 * e apara. Puro/testável.
 */
internal fun cleanForInsert(raw: String): String {
    // #8c: limpeza DEFAULT em toda inserção — tags internas, preâmbulo/
    // pós-escrito "meta" e linhas em branco duplicadas.
    val stripped = stripMetaPreamble(stripPromptTags(raw))
    return stripped.replace(Regex("\\n{3,}"), "\n\n").trim()
}

/**
 * #17-fix: modo de pensamento do chip (per-session, default Rápido).
 * FAST = "low" · DEEP = "high" — rotas estruturadas ignoram (ver
 * [thinkingEffortFor] e DeepSeekProvider.reasoningEffortFor).
 */
enum class ThinkingMode {
    FAST, DEEP;

    /** #17-fix: alternância pura (testável). */
    fun toggled(): ThinkingMode = if (this == FAST) DEEP else FAST

    companion object {
        val DEFAULT = FAST
    }
}

/** #17-fix: esforço de reasoning do chip para as rotas criativas. Puro. */
fun thinkingEffortFor(mode: ThinkingMode): String =
    if (mode == ThinkingMode.DEEP) com.bettertalker.app.data.llm.DeepSeekProvider.REASONING_EFFORT_DEEP
    else com.bettertalker.app.data.llm.DeepSeekProvider.REASONING_EFFORT

/**
 * #8c (GAP-4): dica discreta de ação quando a resposta fecha decisões —
 * usa o comando de inserção que já existe ("insira isso").
 */
internal const val INSERT_INSTRUCTION =
    "_Se estiver de acordo, diga \"insira isso\" para eu colocar no card._"

/**
 * #8c (GAP-4): anexa a instrução discreta quando a resposta tem decisões
 * (〈decisão〉 ou pista explícita). Idempotente — nunca duplica. Puro/testável.
 */
internal fun appendInsertInstruction(finalText: String, responseText: String): String {
    if (com.bettertalker.app.data.copilot.extractDecisions(responseText).isEmpty()) return finalText
    if (finalText.contains(INSERT_INSTRUCTION)) return finalText
    return finalText.trimEnd() + "\n\n" + INSERT_INSTRUCTION
}

/**
 * #15a: aviso suave quando a resposta é gerada SEM S-34 vinculado — o gate
 * duro ("Importe o S-34") foi removido; isto só informa. Nunca bloqueia.
 */
internal const val NO_S34_WARNING =
    "_⚠️ Sem esboço S-34 vinculado — o texto pode não seguir a estrutura original._"

/** #15a: anexa o aviso suave ao fim da resposta. Idempotente — nunca duplica. Puro/testável. */
internal fun appendNoS34Warning(finalText: String): String {
    if (finalText.contains(NO_S34_WARNING)) return finalText
    val base = finalText.trimEnd()
    return if (base.isEmpty()) NO_S34_WARNING else base + "\n\n" + NO_S34_WARNING
}

/** T4: confirmação de inserção no tópico em foco (ou na nota, sem alvo). */
internal fun insertConfirmation(topic: String?): String =
    if (topic != null) "Inserido no tópico “$topic”." else "Inserido na nota."

/**
 * #24-fix — remove `</texto-do-card>` órfão (sem abridor) do texto postado
 * no chat. Com abridor, o parser do #21 converte o bloco — intacto; sem,
 * o fechamento é ruído (render/TTS). Linhas vazias duplicadas que sobrarem
 * são colapsadas. Puro/testável.
 */
internal fun stripOrphanCardClose(raw: String): String =
    if (raw.contains(com.bettertalker.app.ui.components.TEXT_FOR_CARD_OPEN)) {
        raw
    } else {
        raw.replace(com.bettertalker.app.ui.components.TEXT_FOR_CARD_CLOSE, "")
            .replace(Regex("\\n{3,}"), "\n\n")
    }

/**
 * #15d — query do RAG enriquecida: título da seção em foco + título da
 * nota + títulos das seções do S-34 (resumo, quando houver) + prosa do
 * bloco + pergunta. Sem contexto, mantém o comportamento antigo
 * (`blockText + " " + question`). Teto 2500 — o fallback adaptativo
 * cobre o caso em que a query enriquecida ainda é fraca. Pura/testável.
 */
internal fun enrichedRetrievalQuery(
    blockText: String,
    question: String,
    sectionTitle: String? = null,
    noteTitle: String? = null,
    s34SectionTitles: List<String> = emptyList(),
    max: Int = 2500,
): String {
    val context = buildString {
        if (!sectionTitle.isNullOrBlank()) append(sectionTitle.trim())
        if (!noteTitle.isNullOrBlank()) {
            if (isNotEmpty()) append(" · ")
            append(noteTitle.trim())
        }
        s34SectionTitles.forEach { t ->
            if (t.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(t.trim())
            }
        }
    }
    val base = if (context.isEmpty()) "$blockText $question" else "$context $blockText $question"
    return base.take(max)
}

/**
 * #26-fix-c — histórico do reenvio (Regenerar/Retry): remove da continuidade
 * a pergunta atual (último turno do usuário), que vira o "texto atual" do
 * prompt. Puro/testável.
 */
internal fun historyForResend(turns: List<ChatTurn>): List<ChatTurn> =
    if (turns.lastOrNull()?.fromMe == true) turns.dropLast(1) else turns

/** #ui-polish: instrução do "Continuar gerando" (append na mesma bolha). */
const val CONTINUE_INSTRUCTION =
    "Continue exatamente de onde a resposta anterior parou. Não repita nada do que já foi dito, " +
        "não recomece e mantenha o mesmo tom, formato e nível de detalhe."

/**
 * #ui-polish: texto final do "Continuar" — remove o marcador de corte
 * (" [...]") do trecho antigo e anexa a continuação como parágrafo novo.
 * Puro/testável.
 */
internal fun mergeContinuation(existing: String, continuation: String): String {
    val base = existing.removeSuffix(" [...]").trimEnd()
    val extra = continuation.trim()
    return when {
        base.isEmpty() -> extra
        extra.isEmpty() -> existing
        else -> base + "\n\n" + extra
    }
}

/**
 * T5 (Mini Discurso) — destino de uma ideia no editor: tópico (`sectionId`)
 * ou âncora de heading (notas legadas, sem seções). Puro.
 */
data class InsertDestination(
    val label: String,
    val sectionId: String? = null,
    val heading: String? = null,
)

/**
 * T5 — destinos de inserção a partir das seções: só tópicos (BODY), na
 * ordem da nota, com a duração planejada. Puro/testável.
 */
internal fun topicDestinations(sections: List<SpeechSectionEntity>): List<InsertDestination> =
    sections.filter { it.role == SectionRole.BODY.name }
        .sortedBy { it.order }
        .map { InsertDestination(label = "${it.title} · ${it.minutes} min", sectionId = it.id) }

/** T5 — tópicos primeiro; sem tópicos, cai nos headings legados (notas antigas). */
internal fun insertionDestinations(
    topics: List<InsertDestination>,
    legacyHeadings: List<String>,
): List<InsertDestination> =
    if (topics.isNotEmpty()) topics
    else legacyHeadings.map { InsertDestination(label = it, heading = it) }

/** Tópico do draft em edição (mesmo formato persistido nas mensagens). */
typealias DraftSection = ChatCodec.DraftItem

class CopilotViewModel(
    ctx: android.content.Context,
    private val db: AppDatabase,
    private val noteId: String? = null,
    private val voiceEngine: com.bettertalker.app.data.voice.VoiceEngine? = null,
) : ViewModel() {
    private val app = ctx.applicationContext
    private val repo = CopilotRepository(db)
    private val outlines = OutlineRepository(app, db)
    // 3.2.3c: persistência do esboço convertido (seções + sub-pontos).
    private val outlineImportService = OutlineImportService(
        transactionRunner = RoomTransactionRunner(db),
        sectionDao = db.speechSectionDao(),
        subPointDao = db.subPointDao(),
        tombstoneDao = db.tombstoneDao(),
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
                // espera a primeira leitura do banco para limpar prévias
                // de draft órfãs (processo morreu no meio da edição).
                // T1 (chat minimalista): sem balão de boas-vindas — o chat
                // abre vazio, direto na conversa.
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

    fun insert(card: IdeaCard, editedBody: String, dest: InsertDestination?) {
        val body = cleanForInsert(editedBody.ifBlank { card.body })
        insertedTitles += com.bettertalker.app.data.util.normalizeText(card.title)
        pendingInserts += InsertRequest(
            "## ${card.title}\n\n$body\n\n> ${card.snippet}" +
                (if (card.source.isNotEmpty()) "\n> Fonte: ${card.source}" else ""),
            dest?.heading ?: card.sectionTitle.ifEmpty { null },
            targetSectionId = dest?.sectionId,
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
        // T4: as tags 〈sugestão〉 são marcador interno — nunca vão para o editor.
        val clean = cleanForInsert(text)
        if (clean.isBlank()) return
        pendingInserts += InsertRequest(clean, heading)
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
        /** #17-fix: tempo de resposta do assistente (ms), quando informado. */
        val durationMs: Long? = null,
        /** #26-fix-c: timestamp de criação (alvo do "Editar pergunta"). */
        val createdAt: Long = 0,
        /** #ui-polish: resposta cortada por limite de tokens ("Continuar"). */
        val isTruncated: Boolean = false,
    )

    private val _chatBusy = MutableStateFlow(false)
    val chatBusy = _chatBusy.asStateFlow()

    // ---------- #17-fix: chip Rápido/Profundo (per-session) ----------

    private val _thinkingMode = MutableStateFlow(ThinkingMode.DEFAULT)
    val thinkingMode: StateFlow<ThinkingMode> = _thinkingMode.asStateFlow()

    fun toggleThinkingMode() {
        _thinkingMode.value = _thinkingMode.value.toggled()
    }
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
    /** Aviso de retry (orçamento/rede) — transitório, nunca persistido. */
    val localRetry: kotlinx.coroutines.flow.StateFlow<String?> =
        com.bettertalker.app.data.llm.LocalProgress.retryNotice
    /** Reasoning da tentativa atual — transitório, só UI durante o streaming. */
    val localReasoning: kotlinx.coroutines.flow.StateFlow<String> =
        com.bettertalker.app.data.llm.LocalProgress.reasoningText

    /** Mensagem que originou o turno atual, para "Tentar novamente". */
    private var lastUserMessage: String? = null

    /**
     * T1 (modo colar e conversar) — esboço colado íntegro, só na memória
     * (nunca persiste: sem draft, sem outline, sem attachment). Null = modo
     * padrão. Limpo em [clearChat]; novo colar substitui.
     */
    private var pasteBuffer: String? = null
    private val _pasteMode = MutableStateFlow(false)
    val pasteMode = _pasteMode.asStateFlow()

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
     * T2 (chat minimalista): banner de setup só antes do primeiro uso.
     * Combina prontidão + dispensa volátil + flag persistida. Puro o
     * predicado ([shouldShowSetupBanner]), testável sem DataStore.
     */
    val showSetupBanner: StateFlow<Boolean> = combine(
        _readiness, _setupBannerDismissed, settings.chatSetupSeen
    ) { readiness, dismissed, seen ->
        shouldShowSetupBanner(
            isSetupComplete = readiness?.isSetupComplete ?: true,
            dismissed = dismissed,
            seen = seen,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

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
     * #10c: último tópico focado — alvo do retorno quando o escopo volta de
     * "Discurso inteiro" para "Tópico".
     */
    private var _lastFocusedSectionId: String? = null

    /** #10c: escopo do chat — Tópico (default) ou Discurso inteiro. */
    private val _scopeMode = MutableStateFlow(ScopeMode.SECTION)
    val scopeMode: StateFlow<ScopeMode> = _scopeMode.asStateFlow()

    /**
     * F2.3: escopo da conversa atual. `null` = conversa global da nota;
     * caso contrário, o id da seção/tópico em foco. Mensagens de um tópico
     * não vazam para o histórico de outro.
     */
    private val _chatScope = MutableStateFlow<String?>(null)
    val chatScope: StateFlow<String?> = _chatScope.asStateFlow()

    /** #23-fix-c (G1): escopo capturado no INÍCIO do turno — a resposta cai
     *  na seção certa mesmo se o usuário swipar durante a geração. Limpo no
     *  fim do turno (posts fora dele voltam ao escopo vivo). */
    private var turnScope: String? = null

    /** #4 (UX S): job da geração atual — o botão "Parar" cancela por aqui. */
    private var currentGenerationJob: Job? = null

    /**
     * F2.3: alvo atual do chat, para rotear inserções para o tópico certo.
     * null = chat global (mantém o comportamento legado de inserção).
     */
    fun currentTarget(): DraftTarget? {
        // T5: destino explícito do seletor do card (tópico escolhido).
        _insert.value?.targetSectionId?.let { return DraftTarget.Section(it) }
        val ctx = _pushedContext ?: return null
        val sectionId = ctx.sectionId ?: return null
        return if (ctx.subPointId != null) DraftTarget.SubPoint(sectionId, ctx.subPointId)
        else DraftTarget.Section(sectionId)
    }

    fun setPushedContext(ctx: ChatContext?) {
        _pushedContext = ctx
        // F2.3: a conversa segue o foco (tópico vs. global).
        _chatScope.value = ctx?.sectionId
        // #10c: entrada com tópico redefine o escopo para SECTION e memoriza
        // o último foco (alvo do toggle de volta).
        if (ctx?.sectionId != null) {
            _lastFocusedSectionId = ctx.sectionId
            _scopeMode.value = ScopeMode.SECTION
        }
        _readiness.value = ctx?.readiness
        // Nova entrada no chat → banner volta (se ainda incompleto).
        _setupBannerDismissed.value = false
        // Header "Conversando sobre": resolução assíncrona (1-2 queries
        // Room); reset imediato evita rótulo stale da entrada anterior.
        _conversationLabel.value = null
        _topicName.value = null
        // T5: destinos do seletor (tópicos da nota) — relê a cada entrada.
        viewModelScope.launch { refreshInsertDestinations() }
        if (ctx != null) {
            viewModelScope.launch {
                _conversationLabel.value = resolveConversationLabel(ctx)
                // T4: nome do tópico para a confirmação da inserção.
                _topicName.value = currentTopicName()
            }
        }
    }

    /** Rótulo do alvo empurrado ("Conversando sobre: …"). Null = sem header. */
    private val _conversationLabel = MutableStateFlow<String?>(null)
    val conversationLabel: StateFlow<String?> = _conversationLabel.asStateFlow()

    /**
     * #10c — alterna o escopo do chat: Tópico ↔ Discurso inteiro.
     *
     * - SECTION → WHOLE: some o foco (histórico global da nota; GuideBar some).
     * - WHOLE → SECTION: volta ao último tópico focado (ou à 1ª seção da nota);
     *   sem seções, permanece em WHOLE.
     */
    fun toggleScope() = viewModelScope.launch {
        if (_scopeMode.value == ScopeMode.SECTION) {
            _scopeMode.value = ScopeMode.WHOLE
            _chatScope.value = null
            _pushedContext = null
            return@launch
        }
        val target = scopeRestoreTarget(
            lastFocused = _lastFocusedSectionId,
            sectionIds = _guideSections.value.map { it.id },
        ) ?: return@launch
        setPushedContext(
            ChatContext(
                sectionId = target,
                subPointId = null,
                readiness = _readiness.value ?: com.bettertalker.app.ui.editor.NoteReadiness(
                    hasSections = _guideSections.value.isNotEmpty(),
                    hasOutline = false,
                    hasMissingRefs = false,
                    hasMissingBases = false,
                    hasChatHistory = true,
                ),
            )
        )
    }

    // ---------- Modo guiado seção-a-seção: foco real ----------

    /**
     * Seções reais do editor (espelho via MainActivity — mesmo padrão do
     * `setSelection`). Base da navegação, do indicador e do gate; o dossiê
     * continua vindo do `dossierProvider` (fonte do conteúdo).
     */
    private val _guideSections = MutableStateFlow<List<SpeechSection>>(emptyList())
    val guideSections: StateFlow<List<SpeechSection>> = _guideSections.asStateFlow()

    fun setGuideSections(sections: List<SpeechSection>) {
        _guideSections.value = sections
    }

    /** Pedidos de foco do chat para o editor (rolagem). MainActivity repassa. */
    private val _focusRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val focusRequests: SharedFlow<String> = _focusRequests.asSharedFlow()

    /**
     * Troca o escopo para a seção: conversa, dossiê (via `_pushedContext`,
     * lido no próximo `send()`) e rótulo acompanham. Anuncia na conversa
     * nova. Emite o pedido de rolagem ao editor.
     */
    private suspend fun focusSection(sectionId: String, announce: Boolean = true) {
        val sections = _guideSections.value
        val idx = sections.indexOfFirst { it.id == sectionId }
        if (idx < 0) return
        val sec = sections[idx]
        _chatScope.value = sectionId
        val nid = noteId
        val readiness = _pushedContext?.readiness
            ?: nid?.let {
                repo.readNoteReadiness(
                    it,
                    sections.isNotEmpty(),
                    outlines.get(it)?.refsJson.orEmpty(),
                )
            }
            ?: return
        val ctx = ChatContext(sectionId = sectionId, subPointId = null, readiness = readiness)
        _pushedContext = ctx
        _conversationLabel.value = null
        _topicName.value = null
        _conversationLabel.value = resolveConversationLabel(ctx)
        _topicName.value = currentTopicName()
        if (announce) {
            post(
                false,
                "text",
                ChatCodec.escMap(
                    mapOf("text" to guideAnnounceLabel(idx, sections.size, sec.title, sec.minutes))
                ),
                // #23-fix-c: o anúncio pertence à conversa NOVA (escopo vivo),
                // nunca ao escopo do turno em andamento.
                targetScope = _chatScope.value,
            )
        }
        _focusRequests.emit(sectionId)
    }

    /**
     * Executa a decisão de navegação (comandos de texto e botões da barra
     * usam este mesmo caminho). Retorna true = consumiu.
     */
    private suspend fun navigateSection(direction: GuideNavDirection): Boolean {
        val sections = _guideSections.value
        val target = resolveNavTarget(
            orderedIds = sections.map { it.id },
            currentId = _chatScope.value,
            direction = direction,
        )
        when (target) {
            is GuideNavTarget.MoveTo -> focusSection(target.sectionId)
            GuideNavTarget.NoNext -> postText(GUIDE_NO_NEXT_MESSAGE)
            GuideNavTarget.NoPrev -> postText(GUIDE_NO_PREV_MESSAGE)
            GuideNavTarget.NoSections -> postText(GUIDE_NO_FOCUS_MESSAGE)
        }
        return true
    }

    /** #25-fix-c: avança sem gate de aprovação (approvedAt virou legado). */
    fun nextSection() = viewModelScope.launch {
        navigateSection(GuideNavDirection.NEXT)
    }

    /** Volta uma seção. */
    fun previousSection() = viewModelScope.launch {
        navigateSection(GuideNavDirection.PREV)
    }

    /**
     * Aprova a seção em foco (mesmo efeito do toggle do editor: mesma
     * coluna, via DAO — o editor observa o banco e reflete sozinho).
     */
    fun approveCurrentSection() = viewModelScope.launch {
        approveCurrentSectionNow()
    }

    private suspend fun approveCurrentSectionNow(): Boolean {
        val sid = _chatScope.value
        if (sid == null) {
            postText(GUIDE_NO_FOCUS_MESSAGE)
            return true
        }
        val sec = db.speechSectionDao().get(sid) ?: return true
        if (sec.approvedAt == null) {
            db.speechSectionDao().upsert(
                sec.copy(approvedAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
            )
            postText("Tópico aprovado ✓ — diga “próximo” para avançar.")
        } else {
            postText("Este tópico já está aprovado — diga “próximo” para avançar.")
        }
        return true
    }

    /**
     * Comandos do modo guiado seção-a-seção (qualquer provider — roda antes
     * do split remoto/local). Com o guia legado ativo, devolve false para
     * (o guia legado foi removido no cleanup; só a barra/atalhos contam).
     *
     * Sem seção em foco, só "aprovar" é interceptado (comando inequívoco);
     * navegação fica restrita à conversa de um tópico — evita falso
     * positivo em mensagens naturais ("qual o próximo passo?").
     */
    private suspend fun resolveSectionNav(text: String): Boolean {
        if (_chatScope.value == null) {
            // #10c: em Discurso inteiro, navegação pede o escopo de tópico.
            if (_scopeMode.value == ScopeMode.WHOLE &&
                (isNextCommand(text) || isPrevCommand(text))
            ) {
                postText(SCOPE_WHOLE_NAV_NOTICE)
                return true
            }
            if (isApproveCommand(text)) return approveCurrentSectionNow()
            return false
        }
        if (isNextCommand(text)) return navigateSection(GuideNavDirection.NEXT)
        if (isPrevCommand(text)) return navigateSection(GuideNavDirection.PREV)
        if (isApproveCommand(text)) return approveCurrentSectionNow()
        return false
    }

    /** T4: nome do tópico em foco (confirmação da inserção no chat). */
    private val _topicName = MutableStateFlow<String?>(null)
    val topicName: StateFlow<String?> = _topicName.asStateFlow()

    /** T5: destinos do seletor de inserção (tópicos da nota). */
    private val _insertDestinations = MutableStateFlow<List<InsertDestination>>(emptyList())
    val insertDestinations: StateFlow<List<InsertDestination>> = _insertDestinations.asStateFlow()

    private suspend fun refreshInsertDestinations() {
        val nid = noteId ?: return
        _insertDestinations.value = topicDestinations(db.speechSectionDao().forNote(nid))
    }

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

    /**
     * #26-fix-c (D2): reenvia o turno SEM duplicar a pergunta — desativa a
     * resposta anterior (se houver) e refaz a geração com a última pergunta
     * ativa. Mesmo caminho do "Regenerar resposta".
     */
    fun retry() {
        dismissError()
        regenerateLastResponse()
    }

    /**
     * #26-fix-c (DeepSeek Web): regenera a última resposta — a resposta
     * ativa mais recente é DESATIVADA (soft delete) e o turno é refeito com
     * a última pergunta ativa, sem reenviá-la ao banco.
     */
    fun regenerateLastResponse() = viewModelScope.launch {
        val nid = noteId ?: return@launch
        if (_chatBusy.value) return@launch
        val active = db.chatDao().allScoped(nid, _chatScope.value)
        val lastUser = active.lastOrNull { it.fromMe } ?: return@launch
        active.lastOrNull { !it.fromMe }?.let { db.chatDao().deactivateMessage(it.id) }
        val text = ChatCodec.unescMap(lastUser.payload)["text"].orEmpty()
        if (text.isBlank()) return@launch
        lastUserMessage = text
        send(
            text,
            postUserMessage = false,
            historyOverride = historyForResend(conversationTurnsScoped()),
        )
    }

    /**
     * #26-fix-c (DeepSeek Web): edita a pergunta e refaz o turno — a pergunta
     * e TUDO que veio depois no MESMO escopo são desativados (soft delete) e
     * uma nova mensagem com [newText] entra no lugar, disparando a geração.
     * O histórico anterior à pergunta editada fica intacto.
     */
    fun editUserMessage(messageId: String, createdAt: Long, newText: String) = viewModelScope.launch {
        val nid = noteId ?: return@launch
        if (_chatBusy.value) return@launch
        // Guarda: só edita pergunta ATIVA do usuário no escopo atual.
        val ok = db.chatDao().allScoped(nid, _chatScope.value).any {
            it.id == messageId && it.fromMe && it.createdAt == createdAt
        }
        if (!ok) return@launch
        val text = validateOutgoingMessage(newText) ?: return@launch
        db.chatDao().deactivateMessagesFrom(nid, _chatScope.value, createdAt)
        lastUserMessage = text
        send(text, postUserMessage = true, historyOverride = null)
    }

    /**
     * #ui-polish (Continuar gerando): estende a MESMA resposta truncada —
     * o trecho novo é anexado ao payload da mensagem (sem criar bolha nova)
     * e o marcador "[...]" é removido. Usa o histórico ativo como contexto
     * e a mesma instrução de continuação (pura/testável).
     */
    fun continueGeneration(messageId: String) {
        val nid = noteId ?: return
        if (_chatBusy.value) return
        // #23-fix-c (G1): mesmo contrato do send (escopo + job/stop).
        turnScope = _chatScope.value
        currentGenerationJob = viewModelScope.launch {
            _chatBusy.value = true
            // #streaming-fix: o parcial da resposta anterior não vaza.
            com.bettertalker.app.data.llm.LocalProgress.reset()
            _runState.value = ChatRunState.Sending
            try {
                val target = db.chatDao().allScoped(nid, _chatScope.value)
                    .firstOrNull { it.id == messageId && !it.fromMe && it.kind == "text" }
                    ?: return@launch
                val remote = ProviderFactory.resolveRemote(settings, app)
                if (!ProviderFactory.useRemoteRoute(remote)) {
                    postText("Configure a chave de IA na tela Modelo IA para gerar esta parte.")
                    return@launch
                }
                val provider = ProviderFactory.createFor(
                    remote, app, thinkingEffortFor(_thinkingMode.value)
                )
                val history = conversationTurnsScoped()
                // #streaming-fix: entra em Generating ANTES do provider — antes
                // o parcial era emitido com runState ainda Sending e não
                // aparecia na tela (a resposta só "surgia" no fim).
                _runState.value = ChatRunState.Generating
                val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    provider.generate(
                        com.bettertalker.app.data.llm.LlmRequest(
                            text = "",
                            action = com.bettertalker.app.data.llm.LlmAction.CHAT,
                            message = CONTINUE_INSTRUCTION,
                            history = history,
                            isFirstMessage = false,
                        )
                    )
                }
                val cont = truncateIfCut(res.text, res.meta.finishReason)
                if (cont.isBlank()) {
                    postText("Não consegui continuar a resposta agora. Tente novamente.")
                    return@launch
                }
                val oldMap = ChatCodec.unescMap(target.payload)
                val merged = mergeContinuation(oldMap["text"].orEmpty(), cont)
                val newMap = oldMap.toMutableMap().apply {
                    put("text", merged)
                    if (cont != res.text) put("truncated", "1") else remove("truncated")
                }
                db.chatDao().updateMessagePayload(target.id, ChatCodec.escMap(newMap))
                // #streaming-fix: a bolha existente já recebeu o texto novo.
                com.bettertalker.app.data.llm.LocalProgress.clearPartial()
            } catch (e: kotlinx.coroutines.CancellationException) {
                _runState.value = ChatRunState.Cancelled()
                throw e
            } catch (e: Exception) {
                postText(friendlyChatError(e))
            } finally {
                turnScope = null
                currentGenerationJob = null
                _chatBusy.value = false
                if (_runState.value is ChatRunState.Sending ||
                    _runState.value is ChatRunState.Generating
                ) {
                    _runState.value = ChatRunState.Success
                }
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val messages = if (noteId != null) {
        _chatScope.flatMapLatest { scope ->
            // G3 (UX S): o parcial da geração anterior não vaza para o escopo novo.
            com.bettertalker.app.data.llm.LocalProgress.reset()
            // G2 (UX S): esvazia a lista antes do novo emit — sem "fantasma"
            // da conversa da seção anterior por ~150ms.
            db.chatDao().observeScoped(noteId, scope)
                .onStart { emit(emptyList()) }
        }.map { list ->
            list.map { e ->
                when (e.kind) {
                    "ideas" -> {
                        val m = ChatCodec.unescMap(e.payload)
                        ChatItem(e.id, e.fromMe, e.kind,
                            section = m["section"].orEmpty(),
                            cards = ChatCodec.cardsFromJson(m["cards"].orEmpty()),
                            origin = messageOriginOf(m), createdAt = e.createdAt)
                    }
                    "refs" -> {
                        val m = ChatCodec.unescMap(e.payload)
                        ChatItem(e.id, e.fromMe, e.kind, text = m["title"].orEmpty(),
                            detectedJson = m["detected"].orEmpty(),
                            origin = messageOriginOf(m), createdAt = e.createdAt)
                    }
                    "bases" -> {
                        val m = ChatCodec.unescMap(e.payload)
                        ChatItem(e.id, e.fromMe, e.kind, text = m["text"].orEmpty(),
                            slots = m["slots"].orEmpty().split(",").filter { it.isNotBlank() },
                            origin = messageOriginOf(m), createdAt = e.createdAt)
                    }
                    "sections" -> {
                        val m = ChatCodec.unescMap(e.payload)
                        ChatItem(e.id, e.fromMe, e.kind, text = m["title"].orEmpty(),
                            secItems = ChatCodec.sectionsFromJson(m["items"].orEmpty()),
                            origin = messageOriginOf(m), createdAt = e.createdAt)
                    }
                    "draft" -> {
                        val (title, items) = ChatCodec.draftFromJson(e.payload)
                        ChatItem(e.id, e.fromMe, e.kind, draftTitle = title,
                            draftItems = items, createdAt = e.createdAt)
                    }
                    else -> {
                        val m = ChatCodec.unescMap(e.payload)
                        ChatItem(e.id, e.fromMe, "text",
                            text = m["text"].orEmpty(), origin = messageOriginOf(m),
                            durationMs = m["durationMs"]?.toLongOrNull(),
                            createdAt = e.createdAt,
                            isTruncated = m["truncated"] == "1")
                    }
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    } else {
        MutableStateFlow(emptyList())
    }

    /** #4 (UX S): para a geração em andamento — o send trata o cancelamento
     *  (runState → Cancelled, composer destrava no finally). */
    fun stopGeneration() {
        currentGenerationJob?.cancel()
        currentGenerationJob = null
        com.bettertalker.app.data.llm.LocalProgress.reset()
    }

    private suspend fun post(
        fromMe: Boolean,
        kind: String,
        payload: String,
        origin: MessageOrigin = MessageOrigin.COPILOT,
        durationMs: Long? = null,
        /** #ui-polish: resposta cortada por limite de tokens. */
        truncated: Boolean = false,
        // #23-fix-c (G1): escopo do turno (capturado no send) > vivo.
        targetScope: String? = turnScope ?: _chatScope.value,
    ) {
        val nid = noteId ?: return
        // Origem embutida no JSON do payload (sem migration; histórico
        // antigo sem a chave decodifica como COPILOT).
        val withOrigin = ChatCodec.unescMap(payload)
            .toMutableMap().also { it["origin"] = origin.name }
        // #17-fix: tempo de resposta do assistente no payload (sem schema).
        if (durationMs != null && durationMs > 0) withOrigin["durationMs"] = durationMs.toString()
        // #ui-polish: flag de corte no payload JSON (sem migration).
        if (truncated) withOrigin["truncated"] = "1"
        db.chatDao().put(
            ChatEntity(
                newId("msg"), nid, fromMe, kind, ChatCodec.escMap(withOrigin),
                System.currentTimeMillis(),
                // F2.3: a mensagem pertence ao escopo do turno (G1) — não ao
                // escopo vivo no momento em que a resposta chega.
                sectionId = targetScope,
            )
        )
        // #streaming-fix: o parcial morre no mesmo instante em que a resposta
        // final entra no banco — sem texto duplicado nem camada fantasma.
        if (!fromMe) com.bettertalker.app.data.llm.LocalProgress.clearPartial()
    }

    private fun postText(t: String, origin: MessageOrigin = MessageOrigin.COPILOT) = viewModelScope.launch {
        post(false, "text", ChatCodec.escMap(mapOf("text" to t)), origin)
    }

    // T1 (chat minimalista): balão de boas-vindas removido — o chat abre
    // vazio, direto na conversa. O placeholder do composer orienta.

    fun clearChat() = viewModelScope.launch {
        val nid = noteId ?: return@launch
        // F2.3: limpa só a conversa do escopo atual (tópico ou global).
        db.chatDao().clearScoped(nid, _chatScope.value)
        // T1: o esboço colado vive só na conversa — limpar descarta o modo.
        pasteBuffer = null
        _pasteMode.value = false
        // T1 (chat minimalista): sem balão de boas-vindas após limpar.
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
    fun send(raw: String) = send(raw, postUserMessage = true, historyOverride = null)

    /**
     * #26-fix-c — núcleo do turno, compartilhado por enviar/Regenerar/Editar:
     * [postUserMessage] = false evita duplicar a pergunta já persistida;
     * [historyOverride] fixa o histórico do prompt (ex.: sem o turno reenviado).
     * Demais fluxos idênticos ao send().
     */
    private fun send(
        raw: String,
        postUserMessage: Boolean,
        historyOverride: List<ChatTurn>?,
    ) {
        // Mensagem vazia nunca sai: mesmo gate do web.
        val text = validateOutgoingMessage(raw) ?: return
        if (noteId == null) return
        // mínimo anti rajada/toque duplo: a UI também desabilita o envio com chatBusy
        if (_chatBusy.value) return
        lastUserMessage = text
        // #23-fix-c (G1): captura o escopo do turno ANTES de gerar.
        turnScope = _chatScope.value
        currentGenerationJob = viewModelScope.launch {
            // T2: qualquer envio conta como primeiro uso (banner some depois).
            settings.setChatSetupSeen()
            // #streaming-fix: zera o parcial do turno anterior ANTES de mostrar
            // "Sending"; sem isso o texto antigo reaparece durante o RAG.
            com.bettertalker.app.data.llm.LocalProgress.reset()
            _runState.value = ChatRunState.Sending
            // Histórico e first ANTES do post: a pergunta atual não contamina
            // a continuidade, e a primeira mensagem é detectada de verdade.
            // #26-fix-c: Regenerar/Editar passam o histórico já recortado.
            val historyBefore = historyOverride ?: conversationTurnsScoped()
            val isFirst = historyBefore.none { it.fromMe }
            // T2: push > seleção > bloco > discurso. O prompt recebe exatamente o
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
            val blockText = currentFocusText()
            // #26-fix-c: Regenerar/Retry reusam a pergunta já persistida.
            if (postUserMessage) {
                post(true, "text", ChatCodec.escMap(mapOf("text" to text)))
            }
            _chatBusy.value = true
            // F2.1-fix: o try cobre TUDO a partir daqui (intenção, RAG,
            // roteamento e geração). Antes, os return@launch das rotas
            // (OutOfScope/NothingToRefine/ProposalReply/Oratory) ficavam fora
            // do try e pulavam o finally — travando composer e runState.
            try {
            // T2 (Fase 2.1): ordem dos intercepts — colagem NOVA primeiro; o
            // comando explícito de materializar vence o fallback do modo colar
            // (antes, o pasteBuffer sequestrava "insira tudo na nota").
            when (com.bettertalker.app.data.copilot.ChatRouter.interceptFor(
                text,
                canStartPaste = pasteBuffer == null &&
                    _lastS34Document == null &&
                    outlines.get(noteId) == null,
                pasteActive = pasteBuffer != null,
                // #26-fix-b: só vira "material colado" se veio do clipboard.
                fromClipboard = clipboardMatches(text, currentClipboardText()),
            )) {
                // T1 (modo colar e conversar): texto longo colado, sem S-34 nem
                // esboço vinculado, vira o contexto íntegro da conversa — sem
                // persistir (sem draft/outline/attachment). Perguntas seguintes
                // usam a rota fixa minimalista (sem router/oratória/dossiê/RAG).
                com.bettertalker.app.data.copilot.ChatRouter.Intercept.NEW_PASTE -> {
                    pasteBuffer = text.take(com.bettertalker.app.data.copilot.MINIMAL_PASTE_MAX_CHARS)
                    _pasteMode.value = true
                    post(false, "text", ChatCodec.escMap(mapOf(
                        "text" to "Esboço recebido (${pasteBuffer!!.length} caracteres). " +
                            "Pergunte o que quiser sobre ele — sem vincular, sem cerimônia.")))
                    return@launch
                }
                // T2 (Fase 2): materializar a estrutura — ANTES do comando de
                // inserção pontual (T3 mini sequestraria "na nota") e ANTES do
                // fallback do modo colar (Fase 2.1).
                com.bettertalker.app.data.copilot.ChatRouter.Intercept.APPLY_STRUCTURE -> {
                    requestApplyStructure(historyBefore)
                    return@launch
                }
                com.bettertalker.app.data.copilot.ChatRouter.Intercept.PASTE_CHAT -> {
                    answerMinimal(text, historyBefore, isFirst)
                    return@launch
                }
                com.bettertalker.app.data.copilot.ChatRouter.Intercept.NONE -> Unit
            }
            // T3 (Mini Discurso): comando determinístico de inserção — nunca
            // vai ao LLM (funciona com qualquer provider).
            if (resolveInsertCommand(text)) return@launch
            // F5b (Modo C): estruturar texto colado de publicação numa seção
            // — determinístico, pré-split (qualquer provider).
            if (com.bettertalker.app.data.copilot.ChatRouter.isStructureFromPastedCommand(text)) {
                structureFromPasted(currentTarget())
                return@launch
            }
            // F5d (Modo B): opções/ângulos para um tema do zero — determinístico,
            // pré-split (qualquer provider).
            if (com.bettertalker.app.data.copilot.ChatRouter.isAnglesForThemeCommand(text)) {
                proposeAnglesFor(ChatIntent.extractThemeForAngles(text))
                return@launch
            }
            // FALTA-2: propor estrutura da seção — determinístico, pré-split
            // (qualquer provider). Sem seção em foco, só a palavra de escopo
            // explícita dispara (o handler orienta a focar).
            if (com.bettertalker.app.data.copilot.ChatRouter.isProposeStructureCommand(
                    text,
                    focused = _chatScope.value != null,
                )
            ) {
                proposeStructureFor(currentTarget())
                return@launch
            }
            // A intenção é interna: escolhe a trilha de recuperação e o foco do
            // prompt, e nunca é exibida ao usuário.
            val intent = inferIntent(text, isFirst)
            val turnContext = buildTurnFor(text, intent.trainingCategory, historyBefore, isFirst, blockText)
            // F5c: dedupe da ORIENTAÇÃO DO ORADOR — o dossiê só a repete
            // quando o bloco estrutural do S-34 (que já a carrega) não está
            // presente neste turno.
            val contextBlock = dossier?.let {
                dossierPromptBuilder.buildContextBlock(
                    it,
                    includeSpeakerNote = dossierSpeakerNoteIncluded(turnContext.structural),
                )
            }
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
                    // Provider remoto (DeepSeek) + sua chave.
                    val remote = ProviderFactory.resolveRemote(settings, app)
                    if (!ProviderFactory.useRemoteRoute(remote)) {
                        postText("Configure a chave de IA na tela Modelo IA para gerar esta parte.")
                        return@launch
                    }
                    // #15a: sem S-34 vinculado NÃO há mais recusa — a rota
                    // degrada para o chat normal (seção + corpus + RAG, dossiê
                    // injetado) e o aviso suave [NO_S34_WARNING] entra no fim
                    // da resposta. Com S-34, a geração oratória ancorada segue.
                    if (shouldDegradeOratoryToChat(hasS34Document = _lastS34Document != null)) {
                        answerRemote(
                            text, turnContext, historyBefore, isFirst, blockText, remote, contextBlock,
                            noS34Warning = true,
                        )
                        return@launch
                    }
                    generateOratory(route, text, remote, contextBlock, turnContext)
                    return@launch
                }
                is com.bettertalker.app.data.copilot.ChatRouter.Route.ApplyStructure -> {
                    // T2 (Fase 2): normalmente interceptado antes do mini-voz;
                    // se chegar via router, atende do mesmo jeito.
                    requestApplyStructure(historyBefore)
                    return@launch
                }
                is com.bettertalker.app.data.copilot.ChatRouter.Route.ProposeStructure -> {
                    // FALTA-2: pré-split (vale com qualquer provider).
                    proposeStructureFor(currentTarget())
                    return@launch
                }
                is com.bettertalker.app.data.copilot.ChatRouter.Route.StructureFromPasted -> {
                    // F5b (Modo C): normalmente interceptado antes do mini-voz;
                    // se chegar via router, atende do mesmo jeito.
                    structureFromPasted(currentTarget())
                    return@launch
                }
                is com.bettertalker.app.data.copilot.ChatRouter.Route.GenerateLocalNeeds -> {
                    // #8f: Necessidades Locais — discurso curto do zero, sem esboço.
                    generateLocalNeeds(route.inputs)
                    return@launch
                }
                is com.bettertalker.app.data.copilot.ChatRouter.Route.AnglesForTheme -> {
                    // F5d (Modo B): idem — pré-split cobre o caso comum.
                    proposeAnglesFor(route.theme)
                    return@launch
                }
                is com.bettertalker.app.data.copilot.ChatRouter.Route.StructuralQuery,
                is com.bettertalker.app.data.copilot.ChatRouter.Route.General,
                // T1 (modo colar e conversar): o VM decide antes do router
                // (precisa do S-34/esboço vinculado); route() nunca emite.
                is com.bettertalker.app.data.copilot.ChatRouter.Route.PasteAndChat -> Unit
            }

            // Fase 18: com chave BYOD, a rota nova (LLM real) decide.
            // Sem chave, o motor legado local continua (comportamento atual).
            // Provider remoto (DeepSeek) + sua chave.
            // (try aberto acima cobre também as rotas com return@launch.)
            val remote = ProviderFactory.resolveRemote(settings, app)
                if (ProviderFactory.useRemoteRoute(remote)) {
                    // Guiado seção-a-seção: determinístico, antes do LLM
                    // (mesmo padrão dos comandos de referência do Bug #7).
                    if (resolveSectionNav(text)) return@launch
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
                    answerRemote(text, turnContext, historyBefore, isFirst, blockText, remote, contextBlock)
                } else {
                    sendMutex.withLock {
                    if (resolveConfirm(text)) return@withLock
                    if (resolveSectionNav(text)) {
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
                // #23-fix-c: turno acabado — posts fora dele usam o escopo vivo.
                turnScope = null
                currentGenerationJob = null
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
    /** #14-fix: o foco de bloco/seleção só vale no escopo Tópico (SECTION). */
    private fun scopeBlockTitle(): String? =
        if (_scopeMode.value == ScopeMode.WHOLE) null else _activeBlockTitle.value

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
            // #15d: query enriquecida — seção em foco + nota + seções do
            // S-34 (resumo) + prosa + pergunta.
            query = enrichedRetrievalQuery(
                blockText = blockText,
                question = text,
                sectionTitle = scopeBlockTitle(),
                noteTitle = noteId?.let { db.noteDao().get(it)?.title },
                s34SectionTitles = _lastS34Document?.sections?.map { it.title }.orEmpty(),
            ),
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
            blockTitle = scopeBlockTitle(),
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
        // #14-fix: em WHOLE o bloco/seleção não valem (sem "conclusão fantasma").
        val (effBlock, effSel) = effectiveFocusFields(
            whole = _scopeMode.value == ScopeMode.WHOLE,
            activeBlockTitle = _activeBlockTitle.value,
            selection = _selection.value,
        )
        for (id in s34CandidateIds(candidateIds, linkedIds)) {
            val result = retriever.retrieve(
                sourceAttachmentId = id,
                // T2 — foco unificado: o tópico empurrado manda sobre o bloco
                // ativo/seleção (que podem apontar para outro tópico ou nada).
                sectionHint = structuralHint(
                    pushedTitle = pushedSectionTitle(),
                    activeBlockTitle = effBlock,
                    selection = effSel,
                ),
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
        turnContext: com.bettertalker.app.data.copilot.ChatTurnContext,
    ) {
        // #15a: sem S-34 o chamador degrada para o chat (com aviso suave);
        // este guard é defensivo — estado impossível pelo fluxo do sendMessage.
        val doc = _lastS34Document ?: return
        val view = route.sectionId?.let {
            com.bettertalker.app.data.s34.S34StructuralRetrieval.scopeToSection(doc, it)
        }
        val spec = com.bettertalker.app.data.copilot.OratoryGeneration.spec(
            route.mode, doc, view, route.action,
            // #16-fix-e: popula referenceTexts com os textos das refs BÍBLICAS
            // do esboço (acervo nwt) — mata a contradição "NÃO disponível" no
            // prompt da oratória e alimenta o gate via authorizedRefTexts.
            com.bettertalker.app.data.copilot.OratoryGeneration.referenceTextsFor(
                doc.sections,
            ) { raw -> repo.biblePassages(raw).map { it.passage.text } },
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
        // #16-fix-b: o LLM precisa ver a PROSA da seção (não só o título via
        // pushedFirstFocus). O bloco real (título + contentHtml) alimenta o
        // request, os blocos da proposta e o ANTES/DEPOIS do card — e mantém
        // a invariante do aceite (`block.contains(focus)`). Sem seção, o foco
        // resolvido é mantido.
        val block = oratoryFocusBlock(
            sectionTitle = section?.title,
            sectionContentHtml = section?.contentHtml,
            fallbackFocus = focus,
        )
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
                        text = block,
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
            val blocks = listOf(com.bettertalker.app.data.edit.EditBlock(targetId, block))
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
                    // #7e: gate de groundedness — mesmo verificador do chat,
                    // agora também no texto de oratória (citações/refs contra o
                    // corpus do turno + dossiê em contexto + foco).
                    val verified = verifyGeneratedText(
                        generated,
                        turnContext,
                        listOfNotNull(
                            contextBlock,
                            // #16-fix-b: o corpus do gate inclui a prosa da seção
                            // (texto puro) — o modelo agora a vê e pode citá-la.
                            stripHtmlToText(block).takeIf { it.isNotBlank() },
                        ) + com.bettertalker.app.data.copilot.OratoryGeneration.authorizedRefTexts(ready),
                    )
                    if (verified.metrics.hasFlags) {
                        val strictChars = verified.text
                            .removeSuffix(GroundednessVerifier.REMOVAL_NOTICE).length
                        android.util.Log.i(
                            "CopilotLLM",
                            "verifier oratory: flags=${verified.metrics.summary()} " +
                                "flaggedChars=${verified.metrics.flaggedChars} " +
                                "strictChars=$strictChars",
                        )
                    }
                    // Com remoções, o texto da proposta vira o verificado
                    // (frases sem apoio fora). #7e-fix: o aviso do verificador
                    // NÃO entra no conteúdo — vai para o `notice` da proposta.
                    val (cleanText, removalNotice) = stripRemovalNotice(verified.text)
                    val proposal = if (verified.hasRemovals) {
                        r.proposal.copy(
                            operations = listOf(
                                com.bettertalker.app.data.edit.EditOperation.Replace(
                                    targetId,
                                    com.bettertalker.app.data.export.markdownToSimpleHtml(cleanText),
                                )
                            )
                        )
                    } else {
                        r.proposal
                    }
                    _proposal.value = ProposalUi(
                        proposal, block, sectionId = secId, sectionBaseHash = secHash,
                        notice = listOfNotNull(notice, removalNotice)
                            .joinToString(" ").ifBlank { null },
                        sectionContentHtml = section?.contentHtml.orEmpty(),
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
     * T1 (modo colar e conversar) — resposta minimalista sobre o esboço
     * colado íntegro: sem router, oratória, dossiê, retrieval ou TAIL.
     * Gate em modo aviso (nunca remove); insuficiência e "⚠️ Revise"
     * seguem os mesmos padrões do modo padrão.
     */
    private suspend fun answerMinimal(
        text: String,
        history: List<ChatTurn>,
        isFirst: Boolean,
    ) {
        val remote = ProviderFactory.resolveRemote(settings, app)
        if (!ProviderFactory.useRemoteRoute(remote)) {
            postText("Configure a chave de IA na tela Modelo IA para conversar sobre o esboço.")
            return
        }
        val pasted = pasteBuffer
        if (pasted.isNullOrBlank()) {
            postText("Cole o esboço de novo — perdi o texto colado.")
            return
        }
        val provider = com.bettertalker.app.data.llm.ProviderFactory.createFor(remote, app)
        // T1 (Fase 2.1): memória da construção TAMBÉM no modo colar. O fio
        // (decisões/consenso + próximo passo) entra como CONTEÚDO da mensagem
        // — o system segue minimalista. Sem resumo (conversa curta sem
        // decisões) → mensagem inalterada (prompt igual ao anterior).
        val memory = com.bettertalker.app.data.copilot.renderMemoryBlock(
            com.bettertalker.app.data.copilot.buildRollingSummary(
                history,
                pushedSectionTitle() ?: scopeBlockTitle().orEmpty(),
            )
        )
        val messageWithMemory =
            com.bettertalker.app.data.copilot.minimalMemoryPrefix(memory) + text
        val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            provider.generate(
                com.bettertalker.app.data.llm.LlmRequest(
                    text = pasted,
                    action = com.bettertalker.app.data.llm.LlmAction.CHAT,
                    message = messageWithMemory,
                    history = history,
                    isFirstMessage = isFirst,
                    minimalPaste = pasted,
                )
            )
        }
        val outText = truncateIfCut(res.text, res.meta.finishReason)
        val truncated = outText != res.text
        // Gate em modo aviso: o esboço colado É a fonte — nada é removido,
        // só avisado. Corpus = texto íntegro (o modelo citou o esboço).
        val corpus = listOf(pasted)
        val outcome = verifyChatText(outText, corpus)
        val verified = outcome.result
        // Nível 0: o strict shadow mede o “antes” (o que seria removido) sem
        // alterar o texto entregue. Só métricas, nunca conteúdo.
        val strictShadow = GroundednessVerifier.verify(outText, corpus)
        if (verified.metrics.hasFlags || strictShadow.metrics.hasFlags) {
            val strictChars = strictShadow.text
                .removeSuffix(GroundednessVerifier.REMOVAL_NOTICE).length
            android.util.Log.i(
                "CopilotLLM",
                "verifier minimal: flags=${verified.metrics.summary()} " +
                    "flaggedChars=${verified.metrics.flaggedChars} " +
                    "keptChars=${verified.text.length} strictChars=$strictChars",
            )
        }
        val notice = outcome.notice
        val finalText = if (notice == null) verified.text else verified.text + "\n\n" + notice
        post(false, "text", ChatCodec.escMap(mapOf("text" to finalText)), MessageOrigin.COPILOT,
            durationMs = res.meta.durationMs, truncated = truncated)
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
        /** #15a: rota sem S-34 degradada → aviso suave no fim da resposta. */
        noS34Warning: Boolean = false,
    ) {
        // #17-fix: a rota de chat respeita o chip (Rápido = low / Profundo = high);
        // rotas estruturadas (oratória/proposta) forçam "none" dentro do provider.
        val provider = com.bettertalker.app.data.llm.ProviderFactory.createFor(
            remote, app, thinkingEffortFor(_thinkingMode.value)
        )
        // Inversão de contexto (correção): o payload recebe AMBOS — o dossiê
        // (metadados, via contextBlock) E a prosa em foco (via text).
        // Nunca zera: sem a prosa, o modelo vê só o plano e "revise esta
        // seção" sai genérico. O dossiê complementa, não substitui.
        // T3 (acesso bíblico): refs citadas NA PERGUNTA → texto literal da TNM
        // por ref exata (findByRef). P4: só quando a pergunta cita uma ref —
        // nunca varre a Bíblia por padrão. O bloco entra no prompt E no corpus
        // do gate (citação legítima não é removida).
        val questionVerses = if (text.isNotBlank()) repo.biblePassages(text, 4) else emptyList()
        // T1 (expert em publicações): trechos de publicações citadas NA
        // PERGUNTA (mesmo padrão dos versículos; P4: só com citação).
        val questionPubs = if (text.isNotBlank()) repo.publicationPassages(text, 3) else emptyList()
        // T1 (Fase 2): memória da construção — resumo rolante do fio da
        // conversa, antes do dossiê. Null em conversa curta sem decisões =
        // prompt idêntico ao anterior (cache preservado).
        val memory = com.bettertalker.app.data.copilot.renderMemoryBlock(
            com.bettertalker.app.data.copilot.buildRollingSummary(
                history,
                pushedSectionTitle() ?: scopeBlockTitle().orEmpty(),
            )
        )
        val effectiveContextBlock = combineContextBlocks(
            listOfNotNull(memory, contextBlock).joinToString("\n\n").ifBlank { null },
            questionVerses,
            questionPubs,
        )
        if (questionVerses.isNotEmpty()) {
            android.util.Log.i(
                "CopilotLLM",
                "chat remoto: ${questionVerses.size} versículo(s) da pergunta injetado(s)"
            )
        }
        if (questionPubs.isNotEmpty()) {
            android.util.Log.i(
                "CopilotLLM",
                "chat remoto: ${questionPubs.size} trecho(s) de publicação da pergunta injetado(s)"
            )
        }        // HTTP fora da Main (§50): NetworkOnMainThreadException virava erro
        // genérico silencioso. Retrieval Room permanece onde está (provado
        // em aparelho); só a rede desce para IO.
        val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            provider.generate(
                com.bettertalker.app.data.llm.LlmRequest(
                    text = focusTextForLlm(blockText),
                    action = com.bettertalker.app.data.llm.LlmAction.CHAT,
                    message = text,
                    history = history,
                    isFirstMessage = isFirst,
                    // #11-fix: folga quando há ref do acervo resolvida na pergunta.
                    maxOutputTokens = answerOutputBudget(
                        questionVerses.isNotEmpty() || questionPubs.isNotEmpty()
                    ),
                    // F2.3 §10: o RAG NUNCA desaparece com foco. O dossiê do
                    // tópico e o ContextPack coexistem no mesmo prompt.
                    contextPack = turnContext.pack,
                    blockTitle = scopeBlockTitle(),
                    structural = turnContext.structural,
                    oratory = turnContext.oratory,
                    contextBlock = effectiveContextBlock,
                )
            )
        }
        // Quirk do qwen3.8-27b: para no meio sem pontuação (finish_reason
        // "stop" prematuro). Trunca até a última frase completa — nunca
        // entrega texto quebrado. Só chat geral (oratória/proposta têm schema).
        val text = truncateIfCut(res.text, res.meta.finishReason)
        val truncated = text != res.text
        // T1 — gate de alucinação universal (pós-filtro do streaming):
        // confere citações/refs contra o corpus injetado. Não bloqueia: remove
        // só frases claramente sem apoio e anexa o aviso padrão. T1/T3: o
        // corpus do gate cobre TUDO que o prompt injeta — o bloco do dossiê
        // (refs do tópico com texto), os versículos da pergunta e os trechos
        // de publicação da pergunta. Sem o dossiê, citar uma ref do PRÓPRIO
        // tópico dispara o aviso falso "fora dos trechos" (visto no device
        // com "ec 3:11").
        val corpus = verificationCorpus(
            turnContext,
            listOfNotNull(effectiveContextBlock) +
                questionVerseLines(questionVerses, text) +
                questionPubs.map { it.passage.text },
        )
        val outcome = verifyChatText(text, corpus)
        val verified = outcome.result
        // Nível 0: mede o “antes” (strict) e o “depois” (warn-only) no mesmo
        // turno. O strict shadow NÃO é usado como resposta — só métricas.
        val strictShadow = GroundednessVerifier.verify(text, corpus)
        if (verified.metrics.hasFlags || strictShadow.metrics.hasFlags) {
            val strictChars = strictShadow.text
                .removeSuffix(GroundednessVerifier.REMOVAL_NOTICE).length
            android.util.Log.i(
                "CopilotLLM",
                "verifier chat: flags=${verified.metrics.summary()} " +
                    "flaggedChars=${verified.metrics.flaggedChars} " +
                    "keptChars=${verified.text.length} strictChars=$strictChars",
            )
        }
        // Aviso ÚNICO: substitui o REMOVAL_NOTICE e o citationRevisionNotice.
        val notice = outcome.notice
        if (notice != null) {
            android.util.Log.w("CopilotLLM", "chat remoto: aviso único do verifier exibido")
        }
        val finalText = if (notice == null) verified.text else verified.text + "\n\n" + notice
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
        // #8c (GAP-4): instrução discreta quando a resposta fecha decisões.
        val withInstruction = appendInsertInstruction(withTip, verified.text)
        // #15a: aviso suave quando a resposta veio de rota degradada sem S-34.
        val withS34Notice =
            if (noS34Warning) appendNoS34Warning(withInstruction) else withInstruction
        // #24-fix: fecha órfão de <texto-do-card> sem abridor (o modelo às
        // vezes emite só o fechamento) — o payload persistido fica limpo.
        val persistedText = stripOrphanCardClose(withS34Notice)
        // T1 (Fase 2): consenso automático — decisões da resposta (〈decisão〉
        // ou pista explícita) viram agreedApproach do tópico em foco, sem
        // duplicar. Best-effort: nunca quebra a resposta.
        try {
            recordConsensus(verified.text)
        } catch (_: Exception) {
        }
        post(false, "text", ChatCodec.escMap(mapOf("text" to persistedText)), MessageOrigin.COPILOT,
            durationMs = res.meta.durationMs, truncated = truncated)
        // #15d-escopo: RAG fraco + nota sem anexos indexados + publicação
        // indexada não vinculada → sugere vincular (1× por sessão).
        if (!scopeSuggestionShown) {
            val atts = db.attachmentDao().all()
            val hasNoteAttachments = noteId?.let { nid ->
                atts.any { it.noteId == nid && it.indexed }
            } ?: false
            val hasUnlinkedIndexed = atts.any { it.indexed && it.noteId.isNullOrBlank() }
            if (shouldSuggestScope(
                    turnContext.pack.relevantCount,
                    hasNoteAttachments = hasNoteAttachments,
                    hasUnlinkedIndexed = hasUnlinkedIndexed,
                )
            ) {
                scopeSuggestionShown = true
                _scopeSuggestion.value = true
            }
        }
    }

    /**
     * T1 (Fase 2): persiste o consenso da resposta no tópico em foco.
     * O editor observa o DAO e reflete sozinho; o dossiê seguinte já traz
     * a abordagem acordada. Puro o merge ([mergeApproach]).
     */
    private suspend fun recordConsensus(responseText: String) {
        val decisions =
            com.bettertalker.app.data.copilot.extractDecisions(responseText)
        if (decisions.isEmpty()) return
        val sid = _pushedContext?.sectionId ?: return
        val sec = db.speechSectionDao().get(sid) ?: return
        val merged = com.bettertalker.app.data.copilot.mergeApproach(sec.agreedApproach, decisions)
            ?: return
        db.speechSectionDao().upsert(
            sec.copy(agreedApproach = merged, updatedAt = System.currentTimeMillis())
        )
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
        val applied: Boolean = false,
        /**
         * #26-fix: contentHtml da seção na GERAÇÃO — define o rótulo do
         * botão primário ("Usar este texto" × "Adicionar ao final").
         * Vazio no caminho da nota (sem seção).
         */
        val sectionContentHtml: String = "",
    )

    private val _proposal = MutableStateFlow<ProposalUi?>(null)
    val proposal = _proposal.asStateFlow()

    /**
     * FALTA-2: proposta de ESTRUTURA da seção (coexiste com `ProposalUi`,
     * que é de TEXTO). O plano vem do `OutlineProposer`; o aceite escreve
     * objetivo/minutes/refs/abordagem na seção. Estados: pendente
     * (`applied=false`, `notice=null`) → `applied=true` | `notice="..."`.
     * Sem enum novo — mesmo padrão de `ProposalUi`.
     */
    data class StructureProposalUi(
        val id: String,
        val sectionId: String,
        /** Proposta integral (para revalidar no aceite). */
        val proposal: OutlineProposal,
        /** Plano da seção focada (conteúdo revisável). */
        val plan: SectionPlan,
        /** O que a IA propõe, em 1-2 frases (vira abordagem no aceite). */
        val summary: String,
        /** FNV-1a do bloco da seção na criação (stale real na aceitação). */
        val sectionBaseHash: String,
        /** Criada sem refs na origem — o aceite revalida com a regra relaxada. */
        val allowEmptyRefs: Boolean = false,
        val applied: Boolean = false,
        val notice: String? = null,
    )

    private val _structureProposal = MutableStateFlow<StructureProposalUi?>(null)
    val structureProposal: StateFlow<StructureProposalUi?> = _structureProposal.asStateFlow()

    /**
     * F5b (Modo C): proposta de NOTA INTEIRA a partir de texto colado numa
     * seção ("estruturar como esboço"). Substitui as seções da nota via
     * `OutlineImportService.persist` com confirmação. Estados: pendente
     * (`applied=false`, `notice=null`) → `applied=true` | `notice="..."`.
     */
    data class NoteStructureProposalUi(
        val id: String,
        val noteId: String,
        val title: String,
        val summary: String,
        val totalMinutes: Int,
        val sections: List<SectionPlan>,
        /** Sinais do PublicationDetector (informativo no cartão). */
        val hints: List<String> = emptyList(),
        /** Nº de seções atuais da nota na criação (aviso de substituição). */
        val currentSectionCount: Int = 0,
        /** FNV-1a da nota (título + seções) na criação (stale real no aceite). */
        val noteBaseHash: String,
        /** #8f gera com a regra de refs relaxada — o aceite revalida igual. */
        val allowEmptyRefs: Boolean = false,
        val applied: Boolean = false,
        val notice: String? = null,
    )

    private val _noteStructureProposal = MutableStateFlow<NoteStructureProposalUi?>(null)
    val noteStructureProposal: StateFlow<NoteStructureProposalUi?> =
        _noteStructureProposal.asStateFlow()

    // #15d-escopo: sugestão de vincular publicações (RAG fraco + nota sem
    // anexos indexados + publicação indexada não vinculada). 1× por sessão.
    private val _scopeSuggestion = MutableStateFlow(false)
    val scopeSuggestion: StateFlow<Boolean> = _scopeSuggestion.asStateFlow()
    private var scopeSuggestionShown = false

    /** #15d-escopo: "Agora não" — fecha e não reaparece nesta sessão. */
    fun dismissScopeSuggestion() {
        _scopeSuggestion.value = false
    }

    /**
     * F5d (Modo B): até 3 propostas de ângulo (DOCTRINAL/PRACTICAL/NARRATIVE)
     * para um discurso do zero ("me dá opções para X"). `chosen` = id da
     * escolhida; ao escolher, uma `NoteStructureProposalUi` é criada (reuso
     * do #5b) e o card fica resolvido (sem mais botões).
     */
    data class MultiProposalUi(
        val id: String,
        val theme: String,
        val proposals: List<OutlineProposal>,
        val chosen: String? = null,
        val notice: String? = null,
    )

    private val _multiProposal = MutableStateFlow<MultiProposalUi?>(null)
    val multiProposal: StateFlow<MultiProposalUi?> = _multiProposal.asStateFlow()

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

    /** Texto em foco agora (push > seleção > bloco > discurso). Mesma regra do send(). */
    private suspend fun currentFocusText(): String {
        // #14-fix: em WHOLE só o corpo da nota vale (sem bloco/seleção).
        val (effBlock, effSel) = effectiveFocusFields(
            whole = _scopeMode.value == ScopeMode.WHOLE,
            activeBlockTitle = _activeBlockTitle.value,
            selection = _selection.value,
        )
        return pushedFirstFocus(
            pushedTitle = pushedSectionTitle(),
            selection = effSel,
            activeBlockTitle = effBlock,
            noteBody = _noteBody.value.take(2000),
        )
    }

    /**
     * #26-fix-b: texto atual do clipboard do sistema (null quando vazio ou
     * indisponível) — alimenta a detecção real de "material colado".
     */
    private fun currentClipboardText(): String? {
        val cm = app.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0)?.coerceToText(app)?.toString()
    }

    /**
     * Título da seção empurrada pelo editor (null = sem push ou seção
     * removida). Usado para unificar o foco: push manda sobre os canais
     * legados (seleção/bloco). Pura a consulta à parte do DAO.
     */
    private suspend fun pushedSectionTitle(): String? {
        val sectionId = _pushedContext?.sectionId ?: return null
        return db.speechSectionDao().get(sectionId)?.title?.takeIf { it.isNotBlank() }
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
                                r.proposal, focus, sectionId = secId, sectionBaseHash = secHash,
                                sectionContentHtml = section?.contentHtml.orEmpty(),
                            )
                        else -> postText("Não consegui montar a proposta agora. Tente novamente.")
                    }
                    return@launch
                }
                // Provider remoto (DeepSeek) + sua chave.
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
                        blockTitle = scopeBlockTitle(),
                        responseFormat = ResponseFormat.EDIT_PROPOSAL,
                        editMode = mode,
                        brief = brief,
                        // F2b: dossiê do último send() (a proposta nasce do turno).
                        // F5c: dedupe da ORIENTAÇÃO DO ORADOR (igual ao send()).
                        contextBlock = lastDossier?.let {
                            dossierPromptBuilder.buildContextBlock(
                                it,
                                includeSpeakerNote = dossierSpeakerNoteIncluded(turnContext.structural),
                            )
                        },
                    ))
                }
                android.util.Log.e("CopilotLLM", "proposal generated len=" + res.text.length +
                    " offline=" + res.meta.offline)
                when (val r = parseEditProposal(res.text, blocks, "focus", mode)) {
                    is com.bettertalker.app.data.edit.ParseResult.Ok ->
                        _proposal.value = ProposalUi(
                            r.proposal, focus, sectionId = secId, sectionBaseHash = secHash,
                            sectionContentHtml = section?.contentHtml.orEmpty(),
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
     *
     * #26-fix: na seção, o botão primário ADICIONA o texto proposto (seção
     * vazia usa direto; com conteúdo, antigo + "\n\n" + proposto). O
     * `forceReplace` (⋯ "Substituir a seção") mantém o comportamento clássico
     * — resultado completo das operações.
     */
    fun acceptProposal(forceReplace: Boolean = false) {
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
                val fullContent = applyProposalToSectionBlock(
                    section.title, section.contentHtml, ui.focusText, ui.proposal
                )
                if (fullContent == null) {
                    _proposal.value = ui.copy(
                        notice = "Proposta obsoleta: o bloco mudou depois da geração. Gere novamente.")
                    return@launch
                }
                // #26-fix: append adaptativo — o trecho proposto (delta das
                // ops, sem marcadores) é SOMADO à seção; "Substituir a seção"
                // aplica o resultado completo (comportamento clássico).
                val proposed = stripInternalTags(proposedAdditionContent(ui.proposal))
                val newContent = proposalApplyContent(
                    oldContentHtml = section.contentHtml,
                    proposedContentHtml = proposed,
                    fullContentHtml = fullContent,
                    forceReplace = forceReplace,
                )
                proposalUndo.addLast(ProposalSnapshot.Section(section))
                if (proposalUndo.size > 50) proposalUndo.removeFirst()
                db.speechSectionDao().upsert(
                    section.copy(contentHtml = newContent, updatedAt = System.currentTimeMillis())
                )
                _proposal.value = appliedProposalUi(ui)
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
            // #19-fix-a2: o aceite não persiste marcadores internos (mesmo fix do section path).
            notes.save(nid, note.title, applied.second, stripInternalTags(applied.first),
                note.folderId, note.colorArgb, note.pinned)
            _proposal.value = appliedProposalUi(ui)
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

    /**
     * FALTA-2: gera a estrutura da seção em foco via `OutlineGenerator`
     * (1 chamada, escopo por seção; refs da própria seção como âncoras).
     * Sem chave remota → orienta configurar (mesmo padrão da oratória).
     * Proposta válida vira `StructureProposalUi` + anúncio no escopo.
     */
    private suspend fun proposeStructureFor(target: DraftTarget?) {
        val sectionId = (target as? DraftTarget.Section)?.sectionId
        if (sectionId == null) {
            postText(structureTargetError(target) ?: STRUCTURE_NEEDS_SECTION_MESSAGE)
            return
        }
        val section = guideSections.value.firstOrNull { it.id == sectionId }
            ?: db.speechSectionDao().get(sectionId)?.toDomain()
        if (section == null) {
            postText("Não achei a seção em foco. Reabra o tópico no editor.")
            return
        }
        val remote = ProviderFactory.resolveRemote(settings, app)
        if (!ProviderFactory.useRemoteRoute(remote)) {
            postText("Configure a chave de IA na tela Modelo IA para gerar esta parte.")
            return
        }
        val principles = dossierProvider?.invoke(DraftTarget.Section(sectionId))
            ?.methodPrinciples.orEmpty()
        // E2E: refs do #3c vêm também dos sub-pontos (é onde o esboço as
        // guarda); a seção só costuma ter refs depois de um aceite anterior.
        val subPoints = db.subPointDao().forSection(sectionId).map { it.toDomain() }
        val generator = com.bettertalker.app.data.planning.OutlineGeneratorImpl(
            ProviderFactory.createFor(remote, app)
        )
        val request = buildStructureRequest(section, principles, subPoints = subPoints)
        // Refs vazias no request: o prompt manda o LLM não inventar — validar
        // com a regra relaxada (paridade com o #8f), senão o gate de refs
        // do validador derruba por design ("veio incompleta" enganoso).
        val allowEmptyRefs = request.bibleRefs.isEmpty()
        val proposal = try {
            generator.generate(request)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // D6: não engolir cancelamento
        } catch (_: Exception) {
            null
        }
        if (proposal == null) {
            postText("Não consegui gerar a estrutura. Tente novamente.")
            return
        }
        if (!validateStructureAccept(proposal, allowEmptyRefs)) {
            postText(structureFailureMessage(hasRefs = !allowEmptyRefs))
            return
        }
        val plan = pickSectionPlan(proposal, section.title)
        if (plan == null) {
            postText("A estrutura veio sem seções. Tente novamente.")
            return
        }
        _structureProposal.value = StructureProposalUi(
            id = newId("sprop"),
            sectionId = sectionId,
            proposal = proposal,
            plan = plan,
            summary = proposal.summary,
            sectionBaseHash = hashText(sectionProposalBlock(section.title, section.contentHtml)),
            allowEmptyRefs = allowEmptyRefs,
        )
        post(
            false,
            "text",
            ChatCodec.escMap(mapOf("text" to "— Estrutura proposta para ${section.title} —")),
        )
    }

    /**
     * FALTA-2: aceita a estrutura — revalida, barra stale por hash e escreve
     * objetivo/minutes/refs/abordagem na seção (via DAO, como o aceite de
     * texto; o editor observa o banco e reflete). `approvedAt` intacto:
     * aprovação é passo separado (#3a).
     */
    fun acceptStructureProposal() {
        val ui = _structureProposal.value ?: return
        if (ui.applied) return
        viewModelScope.launch {
            if (!validateStructureAccept(ui)) {
                _structureProposal.value = ui.copy(notice = "A estrutura ficou inválida. Gere novamente.")
                return@launch
            }
            val current = db.speechSectionDao().get(ui.sectionId)?.toDomain() ?: run {
                _structureProposal.value = ui.copy(notice = STRUCTURE_STALE_NOTICE)
                return@launch
            }
            when (val d = decideStructureAccept(current, ui.sectionBaseHash, ui.plan, ui.summary)) {
                StructureAcceptDecision.Stale ->
                    _structureProposal.value = ui.copy(notice = STRUCTURE_STALE_NOTICE)
                is StructureAcceptDecision.Apply -> {
                    db.speechSectionDao().upsert(d.updated.toEntity())
                    _structureProposal.value = ui.copy(notice = "Estrutura aplicada.", applied = true)
                }
            }
        }
    }

    /** Rejeita a estrutura: só marca a UI, nada é escrito. */
    fun rejectStructureProposal() {
        val ui = _structureProposal.value ?: return
        _structureProposal.value = ui.copy(notice = STRUCTURE_REJECTED_NOTICE)
    }

    // ---------- F5b (Modo C): estruturar texto colado de publicação ----------

    /**
     * Fase A — aplica na nota atual o MESMO plano do FAB:
     * TALK = Intro + BODY + Conclusão; DEMO = 1 BODY. Atualiza o tipo da nota
     * (MINISTRY_TALK/MINISTRY_PART/…) para o draft usar a persona correta.
     * Não chama o LLM: a estrutura já veio determinística do plano — o que
     * elimina a divergência de cards entre FAB e Editor.
     */
    private suspend fun applyVmPastePlan(plan: PastePlan) {
        val nid = noteId
        if (nid == null) {
            postText(STRUCTURE_PASTED_NEEDS_SECTION_MESSAGE)
            return
        }
        val now = System.currentTimeMillis()
        val existing = db.speechSectionDao().forNote(nid)
        // Nota vazia (empty state do Editor): cria os cards do plano inteiro
        // (Intro/BODY/Conclusão). Nota que já tem estrutura: anexa só o corpo
        // da parte — não duplica abertura/fechamento existentes.
        val toApply = if (existing.isEmpty()) {
            plan.sections
        } else {
            plan.sections.filter { it.role == SectionRole.BODY }
        }
        var order = (existing.maxOfOrNull { it.order } ?: -1) + 1
        toApply.forEach { s ->
            db.speechSectionDao().upsert(
                SpeechSection(
                    id = newId("sec"),
                    noteId = nid,
                    order = order++,
                    role = s.role,
                    title = s.title.ifBlank { "Corpo" },
                    minutes = s.minutes,
                    contentHtml = s.contentHtml,
                    bibleRefs = s.bibleRefs,
                    publicationRefs = s.publicationRefs,
                    methodPrinciple = null,
                    createdAt = now,
                    updatedAt = now,
                ).toEntity()
            )
        }
        db.noteDao().get(nid)?.let { note ->
            // Paridade com o FAB: nota nova ("Nova nota"/vazia) recebe o
            // título do plano ("Cultivando o interesse (4 min)"). Notas com
            // título real são preservadas.
            val placeholder = note.title.isBlank() ||
                note.title == "Nova nota" || note.title == "Sem título"
            val title = if (placeholder) plan.noteTitle else note.title
            if (note.discourseType != plan.discourseType.name || note.title != title) {
                db.noteDao().upsert(
                    note.copy(discourseType = plan.discourseType.name, title = title)
                )
            }
        }
        val base = if (existing.isEmpty()) {
            plan.notice ?: "Material V&M reconhecido."
        } else {
            val bodyTitle = toApply.firstOrNull { it.role == SectionRole.BODY }?.title
                ?: plan.noteTitle
            "Parte adicionada à nota: $bodyTitle."
        }
        // Princípio de mentoria: os cards já existem; a IA orienta sob demanda
        // e só escreve quando o usuário pedir (nada de geração automática).
        postText(
            "$base\n\nPeça ao Copilot para orientar a preparação — " +
                "o texto só é escrito quando você pedir."
        )
    }

    /**
     * F5b (Modo C): divide o texto colado na seção em foco num esboço de
     * nota inteira. Gatilho determinístico; a IA só estrutura o conteúdo
     * (nunca inventa refs — o request leva as refs extraídas do texto).
     */
    private suspend fun structureFromPasted(
        target: DraftTarget?,
        materialNature: String? = null,
    ) {
        val targetError = structurePastedTargetError(target)
        if (targetError != null) {
            postText(targetError)
            return
        }
        val sectionId = (target as DraftTarget.Section).sectionId
        val section = guideSections.value.firstOrNull { it.id == sectionId }
            ?: db.speechSectionDao().get(sectionId)?.toDomain()
        if (section == null) {
            postText("Não achei a seção em foco. Reabra o tópico no editor.")
            return
        }
        val sourceText = htmlToPlainText(section.contentHtml).trim()
        structurePastedEmptyError(sourceText)?.let {
            postText(it)
            return
        }
        val signal = PublicationDetector.looksLikePublication(sourceText)
        val nid = noteId
        if (nid == null) {
            postText(STRUCTURE_PASTED_NEEDS_SECTION_MESSAGE)
            return
        }
        val remote = ProviderFactory.resolveRemote(settings, app)
        if (!ProviderFactory.useRemoteRoute(remote)) {
            postText("Configure a chave de IA na tela Modelo IA para gerar esta parte.")
            return
        }
        val generator = OutlineGeneratorImpl(ProviderFactory.createFor(remote, app))
        val request = buildStructureFromPastedRequest(
            section, sourceText, materialNature = materialNature,
        )
        val proposal = try {
            generator.generate(request)
        } catch (_: Exception) {
            null
        }
        if (proposal == null) {
            postText("Não consegui estruturar o texto. Tente novamente.")
            return
        }
        val validation = OutlineProposalValidator.validate(proposal)
        if (validation is com.bettertalker.app.domain.planning.ValidationResult.Invalid) {
            postText(
                "A estrutura veio inválida (${validation.errors.joinToString(", ")}). " +
                    "Tente novamente."
            )
            return
        }
        val sectionsNow = db.speechSectionDao().forNote(nid).map { it.toDomain() }
        _noteStructureProposal.value = NoteStructureProposalUi(
            id = newId("nsp"),
            noteId = nid,
            title = proposal.title,
            summary = proposal.summary,
            totalMinutes = proposal.totalMinutes,
            sections = proposal.sections,
            hints = signal.hints,
            currentSectionCount = sectionsNow.size,
            noteBaseHash = noteStructureHash(db.noteDao().get(nid)?.title, sectionsNow),
        )
        post(
            false,
            "text",
            ChatCodec.escMap(mapOf("text" to "— Estrutura proposta a partir do texto colado —")),
        )
    }

    /**
     * F5b (Modo C): aceita a proposta de nota — revalida, barra stale (hash
     * da nota inteira) e SUBSTITUI as seções via `OutlineImportService.persist`
     * (atômico). `approvedAt` não é tocado: aprovação é passo separado.
     */
    fun acceptNoteStructureProposal() {
        val ui = _noteStructureProposal.value ?: return
        if (ui.applied) return
        viewModelScope.launch {
            when (val d = decideNoteStructureAccept(ui, currentNoteStructureHash())) {
                NoteStructureAcceptDecision.Invalid ->
                    _noteStructureProposal.value = ui.copy(notice = NOTE_STRUCTURE_INVALID_NOTICE)
                NoteStructureAcceptDecision.Stale ->
                    _noteStructureProposal.value = ui.copy(notice = NOTE_STRUCTURE_STALE_NOTICE)
                is NoteStructureAcceptDecision.Apply -> {
                    try {
                        // F5c: contagem REAL (intro + bodies + conclusão).
                        val total = outlineImportService.persist(
                            d.conversion,
                            DiscourseType.S34_DISCOURSE,
                        )
                        _noteStructureProposal.value = ui.copy(
                            applied = true,
                            notice = structureAppliedNotice(total),
                        )
                    } catch (_: Exception) {
                        _noteStructureProposal.value = ui.copy(notice = NOTE_STRUCTURE_FAILED_NOTICE)
                    }
                }
            }
        }
    }

    /** F5b: rejeita a proposta de nota — só marca a UI, nada é escrito. */
    fun rejectNoteStructureProposal() {
        val ui = _noteStructureProposal.value ?: return
        _noteStructureProposal.value = ui.copy(notice = NOTE_STRUCTURE_REJECTED_NOTICE)
    }

    /** F5b: hash atual da nota (título + seções), para o gate de stale. */
    private suspend fun currentNoteStructureHash(): String {
        val nid = noteId ?: return ""
        val sections = db.speechSectionDao().forNote(nid).map { it.toDomain() }
        return noteStructureHash(db.noteDao().get(nid)?.title, sections)
    }

    // ---------- F5d (Modo B): ângulos para um discurso do zero ----------

    /**
     * F5d (Modo B): propõe até 3 ângulos para [theme] via `OutlineProposer`
     * (retrieval + 1 geração por ângulo; cada uma já validada na origem).
     * A escolha cria uma `NoteStructureProposalUi` — mesmo cartão e aceite
     * do #5b, sem lógica duplicada.
     */
    private suspend fun proposeAnglesFor(theme: String?) {
        val clean = theme?.trim()?.takeIf { it.isNotBlank() }
        if (clean == null) {
            postText(ANGLES_NEEDS_THEME_MESSAGE)
            return
        }
        val remote = ProviderFactory.resolveRemote(settings, app)
        if (!ProviderFactory.useRemoteRoute(remote)) {
            postText("Configure a chave de IA na tela Modelo IA para gerar esta parte.")
            return
        }
        val proposals = try {
            OutlineProposerFactory(
                passageDao = db.passageDao(),
                attachmentDao = db.attachmentDao(),
                llmProvider = ProviderFactory.createFor(remote, app),
            ).create().propose(clean, ANGLES_TOTAL_MINUTES, Audience.GENERAL)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        when (val outcome = buildAnglesOutcome(clean, proposals)) {
            is AnglesOutcome.Error -> postText(outcome.message)
            is AnglesOutcome.Ui -> {
                _multiProposal.value = outcome.ui
                post(
                    false,
                    "text",
                    ChatCodec.escMap(mapOf(
                        "text" to "— ${outcome.ui.proposals.size} ângulos propostos para: " +
                            "${outcome.ui.theme} —",
                    )),
                )
            }
        }
    }

    /**
     * #8f: Necessidades Locais — discurso curto do zero, sem esboço. Reusa o
     * mesmo gerador/validador do #5b (regra de refs relaxada) e o mesmo
     * cartão de aceite (NoteStructureProposalUi). Refs do acervo entram
     * quando há Bíblia indexada; nunca bloqueiam.
     */
    private suspend fun generateLocalNeeds(
        inputs: com.bettertalker.app.data.copilot.ChatRouter.LocalNeedsInputs,
    ) {
        val nid = noteId
        if (nid == null) {
            postText("Abra uma nota para eu montar o discurso.")
            return
        }
        val remote = ProviderFactory.resolveRemote(settings, app)
        if (!ProviderFactory.useRemoteRoute(remote)) {
            postText("Configure a chave de IA na tela Modelo IA para gerar esta parte.")
            return
        }
        // B3: refs do acervo quando houver Bíblia indexada (vazio = segue sem).
        val bibleRefs = try {
            com.bettertalker.app.data.planning.RoomBibleIndex(
                com.bettertalker.app.data.repo.RoomRetrievalRepository(
                    db.passageDao(), db.attachmentDao()
                ),
                db.attachmentDao(),
            ).findByTheme(inputs.theme, 3)
        } catch (_: Exception) {
            emptyList()
        }
        val generator = com.bettertalker.app.data.planning.OutlineGeneratorImpl(
            ProviderFactory.createFor(remote, app),
        )
        val request = com.bettertalker.app.domain.planning.OutlineGenerationRequest(
            id = newId("nl"),
            theme = inputs.theme,
            totalMinutes = inputs.minutes,
            audience = inputs.audience,
            angle = com.bettertalker.app.domain.planning.OutlineAngle.PRACTICAL,
            bibleRefs = bibleRefs,
            publicationRefs = emptyList(),
            methodPrinciples = emptyList(),
        )
        val proposal = try {
            generator.generate(request)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (proposal == null) {
            postText("Não consegui gerar o discurso. Tente novamente.")
            return
        }
        val validation = com.bettertalker.app.domain.planning.OutlineProposalValidator
            .validate(proposal, allowEmptyRefs = true)
        if (validation is com.bettertalker.app.domain.planning.ValidationResult.Invalid) {
            postText(
                "A estrutura veio inválida (${validation.errors.joinToString(", ")}). Tente novamente."
            )
            return
        }
        val sectionsNow = db.speechSectionDao().forNote(nid).map { it.toDomain() }
        _noteStructureProposal.value = NoteStructureProposalUi(
            id = newId("nsp"),
            noteId = nid,
            title = proposal.title,
            summary = proposal.summary,
            totalMinutes = proposal.totalMinutes,
            sections = proposal.sections,
            hints = emptyList(),
            currentSectionCount = sectionsNow.size,
            noteBaseHash = noteStructureHash(db.noteDao().get(nid)?.title, sectionsNow),
            allowEmptyRefs = true,
        )
        post(
            false,
            "text",
            ChatCodec.escMap(mapOf(
                "text" to "— Estrutura proposta para Necessidades Locais: ${inputs.theme} —",
            )),
        )
    }

    /**
     * #20-fix-a: gera a Tribuna da nota — cápsulas (ideia/âncora/transição)
     * por seção, salvas em `tribunaJson`. Não streama; não passa pelo gate
     * (o texto é para o usuário editar).
     */
    fun generateTribuna() {
        viewModelScope.launch {
            val nid = noteId ?: return@launch
            val remote = ProviderFactory.resolveRemote(settings, app)
            if (!ProviderFactory.useRemoteRoute(remote)) {
                postText("Configure a chave de IA na tela Modelo IA para gerar a Tribuna.")
                return@launch
            }
            val sections = db.speechSectionDao().forNote(nid).map { it.toDomain() }
            if (sections.none { it.contentHtml.isNotBlank() }) {
                postText("As seções ainda estão vazias — desenvolva o discurso antes de gerar a Tribuna.")
                return@launch
            }
            val prompt = com.bettertalker.app.data.copilot.buildTribunaPrompt(sections)
            val provider = ProviderFactory.createFor(
                remote, app, thinkingEffortFor(_thinkingMode.value)
            )
            val res = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    provider.generate(
                        com.bettertalker.app.data.llm.LlmRequest(
                            text = prompt,
                            action = com.bettertalker.app.data.llm.LlmAction.CHAT,
                            message = prompt,
                            maxOutputTokens = 2048,
                        )
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (res == null) {
                postText("Não consegui gerar a Tribuna. Tente novamente.")
                return@launch
            }
            val json = com.bettertalker.app.data.edit.extractJsonFence(res.text) ?: res.text
            val byId = com.bettertalker.app.domain.speech.tribunaCapsulesFromJson(json)
                .associateBy { it.sectionId }
            var saved = 0
            sections.forEach { s ->
                val capsule = byId[s.id] ?: return@forEach
                db.speechSectionDao().upsert(
                    s.copy(
                        tribunaJson = capsule.toJson(),
                        updatedAt = System.currentTimeMillis(),
                    ).toEntity()
                )
                saved++
            }
            if (saved == 0) {
                postText("A resposta da Tribuna veio vazia ou em formato inesperado. Tente novamente.")
            } else {
                post(
                    false,
                    "text",
                    ChatCodec.escMap(mapOf(
                        "text" to "— Tribuna gerada para $saved seções —",
                    )),
                )
            }
        }
    }

    /**
     * F5d: escolhe 1 ângulo — marca o card como resolvido e cria a
     * `NoteStructureProposalUi` correspondente (reuso integral do #5b:
     * stale + persist + contagem real). Revalida antes (defensivo).
     */
    fun chooseMultiProposal(proposalId: String) {
        val ui = _multiProposal.value ?: return
        if (ui.chosen != null) return
        val proposal = ui.proposals.firstOrNull { it.id == proposalId } ?: return
        if (OutlineProposalValidator.validate(proposal) is
            com.bettertalker.app.domain.planning.ValidationResult.Invalid
        ) {
            _multiProposal.value = ui.copy(notice = MULTI_PROPOSAL_INVALID_NOTICE)
            return
        }
        val nid = noteId
        if (nid == null) {
            _multiProposal.value = ui.copy(notice = MULTI_PROPOSAL_NEEDS_NOTE_NOTICE)
            return
        }
        _multiProposal.value = ui.copy(chosen = proposal.id)
        viewModelScope.launch {
            val sectionsNow = db.speechSectionDao().forNote(nid).map { it.toDomain() }
            _noteStructureProposal.value = noteStructureProposalFromChoice(
                ui = ui.copy(chosen = proposal.id),
                proposalId = proposalId,
                noteId = nid,
                currentSectionCount = sectionsNow.size,
                noteBaseHash = noteStructureHash(db.noteDao().get(nid)?.title, sectionsNow),
            ) ?: return@launch
        }
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
        // #10c: escrita ancora em seção — em Discurso inteiro, orienta trocar
        // (cobre recortes e o fluxo anafórico).
        val writeIntent = parseInsertSnippet(text) != null ||
            com.bettertalker.app.data.copilot.ChatRouter.isInsertIntoSpeechCommand(text)
        scopeBlockedNotice(_scopeMode.value, insert = writeIntent, nav = false)?.let {
            postText(it)
            return true
        }
        // #8c (GAP-2): recorte por comando — "insira a opção 2", "insira a 3",
        // "insira o parágrafo 1", "insira de X a Y", "insira só isso".
        val snippet = parseInsertSnippet(text)
        if (snippet != null) {
            val last = lastCopilotText()
            if (last.isBlank()) {
                postText("Não há uma resposta minha recente para inserir. Gere o texto e tente de novo.")
                return true
            }
            val cut = extractSnippet(last, snippet)
            if (cut.isNullOrBlank()) {
                postText("Não encontrei ${snippetLabel(snippet)} na última resposta.")
                return true
            }
            insertTextIntoScope(cut)
            postText(insertConfirmation(currentTopicName()) + " ✔")
            return true
        }
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
        postText(insertConfirmation(topic) + " ✔")
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
            // T2: a ref vai por rótulo ("Jeremias 29:11") — biblePassages
            // resolve por ref exata no índice (Je 29:11).
            val verses = repo.biblePassages("${b.label} ${b.chapter}:${b.verse}", 2)
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

    /**
     * "Me guie" — orienta o fluxo seção-a-seção REAL (substitui o guia
     * legado, que iterava `outlineSections` e não trocava o escopo).
     */
    private suspend fun startGuided() {
        if (guideSections.value.isEmpty()) {
            post(
                false,
                "text",
                ChatCodec.escMap(mapOf(
                    "text" to "Abra um tópico no editor e use a barra do topo " +
                        "(←/→ para navegar, Aprovar para liberar o avanço)."
                )),
            )
            return
        }
        post(
            false,
            "text",
            ChatCodec.escMap(mapOf(
                "text" to "Use a barra do topo: ←/→ para navegar, “Aprovar” para " +
                    "liberar o avanço e “próximo” quando a seção estiver aprovada."
            )),
        )
    }

    /**
     * T4 — composição local. O legado MediaPipe foi removido; a geração
     * acontece pelo provider remoto (DeepSeek, rota unificada). Sem
     * provedor disponível, responde orientação honesta (determinístico).
     */
    private suspend fun answerCompose() {
        if (needOutline()) {
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Vincule um esboço primeiro. Toque no + para importar ou colar.")))
            return
        }
        post(false, "text", ChatCodec.escMap(mapOf(
            "text" to "Para compor texto, configure a chave do DeepSeek na tela Modelo IA.")))
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

    /**
     * Importa DOCX/PDF/JWPUB como esboço.
     *
     * [autoLink] (FAB da Home): além do draft no chat, VINCULA na hora — a
     * nota abre com as seções (Introdução + corpos + Conclusão) no editor. No
     * picker do editor fica false: o usuário revisa o draft no chat e clica
     * "Vincular" quando quiser.
     */
    fun importOutlineFile(uri: Uri, autoLink: Boolean = false) = viewModelScope.launch {
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
            // Paridade com o "Colar e Pronto": Home cria as seções na hora.
            if (autoLink) linkDraft()
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
        // Fase A: normaliza UMA vez e consome o cérebro único (o mesmo do FAB).
        val normalized = PasteTextNormalizer.normalize(text)
        val plan = planPaste(normalized)
        when (val kind = plan.kind) {
            is PasteKind.VmTalk,
            is PasteKind.VmDemo,
            is PasteKind.VmConsideration -> {
                applyVmPastePlan(plan)
                return@launch
            }
            is PasteKind.VmRejected -> {
                postText(plan.notice ?: vmRejectionMessage(kind.parts))
                return@launch
            }
            is PasteKind.S34,
            is PasteKind.FreeText -> Unit
        }
        val (titled, rest) = PastedOutlineAnalyzer.splitTitle(normalized)
        val (cands, droppedCount) = PastedOutlineAnalyzer.candidates(rest.ifBlank { normalized })
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
        _previewRefsJson.value = RefDetector.detectedToJson(RefDetector.detect(normalized))
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
            // F5c: persist devolve o total de seções persistidas (contagem);
            // a validação segue informativa e o EditorViewModel revalida ao
            // observar as seções, mostrando o aviso quando a nota abrir.
            outlineImportService.persist(
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

    // ---------- T1 (voz): STT nativo (SpeechRecognizer, pt-BR) ----------

    private val speechRecognizer: com.bettertalker.app.data.voice.SpeechRecognizerManager by lazy {
        com.bettertalker.app.data.voice.SpeechRecognizerManager(app)
    }

    /** Estado da entrada por voz (Idle/Listening/Processing/Result/Error). */
    val voiceState: StateFlow<com.bettertalker.app.data.voice.VoiceInputState> =
        speechRecognizer.state

    /** Abre o microfone (a UI pede a permissão antes). */
    fun startVoiceInput() = speechRecognizer.start()

    /** Conclui a escuta e pede a transcrição (quadrado-stop). */
    fun finishVoiceInput() = speechRecognizer.finish()

    /** Cancela sem transcrever (botão X). */
    fun cancelVoiceInput() = speechRecognizer.cancel()

    // ---------- T2 (Fase 2): materializar a estrutura ("insira tudo") ----------

    /**
     * Prévia da estrutura emitida pelo modelo, aguardando confirmação.
     * Volátil (não persiste); null = sem prévia pendente.
     */
    data class StructurePreview(
        val sections: List<com.bettertalker.app.data.export.StructureSection>,
        val hasExisting: Boolean,
    )

    private val _structurePreview =
        MutableStateFlow<StructurePreview?>(null)
    val structurePreview: StateFlow<StructurePreview?> = _structurePreview.asStateFlow()

    fun dismissStructurePreview() {
        _structurePreview.value = null
    }

    /**
     * Pede ao modelo a estrutura completa em Markdown canônico e abre a
     * prévia (sem persistir). O histórico — com a memória da construção — é
     * a fonte; no modo colar, o esboço colado COMPLEMENTA o contexto
     * (T1/Fase 2.2). Sem RAG/dossiê.
     */
    private suspend fun requestApplyStructure(history: List<ChatTurn>) {
        val nid = noteId ?: return
        val remote = ProviderFactory.resolveRemote(settings, app)
        if (!ProviderFactory.useRemoteRoute(remote)) {
            postText("Configure a chave de IA na tela Modelo IA para gerar a estrutura.")
            return
        }
        // #streaming-fix: parcial da rota anterior não contamina a estrutura.
        com.bettertalker.app.data.llm.LocalProgress.reset()
        _runState.value = ChatRunState.Generating
        try {
            val provider = com.bettertalker.app.data.llm.ProviderFactory.createFor(remote, app)
            val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                provider.generate(
                    com.bettertalker.app.data.llm.LlmRequest(
                        text = "",
                        action = com.bettertalker.app.data.llm.LlmAction.CHAT,
                        message = com.bettertalker.app.data.copilot.applyStructureMessage(pasteBuffer),
                        history = history,
                        isFirstMessage = false,
                    )
                )
            }
            // #7e: gate de groundedness — o esboço colado é a fonte do gate;
            // o que estiver carregado (dossiê, S-34 e seções atuais) complementa.
            // Limitação: este caminho não monta ContextPack/RAG (reportado).
            val corpus = buildList {
                pasteBuffer?.takeIf { it.isNotBlank() }?.let { add(it) }
                lastDossier?.let {
                    add(dossierPromptBuilder.buildContextBlock(it, includeSpeakerNote = false))
                }
                _lastS34Document?.let { addAll(s34CorpusTexts(it)) }
                db.speechSectionDao().forNote(nid).forEach {
                    add(com.bettertalker.app.data.edit.stripHtmlToText(it.contentHtml))
                }
            }.filter { it.isNotBlank() }
            val verified = verifyGeneratedText(res.text, null, corpus)
            if (verified.metrics.hasFlags) {
                val strictChars = verified.text
                    .removeSuffix(GroundednessVerifier.REMOVAL_NOTICE).length
                android.util.Log.i(
                    "CopilotLLM",
                    "verifier structure: flags=${verified.metrics.summary()} " +
                        "flaggedChars=${verified.metrics.flaggedChars} " +
                        "strictChars=$strictChars",
                )
            }
            // #7e-fix: o aviso do verificador não entra no markdown — é
            // postado como mensagem no chat.
            val (cleanText, removalNotice) = stripRemovalNotice(verified.text)
            val sections = com.bettertalker.app.data.export.parseStructureMarkdown(cleanText)
            if (sections.isEmpty()) {
                postText("Não consegui extrair a estrutura da resposta. Tente de novo.")
                return
            }
            val hasExisting = db.speechSectionDao().forNote(nid).isNotEmpty()
            _structurePreview.value = StructurePreview(sections, hasExisting)
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Estrutura pronta (${sections.size} partes) — revise e confirme no painel.")))
            if (removalNotice != null) {
                postText(removalNotice)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            _runState.value = ChatRunState.Cancelled()
            throw e
        } catch (e: Exception) {
            val code = (e as? com.bettertalker.app.data.llm.ProviderError)?.code
            android.util.Log.e("CopilotLLM", "apply-structure failed " +
                e.javaClass.simpleName + " code=$code")
            val msg = friendlyChatError(e)
            _runState.value = ChatRunState.Error(msg)
            postText(msg)
        } finally {
            _chatBusy.value = false
            if (_runState.value is ChatRunState.Generating) {
                _runState.value = ChatRunState.Success
            }
        }
    }

    /**
     * Confirma a prévia: converte em seções (INTRO/BODY/CONCLUSION) e
     * persiste via `OutlineImportService` (mesmo contrato do `linkDraft`).
     * Sem seções existentes — ou com [replace] — substitui; senão anexa.
     */
    fun applyStructure(replace: Boolean) = viewModelScope.launch {
        val nid = noteId ?: return@launch
        val preview = _structurePreview.value ?: return@launch
        val noteTitle = db.noteDao().get(nid)?.title?.takeIf { it.isNotBlank() } ?: "Discurso"
        try {
            val fresh = com.bettertalker.app.data.export.toOutlineConversion(
                preview.sections, nid, noteTitle,
            )
            val conversion = if (replace || !preview.hasExisting) {
                fresh
            } else {
                val existingSecs = db.speechSectionDao().forNote(nid)
                    .map { it.toDomain() }
                val subMap = existingSecs.associate { s ->
                    s.id to db.subPointDao().forSection(s.id).map { it.toDomain() }
                }
                // T5 (Fase 2.1): corpos com título já existente são pulados —
                // "adicionar ao final" não duplica tópico.
                val pulados = com.bettertalker.app.data.export.countDuplicateBodyTitles(
                    existingSecs, fresh,
                )
                if (pulados > 0) {
                    android.util.Log.i(
                        "CopilotLLM",
                        "apply-structure: $pulados seção(ões) duplicada(s) pulada(s) no adicionar"
                    )
                }
                com.bettertalker.app.data.export.mergeAppend(existingSecs, subMap, fresh)
            }
            outlineImportService.persist(conversion, DiscourseType.S34_DISCOURSE)
            _structurePreview.value = null
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Inserido na nota. Você pode editar livremente.")))
            viewModelScope.launch { refreshReadiness() }
        } catch (_: Exception) {
            post(false, "text", ChatCodec.escMap(mapOf(
                "text" to "Não consegui inserir na nota. Tente de novo.")))
        }
    }

    /** Limpa Result/Error depois de levar o texto ao composer. */
    fun consumeVoiceResult() = speechRecognizer.consume()

    // ---------- T2 (voz): TTS nativo (TextToSpeech, pt-BR) ----------

    private val textToSpeech: com.bettertalker.app.data.voice.VoiceEngine by lazy {
        voiceEngine ?: com.bettertalker.app.data.voice.TextToSpeechManager(app)
    }

    /** TTS pronto (voz pt-BR instalada no aparelho). */
    val ttsReady: StateFlow<Boolean> = textToSpeech.ready

    /** Inicialização do TTS terminou (para o aviso único de voz ausente). */
    val ttsInitialized: StateFlow<Boolean> = textToSpeech.initialized

    /** Leitura em andamento (botão ▶/⏸ do T4). */
    val ttsSpeaking: StateFlow<Boolean> = textToSpeech.speaking

    /** Lê uma resposta em voz alta — sempre o texto PÓS-GATE. */
    fun speakMessage(text: String) {
        textToSpeech.speak(com.bettertalker.app.data.voice.prepareForSpeech(text))
    }

    /** Para a leitura em andamento. */
    fun stopSpeaking() = textToSpeech.stop()

    override fun onCleared() {
        speechRecognizer.destroy()
        textToSpeech.shutdown()
        super.onCleared()
    }

    class Factory(
        private val ctx: android.content.Context,
        private val db: AppDatabase,
        private val noteId: String? = null,
        private val voiceEngine: com.bettertalker.app.data.voice.VoiceEngine? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CopilotViewModel(ctx, db, noteId, voiceEngine) as T
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
 * #15a: sem S-34 o gate duro foi removido — degrada SEMPRE (com ou sem
 * esboço convertido) e o aviso suave [NO_S34_WARNING] entra no fim da
 * resposta. Com S-34, a geração oratória ancorada segue normal.
 *
 * Pura, testável.
 */
internal fun shouldDegradeOratoryToChat(hasS34Document: Boolean): Boolean = !hasS34Document

/**
 * #15d-escopo: a sugestão de vincular publicações é legítima quando o RAG
 * veio fraco (< MIN_RELEVANT_HITS relevantes), a nota NÃO tem anexos
 * indexados e existe publicação indexada não vinculada — há o que vincular
 * e vincular ajudaria. Pura/testável.
 */
internal fun shouldSuggestScope(
    relevantCount: Int,
    hasNoteAttachments: Boolean,
    hasUnlinkedIndexed: Boolean,
): Boolean = relevantCount < MIN_RELEVANT_HITS && !hasNoteAttachments && hasUnlinkedIndexed

/**
 * Inversão de contexto (correção): a prosa em foco que vai ao LLM.
 *
 * Sempre a prosa do bloco, com ou sem alvo empurrado — o dossiê
 * (metadados) viaja em `contextBlock`, nunca no lugar da prosa.
 * Vazio entra vazio (nunca inventa); nunca zera prosa existente.
 *
 * Pura, testável.
 */
internal fun focusTextForLlm(blockText: String): String = blockText

// ---------- Modo guiado seção-a-seção: navegação pura ----------

/** Direção da navegação entre seções reais do editor. */
enum class GuideNavDirection { NEXT, PREV }

/**
 * Destino decidido para um comando de navegação. Puro/testável.
 */
sealed interface GuideNavTarget {
    /** Troca o escopo para a seção indicada. */
    data class MoveTo(val sectionId: String) : GuideNavTarget
    /** NEXT na última seção. */
    data object NoNext : GuideNavTarget
    /** PREV na primeira (ou sem foco). */
    data object NoPrev : GuideNavTarget
    /** Sem seções na nota. */
    data object NoSections : GuideNavTarget
}

const val GUIDE_NO_NEXT_MESSAGE = "Não há próxima seção."
const val GUIDE_NO_PREV_MESSAGE = "Não há seção anterior."

/** #10c: escopo do chat — Tópico (default) ou Discurso inteiro. */
enum class ScopeMode { SECTION, WHOLE }

/** #10c: avisos do escopo Discurso inteiro (escrita/navegação pedem tópico). */
const val SCOPE_WHOLE_INSERT_NOTICE = "Mude o escopo para Tópico para inserir no card."
const val SCOPE_WHOLE_NAV_NOTICE = "Mude o escopo para Tópico para navegar entre seções."

/**
 * #10c: alvo do retorno WHOLE→SECTION — último foco válido ou 1ª seção da
 * nota; null = sem seções (permanece em WHOLE). Puro/testável.
 */
internal fun scopeRestoreTarget(lastFocused: String?, sectionIds: List<String>): String? =
    lastFocused?.takeIf { it in sectionIds } ?: sectionIds.firstOrNull()

/**
 * #10c: aviso quando o escopo WHOLE bloqueia a ação; null = segue normal.
 * Puro/testável.
 */
internal fun scopeBlockedNotice(mode: ScopeMode, insert: Boolean, nav: Boolean): String? = when {
    mode != ScopeMode.WHOLE -> null
    insert -> SCOPE_WHOLE_INSERT_NOTICE
    nav -> SCOPE_WHOLE_NAV_NOTICE
    else -> null
}
const val GUIDE_NO_FOCUS_MESSAGE = "Abra um tópico no editor para navegar entre seções."

/**
 * Decide o destino: NEXT avança (sem gate — #25-fix-c), PREV sempre permite;
 * sem foco, NEXT começa pela primeira. `orderedIds` na ordem do editor.
 * Puro/testável.
 */
internal fun resolveNavTarget(
    orderedIds: List<String>,
    currentId: String?,
    direction: GuideNavDirection,
): GuideNavTarget {
    if (orderedIds.isEmpty()) return GuideNavTarget.NoSections
    val idx = orderedIds.indexOf(currentId)
    return when (direction) {
        GuideNavDirection.NEXT -> {
            if (idx < 0) return GuideNavTarget.MoveTo(orderedIds.first())
            if (idx + 1 >= orderedIds.size) return GuideNavTarget.NoNext
            GuideNavTarget.MoveTo(orderedIds[idx + 1])
        }
        GuideNavDirection.PREV -> {
            if (idx - 1 < 0) return GuideNavTarget.NoPrev
            GuideNavTarget.MoveTo(orderedIds[idx - 1])
        }
    }
}

/** Anúncio de troca de escopo postado na conversa nova. Puro/testável. */
internal fun guideAnnounceLabel(index: Int, total: Int, title: String, minutes: Int): String =
    "— Tópico ${index + 1} de $total: $title ($minutes min) —"

private fun normalizeNavText(text: String): String =
    " ${com.bettertalker.app.data.util.normalizeText(text)} "

/** "próximo/próxima/seguinte/avançar" (o `force` se decide à parte). Puro/testável. */
internal fun isNextCommand(text: String): Boolean {
    val t = normalizeNavText(text)
    return listOf("proximo", "proxima", "seguinte", "avancar").any { t.contains(it) }
}

/** "anterior"/"voltar"/"volta". Puro/testável. */
internal fun isPrevCommand(text: String): Boolean {
    val t = normalizeNavText(text)
    return listOf("anterior", "voltar", "volta").any { t.contains(it) }
}

/**
 * "aprovar"/"aprovado"/"ok, aprovado" — mas nunca "desaprovar".
 * Puro/testável.
 */
internal fun isApproveCommand(text: String): Boolean {
    val t = normalizeNavText(text)
    return t.contains("aprov") && !t.contains("desaprov")
}

/**
 * T2 — prioridade unificada do foco: push > seleção > bloco > discurso.
 *
 * O alvo empurrado pelo editor vence os canais legados (que podem apontar
 * para outro tópico ou nada). Sem push, vale a ordem antiga. Pura, testável.
 */
/**
 * #14-fix: campos de foco EFETIVOS do turno — em WHOLE (Discurso inteiro) o
 * bloco ativo e a seleção do editor NÃO valem (o foco é a nota inteira);
 * em SECTION, passam intactos. Puro/testável.
 */
internal fun effectiveFocusFields(
    whole: Boolean,
    activeBlockTitle: String?,
    selection: String,
): Pair<String?, String> = if (whole) null to "" else activeBlockTitle to selection

internal fun pushedFirstFocus(
    pushedTitle: String?,
    selection: String,
    activeBlockTitle: String?,
    noteBody: String,
): String = when {
    !pushedTitle.isNullOrBlank() -> pushedTitle
    selection.isNotEmpty() -> selection
    activeBlockTitle != null -> activeBlockTitle
    else -> noteBody
}

/**
 * T4 — hint estrutural do turno: o tópico empurrado manda sobre o bloco
 * ativo/seleção. Sem push, vale o legado. Pura, testável.
 */
internal fun structuralHint(
    pushedTitle: String?,
    activeBlockTitle: String?,
    selection: String,
): String? = pushedTitle?.takeIf { it.isNotBlank() } ?: activeBlockTitle ?: selection

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
 * #16-fix-b: bloco do foco da oratória. Quando o foco é uma seção, o LLM
 * recebe o bloco REAL (título + contentHtml) — antes ia só o título
 * (`pushedFirstFocus`) e a prosa escrita/editada nunca chegava ao modelo
 * (bug do T8 no teste #16). Sem seção, mantém o foco resolvido. O valor
 * satisfaz a invariante do aceite (`sectionProposalBlock.contains(this)`).
 * Puro/testável.
 */
internal fun oratoryFocusBlock(
    sectionTitle: String?,
    sectionContentHtml: String?,
    fallbackFocus: String,
): String = if (sectionTitle.isNullOrBlank()) {
    fallbackFocus
} else {
    sectionProposalBlock(sectionTitle, sectionContentHtml.orEmpty())
}

// ---------- FALTA-2: proposta de estrutura da seção (pura) ----------

/** Aviso quando a seção mudou depois da proposta (nada é aplicado). */
const val STRUCTURE_STALE_NOTICE = "Proposta obsoleta — o texto mudou desde que foi gerada."

/** Aviso quando a proposta é rejeitada (nada é escrito). */
const val STRUCTURE_REJECTED_NOTICE = "Proposta rejeitada — nada foi alterado."

/** Erro quando o foco não é uma seção (sub-ponto ou chat genérico). */
const val STRUCTURE_NEEDS_SECTION_MESSAGE =
    "Para propor estrutura, foque numa seção (não num sub-ponto)."

/**
 * Erro de alvo para `proposeStructureFor`: null = foco válido em seção.
 * Puro/testável.
 */
internal fun structureTargetError(target: DraftTarget?): String? =
    if (target is DraftTarget.Section) null else STRUCTURE_NEEDS_SECTION_MESSAGE

/**
 * Aceite da proposta de estrutura: MESMA regra relaxada usada na criação
 * (#8f/paridade). A flag vem da UI da proposta — nunca reavaliar do zero.
 * Puro/testável.
 */
internal fun validateStructureAccept(
    proposal: com.bettertalker.app.domain.planning.OutlineProposal,
    allowEmptyRefs: Boolean,
): Boolean = com.bettertalker.app.domain.planning.OutlineProposalValidator
    .validate(proposal, allowEmptyRefs = allowEmptyRefs) is
    com.bettertalker.app.domain.planning.ValidationResult.Valid

/** Overload para a proposta de seção (flag embutida na UI). */
internal fun validateStructureAccept(ui: CopilotViewModel.StructureProposalUi): Boolean =
    validateStructureAccept(ui.proposal, ui.allowEmptyRefs)

/**
 * Falha do validador em `proposeStructureFor`: com refs no request é drift
 * do LLM (retry ajuda); sem refs é determinístico — orienta em vez de mentir.
 * Puro/testável.
 */
internal fun structureFailureMessage(hasRefs: Boolean): String =
    if (hasRefs) {
        "A estrutura veio incompleta. Tente novamente."
    } else {
        "A seção não tem referências bíblicas; a estrutura veio sem refs. " +
            "Adicione refs ou revise manualmente."
    }

/**
 * Monta o pedido de estrutura a partir da seção em foco (+ princípios do
 * dossiê, se houver). Escopo por seção: `totalMinutes` = minutos da
 * própria seção; as refs da seção viram o conjunto autorizado (o prompt
 * e o parser exigem subconjunto — nada inventado). Puro/testável.
 */
internal fun buildStructureRequest(
    section: SpeechSection,
    methodPrinciples: List<String> = emptyList(),
    id: String = newId("sgen"),
    subPoints: List<SubPoint> = emptyList(),
): OutlineGenerationRequest {
    // E2E (device): no nível da seção, as refs do esboço vivem nos
    // SUB-PONTOS — agregar (ordem de aparição, trim, dedupe), MESMO padrão
    // do DossierBuilder. Sem isto o validador barra a proposta do #3c
    // ("A estrutura veio incompleta") em qualquer seção criada pelo app.
    val subs = subPoints.sortedBy { it.order }
    val bible = (section.bibleRefs + subs.flatMap { it.bibleRefs })
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
    val pubs = (section.publicationRefs + subs.flatMap { it.publicationRefs })
        .filter { it.symbol.isNotBlank() }
        .distinct()
    return OutlineGenerationRequest(
        id = id,
        theme = section.title,
        totalMinutes = section.minutes,
        audience = Audience.GENERAL,
        angle = OutlineAngle.PRACTICAL,
        bibleRefs = bible,
        publicationRefs = pubs,
        methodPrinciples = methodPrinciples,
    )
}

/**
 * Escolhe o plano da seção focada dentro da proposta: melhor casamento de
 * título (normalizado, contém) e, sem casamento, o primeiro. Null = sem
 * seções (proposta vazia — o validador já barraria). Puro/testável.
 */
internal fun pickSectionPlan(proposal: OutlineProposal, sectionTitle: String): SectionPlan? {
    if (proposal.sections.isEmpty()) return null
    val want = sectionTitle.trim().lowercase()
    return proposal.sections.firstOrNull {
        it.title.trim().lowercase().isNotBlank() &&
            (it.title.trim().lowercase().contains(want) || want.contains(it.title.trim().lowercase()))
    } ?: proposal.sections.first()
}

/**
 * Aplica o plano aprovado na seção: objetivo (= ideia central), minutos e
 * refs (merge com dedupe); abordagem concatena com `\n\n`. `approvedAt`
 * e o resto passam intactos — aprovação é passo separado (#3a).
 * Puro/testável.
 */
internal fun applyStructurePlan(
    section: SpeechSection,
    plan: SectionPlan,
    summary: String,
    now: Long = System.currentTimeMillis(),
): SpeechSection {
    val bible = (section.bibleRefs + plan.bibleRefs).distinct()
    val pubs = (section.publicationRefs + plan.publicationRefs).distinct()
    val current = section.agreedApproach
    // E: mesmo merge do consenso do chat — dedupe + teto de 500 chars
    // (o campo não pode crescer sem limite a cada aplicação).
    val merged = com.bettertalker.app.data.copilot.mergeApproachText(current, summary)
    val approach = merged ?: current?.takeIf { it.isNotBlank() }
    return section.copy(
        objective = plan.mainIdea,
        minutes = plan.minutes,
        bibleRefs = bible,
        publicationRefs = pubs,
        agreedApproach = approach,
        updatedAt = now,
    )
}

/** Decisão do aceite: aplica ou barra por stale. Puro/testável. */
sealed interface StructureAcceptDecision {
    data class Apply(val updated: SpeechSection) : StructureAcceptDecision
    data object Stale : StructureAcceptDecision
}

/**
 * Decide o aceite: hash da seção atual vs. hash da criação. Revalidação
 * do `proposal` acontece no chamador (precisa do validate).
 * Puro/testável.
 */
internal fun decideStructureAccept(
    current: SpeechSection,
    sectionBaseHash: String,
    plan: SectionPlan,
    summary: String,
    now: Long = System.currentTimeMillis(),
): StructureAcceptDecision {
    if (hashText(sectionProposalBlock(current.title, current.contentHtml)) != sectionBaseHash) {
        return StructureAcceptDecision.Stale
    }
    return StructureAcceptDecision.Apply(applyStructurePlan(current, plan, summary, now))
}

// ---------- F5b (Modo C): estruturar texto colado de publicação (pura) ----------

/** Foco inválido (sub-ponto ou nada): orienta a focar uma seção. */
const val STRUCTURE_PASTED_NEEDS_SECTION_MESSAGE =
    "Foque numa seção com o texto colado para estruturar."

/** Seção em foco sem texto colado (o comando confirma; o conteúdo não existe). */
const val STRUCTURE_PASTED_EMPTY_MESSAGE =
    "Cole o texto de uma publicação numa seção e foque nela antes de pedir."

/** Duração total default do esboço estruturado (o usuário ajusta depois). */
const val STRUCTURE_PASTED_TOTAL_MINUTES = 30

/** Aviso quando a nota mudou depois da proposta (nada é aplicado). */
const val NOTE_STRUCTURE_STALE_NOTICE = "Proposta obsoleta — o texto da nota mudou."

/** Aviso quando a proposta é rejeitada (nada é escrito). */
const val NOTE_STRUCTURE_REJECTED_NOTICE = "Proposta rejeitada — nada foi alterado."

const val NOTE_STRUCTURE_INVALID_NOTICE = "A estrutura ficou inválida. Gere novamente."

const val NOTE_STRUCTURE_FAILED_NOTICE = "Não consegui aplicar a estrutura. Tente novamente."

/** Erro de conteúdo vazio para o comando: null = há texto. Puro/testável. */
internal fun structurePastedEmptyError(sourceText: String): String? =
    if (sourceText.isBlank()) STRUCTURE_PASTED_EMPTY_MESSAGE else null

/** Erro de alvo para `structureFromPasted`: null = foco válido em seção. Puro. */
internal fun structurePastedTargetError(target: DraftTarget?): String? =
    if (target is DraftTarget.Section) null else STRUCTURE_PASTED_NEEDS_SECTION_MESSAGE

/** F5c: aviso de aceite com a contagem REAL de seções persistidas. Puro. */
internal fun structureAppliedNotice(totalSections: Int): String =
    "Estrutura aplicada. $totalSections seções criadas."

/**
 * F5c: o dossiê deve repetir a ORIENTAÇÃO DO ORADOR? Não quando o bloco
 * estrutural do S-34 já a carrega (a mesma instrução não aparece em dois
 * blocos do mesmo prompt). Sem bloco estrutural — ou sem nota — o dossiê
 * mantém a orientação. Puro/testável.
 */
internal fun dossierSpeakerNoteIncluded(structural: OutlineStructureContext?): Boolean =
    structural?.speakerNote.isNullOrBlank()

// ---------- F5d (Modo B): ângulos para um discurso do zero (pura) ----------

/** Duração total default dos ângulos (o usuário ajusta na aplicação). */
const val ANGLES_TOTAL_MINUTES = 30

/** Pedido sem tema: o chat pergunta o tema em vez de gerar às cegas. */
const val ANGLES_NEEDS_THEME_MESSAGE =
    "Sobre qual tema? Diga 'me dá opções para um discurso sobre <tema>'."

/** Propositor sem nenhuma proposta válida (LLM/retrieval). */
const val ANGLES_EMPTY_MESSAGE =
    "Não consegui gerar ângulos para este tema. Tente reformular."

const val MULTI_PROPOSAL_INVALID_NOTICE = "Essa opção ficou inválida. Gere novamente."

const val MULTI_PROPOSAL_NEEDS_NOTE_NOTICE =
    "Abra uma nota para escolher e aplicar uma estrutura."

/** Resultado do pedido de ângulos: UI pronta ou erro para o chat. */
sealed interface AnglesOutcome {
    data class Ui(val ui: CopilotViewModel.MultiProposalUi) : AnglesOutcome
    data class Error(val message: String) : AnglesOutcome
}

/**
 * F5d: decide o resultado do propose() — tema ausente ou 0 válidas viram
 * mensagem no chat; caso contrário, `MultiProposalUi` pendente.
 * Puro/testável.
 */
internal fun buildAnglesOutcome(
    theme: String?,
    proposals: List<OutlineProposal>,
    id: String = newId("mprop"),
): AnglesOutcome {
    val clean = theme?.trim().orEmpty()
    if (clean.isBlank()) return AnglesOutcome.Error(ANGLES_NEEDS_THEME_MESSAGE)
    if (proposals.isEmpty()) return AnglesOutcome.Error(ANGLES_EMPTY_MESSAGE)
    return AnglesOutcome.Ui(
        CopilotViewModel.MultiProposalUi(id = id, theme = clean, proposals = proposals),
    )
}

/**
 * F5d: converte a escolha do card em `NoteStructureProposalUi` — MESMO
 * contrato/aceite do #5b (nada de lógica duplicada). Null = id inexistente.
 * Puro/testável.
 */
internal fun noteStructureProposalFromChoice(
    ui: CopilotViewModel.MultiProposalUi,
    proposalId: String,
    noteId: String,
    currentSectionCount: Int,
    noteBaseHash: String,
    id: String = newId("nsp"),
): CopilotViewModel.NoteStructureProposalUi? {
    val proposal = ui.proposals.firstOrNull { it.id == proposalId } ?: return null
    return CopilotViewModel.NoteStructureProposalUi(
        id = id,
        noteId = noteId,
        title = proposal.title,
        summary = proposal.summary,
        totalMinutes = proposal.totalMinutes,
        sections = proposal.sections,
        hints = listOf("opção escolhida (${proposal.angle})"),
        currentSectionCount = currentSectionCount,
        noteBaseHash = noteBaseHash,
    )
}

/**
 * F5b: monta o pedido de estruturação do texto colado. Refs bíblicas e de
 * publicação são EXTRAÍDAS do próprio conteúdo (mesmo formato canônico do
 * `OutlineConverter`) — o conjunto autorizado não inventa nada. O tema é o
 * título atual da seção (o LLM deriva o título real do conteúdo).
 * Puro/testável.
 */
internal fun buildStructureFromPastedRequest(
    section: SpeechSection,
    sourceContent: String,
    id: String = newId("sgen"),
    // #8b: hint V&M ("TALK:10min") — natureza + duração no prompt.
    materialNature: String? = null,
): OutlineGenerationRequest {
    val bible = RefDetector.detectBible(sourceContent).map {
        val abbrev = normalizeBibleBookName(it.label) ?: it.label
        "$abbrev ${it.chapter}:${it.verse}"
    }.distinct()
    val pubs = RefDetector.detect(sourceContent).map {
        PublicationRef(
            symbol = canonicalPublicationSymbol(it.pubKey, it.editionKey),
            page = it.page,
            paragraph = it.paragraph,
            article = it.article,
            chapter = it.chapter,
        )
    }.distinct()
    return OutlineGenerationRequest(
        id = id,
        theme = section.title,
        // #8b: material V&M tem duração própria (o hint manda no prompt).
        totalMinutes = materialMinutesOf(materialNature) ?: STRUCTURE_PASTED_TOTAL_MINUTES,
        audience = Audience.GENERAL,
        angle = OutlineAngle.PRACTICAL,
        bibleRefs = bible,
        publicationRefs = pubs,
        methodPrinciples = emptyList(),
        sourceContent = sourceContent,
        materialNature = materialNature,
    )
}

/** F5b: hash da nota inteira (título + seções) para o gate de stale. Puro. */
internal fun noteStructureHash(noteTitle: String?, sections: List<SpeechSection>): String =
    hashText(
        noteTitle.orEmpty().trim() + "\n" +
            sections.joinToString("\n\n") { sectionProposalBlock(it.title, it.contentHtml) },
    )

/** F5b: reconstrói a proposta completa a partir da UI (validar/converter). Puro. */
internal fun noteProposalOf(ui: CopilotViewModel.NoteStructureProposalUi): OutlineProposal =
    OutlineProposal(
        id = ui.id,
        angle = OutlineAngle.PRACTICAL,
        audience = Audience.GENERAL,
        title = ui.title,
        summary = ui.summary,
        totalMinutes = ui.totalMinutes,
        sections = ui.sections,
    )

/**
 * F5b: corpo de 1 seção do plano — vira sub-pontos/refs no converter
 * (mesma mecânica do esboço importado). Puro/testável.
 */
internal fun sectionPlanBody(plan: SectionPlan): String = buildString {
    if (plan.mainIdea.isNotBlank()) append(plan.mainIdea.trim())
    plan.bibleRefs.forEach {
        if (isNotEmpty()) append('\n')
        append("[Leia $it.]")
    }
    plan.publicationRefs.forEach { r ->
        if (isNotEmpty()) append('\n')
        append("(${r.symbol}${r.page?.let { p -> " p. $p" } ?: ""})")
    }
}

/**
 * F5b: converte a proposta de NOTA em [OutlineConversion] reusando
 * [buildOutlineConversion] (converter existente: intro/conclusão
 * sintetizadas no caminho S-34; sub-pontos extraídos do corpo).
 * Puro/testável.
 */
internal fun proposalToConversion(
    proposal: OutlineProposal,
    noteId: String,
    converter: OutlineConverter = OutlineConverter(),
): OutlineConversion {
    val sections = proposal.sections.mapIndexed { i, plan ->
        OutlineSection(
            title = plan.title,
            minutes = plan.minutes,
            order = i,
            body = sectionPlanBody(plan),
        )
    }
    return buildOutlineConversion(
        title = proposal.title,
        totalMinutes = proposal.totalMinutes,
        sections = sections,
        preamble = "",
        noteId = noteId,
        converter = converter,
    )
}

/** Decisão do aceite da proposta de nota: aplica, invalida ou barra por stale. */
sealed interface NoteStructureAcceptDecision {
    data class Apply(val conversion: OutlineConversion) : NoteStructureAcceptDecision
    data object Stale : NoteStructureAcceptDecision
    data object Invalid : NoteStructureAcceptDecision
}

/**
 * F5b: decide o aceite — revalida a proposta e compara o hash atual da nota
 * com o da criação. Só [NoteStructureAcceptDecision.Apply] escreve (via
 * `persist` no chamador). Puro/testável.
 */
internal fun decideNoteStructureAccept(
    ui: CopilotViewModel.NoteStructureProposalUi,
    currentHash: String,
    converter: OutlineConverter = OutlineConverter(),
): NoteStructureAcceptDecision {
    val proposal = noteProposalOf(ui)
    if (!validateStructureAccept(proposal, ui.allowEmptyRefs)) {
        return NoteStructureAcceptDecision.Invalid
    }
    if (currentHash != ui.noteBaseHash) return NoteStructureAcceptDecision.Stale
    return NoteStructureAcceptDecision.Apply(proposalToConversion(proposal, ui.noteId, converter))
}

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
    // #16-fix-c: o texto aceito não carrega marcadores internos
    // (〈sugestão〉/〈decisão〉): o conteúdo fica, os marcadores saem —
    // preservando o HTML (stripPromptTags genérico comeria os <p>).
    return stripInternalTags(applied.first.removePrefix(title).trimStart('\n', ' ', '\t'))
}

/**
 * #26-fix — trecho (HTML) que o botão primário ADICIONA à seção: o conteúdo
 * proposto pelas ops. O resultado completo (`renderAfterText`/
 * `applyProposalToSectionBlock`) já inclui o texto antigo nos Insert e
 * DUPLICARIA no append; aqui é só o delta. Delete-only → vazio. Puro/testável.
 */
internal fun proposedAdditionContent(proposal: CopilotEditProposal): String =
    proposal.operations.mapNotNull { op ->
        when (op) {
            is EditOperation.Insert -> op.contentHtml
            is EditOperation.Replace -> op.contentHtml
            is EditOperation.Delete -> null
        }
    }.joinToString("\n").trim()

/**
 * #26-fix — conteúdo final da seção no aceite: seção vazia → proposto;
 * com conteúdo + botão padrão → antigo + "\n\n" + proposto (append, NÃO
 * substitui); `forceReplace` (⋯ "Substituir a seção") → resultado completo
 * das operações (comportamento clássico). Proposto vazio (delete-only) →
 * completo. Puro/testável.
 */
internal fun proposalApplyContent(
    oldContentHtml: String,
    proposedContentHtml: String,
    fullContentHtml: String,
    forceReplace: Boolean,
): String = when {
    forceReplace -> fullContentHtml
    proposedContentHtml.isBlank() -> fullContentHtml
    oldContentHtml.isBlank() -> proposedContentHtml
    else -> oldContentHtml.trimEnd() + "\n\n" + proposedContentHtml
}

/** #26-fix: rótulo do botão primário (vazia = usar; com conteúdo = adicionar). Puro/testável. */
internal fun proposalButtonLabel(sectionContentHtml: String): String =
    if (sectionContentHtml.isBlank()) "Usar este texto" else "Adicionar ao final"

/**
 * #26-fix: estado do card após o aceite — marca `applied` SEM tocar no
 * `notice` (o aviso de fidelidade, ex.: "removi uma frase…", permanece
 * visível depois de aplicar). Puro/testável.
 */
internal fun appliedProposalUi(ui: CopilotViewModel.ProposalUi): CopilotViewModel.ProposalUi =
    ui.copy(applied = true)
