package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.domain.TrainingCategory
import com.bettertalker.app.data.s34.S34Document
import com.bettertalker.app.data.s34.S34StructuralRetrieval

/**
 * Fase 20-A — estrutura oratória INFERIDA a partir do S-34.
 *
 * Distinção conceitual inegociável:
 * ```text
 * OutlineDocument          → estrutura REAL do S-34 (não muda aqui)
 * InferredOratoryStructure → interpretação oratória derivada
 * ```
 * Esta camada NUNCA altera o S-34 (§16/§17) e nunca gera texto (§15): ela
 * planeja. É pura (sem rede, LLM, Firebase, UI) e NÃO é persistida (§32) —
 * pode ser reconstruída do OutlineDocument a qualquer momento.
 *
 * Fidelidade estrutural:
 * - INTRODUCTION = objective + first section + COMO apresentar
 * - DEVELOPMENT  = sequência EXATA do S-34 (nunca reordenada/fundida)
 * - CONCLUSION   = objective + last section + COMO apresentar
 * O S-34 pode não ter seções chamadas "Introdução"/"Conclusão" (§20): as
 * duas são inferidas; o desenvolvimento é projeção direta, não inferência
 * (§21).
 */
object OratoryStructure {

    /** Origem declarada de cada fonte (proveniência legível, §14). */
    enum class Source { S34, TRAINING, MISSING }

    /** Objetivo do discurso. `MISSING` quando o S-34 não declara (§7). */
    data class Purpose(
        val source: Source,
        val text: String? = null
    )

    /** Ponto do S-34 referenciado por id (nunca por cópia de texto, §14). */
    data class SectionRef(
        val sectionId: String,
        val order: Int,
        val title: String,
        val source: Source = Source.S34
    )

    /**
     * Uma parte oratória: conteúdo (O QUE falar) + treinamento (COMO
     * apresentar) explicitamente separados (§13).
     */
    data class Part(
        val purpose: Purpose,
        val section: SectionRef?,
        val trainingSources: List<TrainingSource>
    )

    /** Referência a BE/TH por categoria — sem copiar texto, sem ranking (§12). */
    data class TrainingSource(
        val category: TrainingCategory,
        val source: Source = Source.TRAINING
    )

    /** Projeção ordenada de um ponto no desenvolvimento (§5/§17). */
    data class DevelopmentStep(
        val section: SectionRef,
        val subsectionCount: Int,
        val referenceCount: Int
    )

    /**
     * Foco atual com vizinhos (§18) — insumo para transições futuras (§19).
     * Ausente quando a B.4 não resolveu um ponto (§29): nunca inventado.
     */
    data class Focus(
        val previousSectionId: String?,
        val currentSectionId: String,
        val nextSectionId: String?
    )

    data class Inferred(
        val outlineId: String,
        val title: String,
        val introduction: Part,
        val development: List<DevelopmentStep>,
        val conclusion: Part,
        val focus: Focus?
    )

    sealed interface Result {
        data class Ok(val structure: Inferred) : Result
        /** Sem seções estruturadas: nada a inferir (nunca artificial). */
        data object InsufficientStructure : Result
    }

    /**
     * Categorias de treinamento relevantes por parte. Usa SÓ a taxonomia
     * existente; nenhuma é "melhor" que outra (§12).
     */
    fun trainingFor(part: PartKind): List<TrainingSource> = when (part) {
        PartKind.INTRODUCTION -> listOf(
            TrainingCategory.INTRODUCTION, TrainingCategory.QUESTIONS,
            TrainingCategory.CLARITY, TrainingCategory.NATURALNESS
        )
        PartKind.DEVELOPMENT -> listOf(
            TrainingCategory.DEVELOPMENT, TrainingCategory.TRANSITION,
            TrainingCategory.EXPLANATION
        )
        PartKind.CONCLUSION -> listOf(
            TrainingCategory.CONCLUSION, TrainingCategory.APPLICATION,
            TrainingCategory.CLARITY, TrainingCategory.NATURALNESS
        )
    }.map { TrainingSource(it) }

    enum class PartKind { INTRODUCTION, DEVELOPMENT, CONCLUSION }

    /**
     * Infere a estrutura. `currentSectionId` vem da B.4; quando desconhecido
     * o foco fica ausente (nunca inventado).
     */
    fun infer(
        document: S34Document,
        currentSectionId: String? = null
    ): Result {
        if (document.sections.isEmpty()) return Result.InsufficientStructure
        val ordered = document.sections.sortedBy { it.order }
        val purpose = Purpose(
            source = if (document.objective.isNullOrBlank()) Source.MISSING else Source.S34,
            text = document.objective?.takeIf { it.isNotBlank() }
        )
        fun ref(s: com.bettertalker.app.data.s34.S34Section) = SectionRef(
            sectionId = s.id, order = s.order, title = s.title
        )
        val first = ref(ordered.first())
        val last = ref(ordered.last())
        val steps = ordered.map { s ->
            DevelopmentStep(
                section = ref(s),
                subsectionCount = s.subsections.size,
                referenceCount = s.references.size + s.subsections.sumOf { it.references.size }
            )
        }
        return Result.Ok(
            Inferred(
                outlineId = document.id,
                title = document.title,
                introduction = Part(purpose, first, trainingFor(PartKind.INTRODUCTION)),
                development = steps,
                conclusion = Part(purpose, last, trainingFor(PartKind.CONCLUSION)),
                focus = focusOf(ordered, currentSectionId)
            )
        )
    }

