package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.s34.S34Section
import com.bettertalker.app.domain.speech.SectionRole

/**
 * Mentor — fatia 2: coaching de UM ponto do esboço.
 *
 * Determinístico e read-only. Não gera texto, não chama LLM e não altera a
 * nota. O objetivo é ensinar o usuário a desenvolver o ponto: leitura,
 * diagnóstico, caminho/perguntas e próximo passo. O rascunho continua sendo
 * um pedido explícito no fluxo existente.
 */
data class PointCoachingInput(
    val section: NoteSectionSnapshot,
    val pointNumber: Int,
    val s34Section: S34Section? = null,
    val sourceCounts: DiagnosisSourceCounts = DiagnosisSourceCounts.NONE,
    val nextPointTitle: String? = null,
)

/** Um achado do ponto; a UI mostra os três primeiros. */
data class PointCoachingFinding(
    val label: String,
    val level: DiagnosisLevel,
    val detail: String,
)

/** Próximo passo primário do card. O rascunho é uma ação secundária explícita. */
sealed interface PointCoachingNextStep {
    val label: String

    data class OpenEditor(
        val sectionId: String,
        val title: String,
        val sectionNumber: Int,
        override val label: String = "Abrir no editor",
    ) : PointCoachingNextStep
}

data class PointCoachingReport(
    val sectionId: String,
    val pointNumber: Int,
    val title: String,
    val minutes: Int,
    val overall: DiagnosisLevel,
    val reading: String,
    val findings: List<PointCoachingFinding>,
    val steps: List<String>,
    val nextStep: PointCoachingNextStep,
    val generatedAt: Long,
)

/**
 * Analisador puro do ponto. Só trabalha BODY; introdução/conclusão têm fluxo
 * próprio e são recusadas antes de chegar aqui.
 */
object PointCoachingAnalyzer {

    fun analyze(input: PointCoachingInput, now: Long): PointCoachingReport {
        val section = input.section
        require(section.role == SectionRole.BODY) {
            "PointCoachingAnalyzer só aceita BODY"
        }
        val totalSubs = section.subPoints.size
        val developedSubs = section.subPoints.count { it.hasDevelopment }
        val words = wordCount(section.bodyText) +
            section.subPoints.sumOf { wordCount(it.developedText) }
        val hasText = section.bodyText.isNotBlank() ||
            section.subPoints.any { it.hasDevelopment }
        val s34Refs = input.s34Section?.references.orEmpty()
        val reading = buildString {
            append("Ponto ${input.pointNumber} · ${section.title.ifBlank { "sem título" }}")
            append(" · ${section.minutes} min")
            if (totalSubs > 0) append(" · $developedSubs/$totalSubs subpontos com texto")
            append(" · $words palavra(s)")
            val s34 = input.s34Section
            if (s34 != null) {
                append(" · S-34: ${s34.references.size} ref(s)")
            }
        }
        val findings = listOf(
            subPointsFinding(section, totalSubs, developedSubs, hasText),
            textFinding(words, hasText, section.minutes),
            sourcesFinding(input.sourceCounts),
            s34Finding(input.s34Section),
        )
        val overall = when {
            findings.any { it.level == DiagnosisLevel.MISSING } -> DiagnosisLevel.MISSING
            findings.any { it.level == DiagnosisLevel.ATTENTION } -> DiagnosisLevel.ATTENTION
            else -> DiagnosisLevel.OK
        }
        val steps = buildSteps(
            section = section,
            totalSubs = totalSubs,
            developedSubs = developedSubs,
            hasText = hasText,
            s34Refs = s34Refs.map { it.rawText },
            nextPointTitle = input.nextPointTitle,
            level = overall,
        )
        return PointCoachingReport(
            sectionId = section.id,
            pointNumber = input.pointNumber,
            title = section.title.ifBlank { "Ponto ${input.pointNumber}" },
            minutes = section.minutes,
            overall = overall,
            reading = reading,
            findings = findings,
            steps = steps,
            nextStep = PointCoachingNextStep.OpenEditor(
                sectionId = section.id,
                title = section.title,
                sectionNumber = input.pointNumber,
            ),
            generatedAt = now,
        )
    }

