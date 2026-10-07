package com.bettertalker.app.domain.planning

import com.bettertalker.app.domain.planning.retrieval.MethodIndex
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.SubPoint
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Contexto puro de seleção. Desacoplado do `SelectionContext` da UI
 * (`ui/editor/EditorContracts.kt`) — o VM faz a conversão.
 */
data class SelectionContextContract(
    val sectionId: String,
    val subPointId: String?,
    val selectedText: String,
    val fullContentHtml: String,
)

/**
 * Par seção + sub-pontos. Necessário porque `SpeechSection` não carrega
 * sub-pontos (entidades separadas).
 */
data class SectionWithSubPoints(
    val section: SpeechSection,
    val subPoints: List<SubPoint>,
)

/**
 * Monta o dossiê de uma seção/sub-ponto para o Copilot.
 *
 * Puro (só depende de abstrações). Sem LLM. Sem persistência.
 *
 * BODY: refs literais + be/th + metadados da seção.
 * INTRO/CONCLUSION: na 3.5b.2.
 */
interface DossierBuilder {
    suspend fun build(
        context: SelectionContextContract,
        document: List<SectionWithSubPoints>,
    ): Dossier
}

/**
 * Implementação default. Pura (injeção de [ReferenceResolver] + [MethodIndex]).
 *
 * Paralelismo: N refs em paralelo (async/awaitAll) — evita jank.
 *
 * Degradação graciosa: acervo vazio → status UNRESOLVED em todas;
 * `unresolvedRefs` populado. Nunca lança (exceto seção inexistente,
 * que é erro de programação do caller).
 */
