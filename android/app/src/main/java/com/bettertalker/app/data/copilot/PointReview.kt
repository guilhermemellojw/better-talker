package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.s34.S34Section
import com.bettertalker.app.domain.speech.SectionRole

/**
 * Mentor — fatia 3: revisão estrutural do texto de UM ponto.
 *
 * O usuário escreve; o Mentor aponta o que está bom e até 3 melhorias.
 * Determinístico, read-only e sem reescrever: nenhum texto é gerado ou
 * substituído. A revisão qualitativa (estilo, aplicação fina, transições)
 * fica para uma etapa com LLM.
 */
data class PointReviewInput(
    val section: NoteSectionSnapshot,
    val pointNumber: Int,
    val s34Section: S34Section? = null,
    val sourceCounts: DiagnosisSourceCounts = DiagnosisSourceCounts.NONE,
    val nextPointTitle: String? = null,
)

sealed interface PointReviewNextStep {
    val label: String

    data class OpenEditor(
        val sectionId: String,
        val title: String,
        val sectionNumber: Int,
        override val label: String = "Abrir no editor",
    ) : PointReviewNextStep
}

data class PointReviewReport(
    val sectionId: String,
    val pointNumber: Int,
    val title: String,
    val minutes: Int,
    val overall: DiagnosisLevel,
    val reading: String,
    val strengths: List<String>,
    val improvements: List<String>,
    val nextStep: PointReviewNextStep,
    val generatedAt: Long,
    /** 1B — revisão qualitativa (DeepSeek); null quando não executada. */
    val quality: PointQualityReview? = null,
    val qualityStatus: PointQualityStatus = PointQualityStatus.NONE,
    val qualityError: String? = null,
    val qualityTextHash: String? = null,
    val qualityStartedAt: Long? = null,
)

object PointReviewAnalyzer {

    fun analyze(input: PointReviewInput, now: Long): PointReviewReport {
        val section = input.section
        require(section.role == SectionRole.BODY) {
            "PointReviewAnalyzer só aceita BODY"
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
            if (totalSubs > 0) append(" · $developedSubs/$totalSubs subpontos")
            append(" · $words palavra(s)")
            if (s34Refs.isNotEmpty()) append(" · S-34: ${s34Refs.size} ref(s)")
        }
        val strengths = strengthsOf(
            section = section,
            totalSubs = totalSubs,
            developedSubs = developedSubs,
            words = words,
            hasText = hasText,
            s34Refs = s34Refs.map { it.rawText },
        )
        val improvements = improvementsOf(
            section = section,
            totalSubs = totalSubs,
            developedSubs = developedSubs,
            words = words,
            hasText = hasText,
            sourceCounts = input.sourceCounts,
            nextPointTitle = input.nextPointTitle,
        )
        val sourcesAttention = input.sourceCounts.total > 0 && (
            input.sourceCounts.resolved < input.sourceCounts.total ||
                input.sourceCounts.indexing > 0 ||
                input.sourceCounts.noText > 0 ||
                input.sourceCounts.blocking > 0 ||
                input.sourceCounts.needsAction > 0
            )
        val overall = when {
            !hasText -> DiagnosisLevel.ATTENTION
            totalSubs > 0 && developedSubs < totalSubs -> DiagnosisLevel.ATTENTION
            sourcesAttention -> DiagnosisLevel.ATTENTION
            else -> DiagnosisLevel.OK
        }
        return PointReviewReport(
            sectionId = section.id,
            pointNumber = input.pointNumber,
            title = section.title.ifBlank { "Ponto ${input.pointNumber}" },
            minutes = section.minutes,
            overall = overall,
            reading = reading,
            strengths = strengths,
            improvements = improvements,
            nextStep = PointReviewNextStep.OpenEditor(
                sectionId = section.id,
                title = section.title,
                sectionNumber = input.pointNumber,
            ),
            generatedAt = now,
        )
    }

    private fun strengthsOf(
        section: NoteSectionSnapshot,
        totalSubs: Int,
        developedSubs: Int,
        words: Int,
        hasText: Boolean,
        s34Refs: List<String>,
    ): List<String> = buildList {
        if (totalSubs > 0) {
            add("O ponto tem $totalSubs subponto(s) definidos no esboço.")
        } else {
            add("O ponto existe na estrutura da nota.")
        }
        if (hasText && words > 0) {
            add("Há $words palavra(s) escritas no ponto.")
        }
        if (developedSubs > 0) {
            if (totalSubs > 0 && developedSubs == totalSubs) {
                add("Todos os subpontos estão cobertos.")
            } else {
                add("$developedSubs de $totalSubs subpontos já têm texto.")
            }
        }
        if (s34Refs.isNotEmpty()) {
            add("O S-34 indica ${s34Refs.size} referência(s) para este ponto.")
        }
    }.distinct().take(3)

    private fun improvementsOf(
        section: NoteSectionSnapshot,
        totalSubs: Int,
        developedSubs: Int,
        words: Int,
        hasText: Boolean,
        sourceCounts: DiagnosisSourceCounts,
        nextPointTitle: String?,
    ): List<String> = buildList {
        val empty = section.subPoints.filterNot { it.hasDevelopment }
        if (!hasText) {
            if (empty.isNotEmpty()) {
                add(
                    "Comece pelo primeiro subponto: “${empty.first().outlineText.take(90)}” — " +
                        "explique, mostre o apoio e aplique."
                )
            } else {
                add("Escreva uma primeira versão: ideia central + aplicação para o ouvinte.")
            }
        } else if (empty.isNotEmpty()) {
            add(
                "Desenvolva “${empty.first().outlineText.take(90)}”; ele ainda está só no esboço."
            )
            if (empty.size > 1) {
                add("Ainda faltam ${empty.size - 1} subponto(s) sem texto.")
            }
        }
        if (sourceCounts.total > 0) {
            val pending = sourceCounts.blocking + sourceCounts.needsAction
            when {
                pending > 0 -> add("Resolva $pending fonte(s) bloqueante(s)/pendente(s) antes de citar.")
                sourceCounts.indexing > 0 ->
                    add("Aguarde a indexação de ${sourceCounts.indexing} fonte(s).")
                sourceCounts.noText > 0 ->
                    add("Há ${sourceCounts.noText} fonte(s) sem texto; confira antes de citar.")
            }
        }
        if (hasText) {
            val minWords = section.minutes * 80
            if (words in 1 until minWords) {
                add(
                    "O texto tem $words palavra(s) para ${section.minutes} min; " +
                        "pode faltar desenvolvimento (estimativa)."
                )
            }
            add("Inclua uma aplicação prática: o que o ouvinte deve fazer ou sentir?")
        }
        if (!nextPointTitle.isNullOrBlank()) {
            add("Feche o ponto com uma transição para “${nextPointTitle.take(70)}”.")
        } else if (hasText) {
            add("Feche o ponto com uma frase que prepare a conclusão.")
        }
    }.distinct().take(3)

    private fun wordCount(text: String): Int =
        text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
}