    /** previous/current/next a partir da ordem documental (§18). */
    private fun focusOf(
        ordered: List<com.bettertalker.app.data.s34.S34Section>,
        currentSectionId: String?
    ): Focus? {
        if (currentSectionId == null) return null
        val idx = ordered.indexOfFirst { it.id == currentSectionId }
        if (idx < 0) return null
        return Focus(
            previousSectionId = ordered.getOrNull(idx - 1)?.id,
            currentSectionId = ordered[idx].id,
            nextSectionId = ordered.getOrNull(idx + 1)?.id
        )
    }

    /**
     * Estado de foco a partir do resultado da B.4: só `SectionFocus` produz
     * foco; `UnknownSection`/`UnmatchedHint`/`DocumentScope` não inventam um
     * ponto atual (§29).
     */
    fun currentSectionOf(result: com.bettertalker.app.data.repo.S34StructuralRetriever.Result): String? =
        when (result) {
            is com.bettertalker.app.data.repo.S34StructuralRetriever.Result.SectionFocus ->
                result.view.sectionId
            else -> null
        }

    /** Atalho: infere a partir do documento + resultado da B.4. */
    fun inferFrom(
        document: S34Document,
        retrieval: com.bettertalker.app.data.repo.S34StructuralRetriever.Result
    ): Result = infer(document, currentSectionOf(retrieval))

    /** Só o título da seção referenciada (para serialização). */
    fun titleOf(structure: Inferred, sectionId: String): String? =
        structure.development.firstOrNull { it.section.sectionId == sectionId }?.section?.title

    /** Conveniência de leitura: pontos em ordem, sem proxy de score. */
    fun orderedSectionIds(structure: Inferred): List<String> =
        structure.development.map { it.section.sectionId }

    /** Vista escopada da B.4 tem os mesmos ids; nada é remapeado aqui. */
    fun scopeMatches(structure: Inferred, view: S34StructuralRetrieval.ScopedView): Boolean =
        orderedSectionIds(structure).contains(view.sectionId)
}

/**
 * Serializa o planejamento estrutural para o prompt (§22).
 * NUNCA gera texto oratório (§15) — só aponta fontes e categorias.
 */
fun serializeOratoryStructure(s: OratoryStructure.Inferred): String {
    val sb = StringBuilder()
    sb.append("\n\n--- ESTRUTURA ORATÓRIA INFERIDA (INFERIDA A PARTIR DO S-34) ---\n")
    sb.append("Esta é uma organização DERIVADA do S-34; ela não adiciona conteúdo factual ao esboço.\n")
    sb.append("[S34] Objetivo: ${s.introduction.purpose.text ?: "não declarado no S-34"}\n")

    val intro = s.introduction
    sb.append("[S34] PARTE 1 — ABERTURA\n")
    sb.append("[S34]   Conteúdo: objetivo")
    intro.section?.let {
        sb.append(" + ponto ${it.order} \"${it.title}\" (${it.sectionId})\n")
    } ?: sb.append("\n")
    sb.append("[TRAINING]   Como apresentar (BE/TH): " +
        intro.trainingSources.joinToString(", ") { it.category.serial } + "\n")

    sb.append("[S34] DESENVOLVIMENTO — sequência exata do S-34:\n")
    s.development.forEach { step ->
        val mark = if (s.focus?.currentSectionId == step.section.sectionId) "  <= PONTO ATUAL" else ""
        sb.append("[S34]   ${step.section.order}. ${step.section.title} (${step.section.sectionId})" +
            " — ${step.subsectionCount} subponto(s), ${step.referenceCount} referência(s)$mark\n")
    }
    sb.append("[TRAINING]   Como apresentar (BE/TH): " +
        OratoryStructure.trainingFor(OratoryStructure.PartKind.DEVELOPMENT)
            .joinToString(", ") { it.category.serial } + "\n")

    s.focus?.let { f ->
        sb.append("[S34] FOCO ATUAL: ${f.currentSectionId}" +
            " (anterior: ${f.previousSectionId ?: "—"}; próximo: ${f.nextSectionId ?: "—"})\n")
    }

    val conc = s.conclusion
    sb.append("[S34] PARTE FINAL — CONCLUSÃO\n")
    sb.append("[S34]   Conteúdo: objetivo")
    conc.section?.let {
        sb.append(" + ponto ${it.order} \"${it.title}\" (${it.sectionId})\n")
    } ?: sb.append("\n")
    sb.append("[TRAINING]   Como apresentar (BE/TH): " +
        conc.trainingSources.joinToString(", ") { it.category.serial } + "\n")

    sb.append("--- FIM DA ESTRUTURA ORATÓRIA INFERIDA ---\n")
    return sb.toString()
}

/**
 * Regra de prompt da estrutura inferida (§23): derivada, não conteúdo novo.
 * Contrato testado como regra, não como texto decorativo.
 */
val ORATORY_STRUCTURE_RULES = """
A estrutura oratória (abertura/desenvolvimento/conclusão) é uma organização
DERIVADA do S-34: ela não adiciona conteúdo factual ao esboço. Preserve a
sequência do desenvolvimento e use BE/TH somente para decidir COMO apresentar
cada parte, nunca para inventar conteúdo factual.
""".trimIndent()