class DefaultDossierBuilder(
    private val referenceResolver: ReferenceResolver,
    private val methodIndex: MethodIndex,
    private val methodLimit: Int = 5,
) : DossierBuilder {

    override suspend fun build(
        context: SelectionContextContract,
        document: List<SectionWithSubPoints>,
    ): Dossier {
        val currentState = document.firstOrNull {
            it.section.id == context.sectionId
        } ?: error("Seção não encontrada: ${context.sectionId}")

        val currentSection = currentState.section
        val currentSubPoint = context.subPointId?.let { id ->
            currentState.subPoints.firstOrNull { it.id == id }
        }

        // overview: metadados de todas as seções (sem conteúdo integral;
        // F2.3: recorte curto + objetivo para a visão global de INTRO/CONCLUSION)
        val overview = document.map { state ->
            SectionMeta(
                id = state.section.id,
                title = state.section.title,
                role = state.section.role,
                order = state.section.order,
                minutes = state.section.minutes,
                objective = state.section.objective?.takeIf { it.isNotBlank() },
                snippet = sectionSnippet(state),
            )
        }

        // Refs: do sub-ponto (BODY) OU da seção (INTRO/CONCLUSION ou cursor no header)
        val bibleRefs = currentSubPoint?.bibleRefs ?: currentSection.bibleRefs
        val pubRefs = currentSubPoint?.publicationRefs ?: currentSection.publicationRefs

        // Resolve em paralelo
        val bibleTexts = coroutineScope {
            bibleRefs.map { ref ->
                async { toResolvedBible(referenceResolver.resolveBible(ref)) }
            }.awaitAll()
        }
        val publicationTexts = coroutineScope {
            pubRefs.map { ref ->
                async { toResolvedPublication(referenceResolver.resolvePublication(ref)) }
            }.awaitAll()
        }

        // Princípios be/th: texto livre com hint por role + categoria (3.5c)
        val contextText = buildMethodContext(currentSection, currentSubPoint)
        val principles = if (contextText.isBlank()) emptyList()
        else methodIndex.findPrinciples(
            context = contextText,
            limit = methodLimit,
            category = methodCategoryFor(currentSection.role),
        )

        // Transição: só para INTRO/CONCLUSION
        val transitionContext = when (currentSection.role) {
            SectionRole.BODY -> null
            SectionRole.INTRO -> buildIntroTransition(document)
            SectionRole.CONCLUSION -> buildConclusionRecap(document)
        }

        // unresolvedRefs: só UNRESOLVED + MISSING_CORPUS
        val unresolved = buildList {
            bibleTexts.forEach { rt ->
                if (rt.status == ReferenceStatus.UNRESOLVED ||
                    rt.status == ReferenceStatus.MISSING_CORPUS
                ) add(rt.ref)
            }
            publicationTexts.forEach { rt ->
                if (rt.status == ReferenceStatus.UNRESOLVED ||
                    rt.status == ReferenceStatus.MISSING_CORPUS
                ) add(rt.ref.symbol)
            }
        }

        return Dossier(
            currentSection = currentSection,
            currentSubPoint = currentSubPoint,
            selectedText = context.selectedText.takeIf { it.isNotBlank() },
            fullContentHtml = context.fullContentHtml,
            overview = overview,
            bibleTexts = bibleTexts,
            publicationTexts = publicationTexts,
            methodPrinciples = principles,
            unresolvedRefs = unresolved,
            transitionContext = transitionContext,
            sectionSubPoints = currentState.subPoints,
        )
    }

    /**
     * F2.3: recorte curto do texto já desenvolvido de uma seção, para a
     * visão global de INTRO/CONCLUSION. BODY prefere `contentHtml` (mini
     * discurso do tópico) e cai para os sub-pontos desenvolvidos (legado).
     */
    private fun sectionSnippet(state: SectionWithSubPoints): String? {
        val raw = if (state.section.contentHtml.isNotBlank()) {
            state.section.contentHtml
        } else {
            state.subPoints.sortedBy { it.order }
                .map { it.developedHtml }
                .filter { it.isNotBlank() }
                .joinToString(" ")
        }
        val text = stripHtml(raw)
        if (text.isBlank()) return null
        return if (text.length <= SectionMeta.SNIPPET_MAX) text
        else text.take(SectionMeta.SNIPPET_MAX).trimEnd() + "…"
    }

    private fun toResolvedBible(r: ResolvedReference) = ResolvedBibleText(
        ref = r.canonicalRef ?: r.original,
        text = r.text,
        status = r.status,
    )

    private fun toResolvedPublication(r: ResolvedReference) = ResolvedPublicationText(
        // T6: usa o rótulo resolvido ("it “Gedalias” §4") quando houver — o
        // prompt mostra a citação específica, não só o símbolo.
        ref = PublicationRef(symbol = r.canonicalRef ?: r.original, page = null, paragraph = null),
        text = r.text,
        status = r.status,
    )

    /** Contexto textual para o MethodIndex (hint por role + outline/instrução). */
    private fun buildMethodContext(
        section: SpeechSection,
        subPoint: SubPoint?,
    ): String {
        val roleHint = when (section.role) {
            SectionRole.INTRO -> "introdução"
            SectionRole.CONCLUSION -> "conclusão"
            SectionRole.BODY -> null
        }
        return buildString {
            if (roleHint != null) append(roleHint).append(' ')
            if (subPoint != null) {
                append(subPoint.outlineText)
                subPoint.instruction?.let { append(' ').append(it) }
                // NÃO inclui developedHtml — o usuário já escreveu
            } else {
                append(section.title)
                if (section.contentHtml.isNotBlank()) {
                    append(' ').append(section.contentHtml)
                }
            }
        }.trim()
    }

    /**
     * Categoria serial (TrainingCategory) para o role da seção.
     * Refinamento (questions/delivery) fica para 3.5c.3.
     */
    private fun methodCategoryFor(role: SectionRole): String = when (role) {
        SectionRole.INTRO -> "introduction"
        SectionRole.BODY -> "development"
        SectionRole.CONCLUSION -> "conclusion"
    }

    /**
     * Transição para a INTRO: primeiros TRANSITION_SUB_POINTS sub-pontos
     * desenvolvidos do primeiro BODY (por order).
     */
    private fun buildIntroTransition(
        document: List<SectionWithSubPoints>,
    ): String? {
        val firstBody = document
            .filter { it.section.role == SectionRole.BODY }
            .minByOrNull { it.section.order }
            ?: return null
        val developed = firstBody.subPoints
            .filter { it.developedHtml.isNotBlank() }
            .sortedBy { it.order }
            .take(TRANSITION_SUB_POINTS)
        if (developed.isEmpty()) return null
        return joinDevelopedSubPoints(developed)
    }

    /**
     * Recap para a CONCLUSION: últimos TRANSITION_SUB_POINTS sub-pontos
     * desenvolvidos do último BODY (por order).
     */
    private fun buildConclusionRecap(
        document: List<SectionWithSubPoints>,
    ): String? {
        val lastBody = document
            .filter { it.section.role == SectionRole.BODY }
            .maxByOrNull { it.section.order }
            ?: return null
        val developed = lastBody.subPoints
            .filter { it.developedHtml.isNotBlank() }
            .sortedBy { it.order }
            .takeLast(TRANSITION_SUB_POINTS)
        if (developed.isEmpty()) return null
        return joinDevelopedSubPoints(developed)
    }

    /**
     * Junta sub-pontos desenvolvidos como "outlineText: texto".
     * Se outlineText for blank, cai para só o texto (evita ": texto").
     */
    private fun joinDevelopedSubPoints(subPoints: List<SubPoint>): String =
        subPoints.joinToString("\n\n") { sp ->
            val text = stripHtml(sp.developedHtml).take(TRANSITION_CHAR_LIMIT)
            val outline = sp.outlineText.trim()
            if (outline.isBlank()) text
            else "$outline: $text"
        }

    /**
     * Remove tags HTML e colapsa espaços. Local (domain puro — não importa
     * data/util.TextNorm).
     */
    private fun stripHtml(html: String): String =
        html.replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private companion object {
        const val TRANSITION_SUB_POINTS = 2
        const val TRANSITION_CHAR_LIMIT = 400
    }
}