    private fun subPointsFinding(
        section: NoteSectionSnapshot,
        total: Int,
        developed: Int,
        hasText: Boolean,
    ): PointCoachingFinding {
        if (total == 0) {
            return if (hasText) {
                PointCoachingFinding(
                    "Subpontos",
                    DiagnosisLevel.OK,
                    "Sem subpontos; o texto está no corpo da seção.",
                )
            } else {
                PointCoachingFinding(
                    "Subpontos",
                    DiagnosisLevel.ATTENTION,
                    "O ponto ainda não tem texto nem subpontos.",
                )
            }
        }
        val empty = section.subPoints.filterNot { it.hasDevelopment }
        return if (empty.isEmpty()) {
            PointCoachingFinding(
                "Subpontos",
                DiagnosisLevel.OK,
                "$developed/$total subpontos com texto.",
            )
        } else {
            val first = empty.first().outlineText.ifBlank { "subponto vazio" }
            PointCoachingFinding(
                "Subpontos",
                DiagnosisLevel.ATTENTION,
                "$developed/$total com texto. Comece por: “${first.take(90)}”.",
            )
        }
    }

    private fun textFinding(words: Int, hasText: Boolean, minutes: Int): PointCoachingFinding {
        if (!hasText || words == 0) {
            return PointCoachingFinding(
                "Texto atual",
                DiagnosisLevel.ATTENTION,
                "Ainda sem texto. O alvo é ${minutes} min — comece por uma ideia e uma aplicação.",
            )
        }
        return PointCoachingFinding(
            "Texto atual",
            DiagnosisLevel.OK,
            "$words palavra(s) para ${minutes} min (estimativa; ajuste ao seu ritmo).",
        )
    }

    private fun sourcesFinding(counts: DiagnosisSourceCounts): PointCoachingFinding {
        if (counts.total == 0) {
            return PointCoachingFinding(
                "Fontes da nota",
                DiagnosisLevel.NOT_ASSESSED,
                "A nota não tem referências para avaliar.",
            )
        }
        val detail = buildString {
            append("${counts.resolved}/${counts.total} resolvidas")
            if (counts.indexing > 0) append(" · ${counts.indexing} indexando")
            if (counts.noText > 0) append(" · ${counts.noText} sem texto")
            if (counts.blocking > 0) append(" · ${counts.blocking} bloqueantes")
        }
        val attention = counts.resolved < counts.total ||
            counts.indexing > 0 || counts.noText > 0 ||
            counts.blocking > 0 || counts.needsAction > 0
        return PointCoachingFinding(
            "Fontes da nota",
            if (attention) DiagnosisLevel.ATTENTION else DiagnosisLevel.OK,
            detail,
        )
    }

    private fun s34Finding(s34: S34Section?): PointCoachingFinding =
        if (s34 == null) {
            PointCoachingFinding(
                "S-34",
                DiagnosisLevel.NOT_ASSESSED,
                "Não encontrei a seção correspondente no S-34.",
            )
        } else {
            PointCoachingFinding(
                "S-34",
                DiagnosisLevel.OK,
                "${s34.title.ifBlank { "seção" }} · ${s34.references.size} referência(s).",
            )
        }

    private fun buildSteps(
        section: NoteSectionSnapshot,
        totalSubs: Int,
        developedSubs: Int,
        hasText: Boolean,
        s34Refs: List<String>,
        nextPointTitle: String?,
        level: DiagnosisLevel,
    ): List<String> = buildList {
        if (!hasText) {
            add("Leia o ponto e escreva em uma frase o que o ouvinte deve entender.")
        } else if (developedSubs < totalSubs) {
            add("Relate o ponto em uma frase e confira se cada subponto sustenta essa ideia.")
        } else {
            add("Leia o ponto em voz alta e marque a ideia central de cada subponto.")
        }
        val empty = section.subPoints.filterNot { it.hasDevelopment }
        empty.take(1).forEachIndexed { index, sub ->
            val anchor = sub.outlineText.ifBlank { "subponto ${index + 1}" }
            add("Desenvolva “${anchor.take(90)}”: explique, mostre o apoio e aplique.")
        }
        if (s34Refs.isNotEmpty()) {
            add("Escolha uma referência (${s34Refs.first().take(60)}) e mostre como ela sustenta a ideia.")
        } else {
            add("Escolha a escritura ou o trecho que ancora este ponto e explique o contexto.")
        }
        if (!nextPointTitle.isNullOrBlank()) {
            add("Escreva a transição para “${nextPointTitle.take(70)}”.")
        } else {
            add("Feche o ponto com uma frase que prepare a conclusão.")
        }
        if (level == DiagnosisLevel.OK) {
            add("Ajuste a aplicação prática e revise o tempo de fala no editor.")
        }
    }.distinct().take(4)

    private fun wordCount(text: String): Int =
        text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
}
