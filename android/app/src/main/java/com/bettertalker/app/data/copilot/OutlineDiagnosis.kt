package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.s34.S34Document
import com.bettertalker.app.data.s34.S34Section
import com.bettertalker.app.data.util.htmlToPlainText
import com.bettertalker.app.data.util.normalizeText
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import com.bettertalker.app.domain.speech.SubPoint
import kotlin.math.abs
import kotlin.math.roundToInt

/** Nível de um critério ou do diagnóstico geral. */
enum class DiagnosisLevel {
    OK,
    ATTENTION,
    MISSING,
    /** O dado existe, mas não foi declarado (ex.: objetivo ausente). */
    NOT_INFORMED,
    /** Não há dado suficiente para avaliar (ex.: nota sem refs). */
    NOT_ASSESSED,
}

/** Estado do pré-requisito S-34. */
enum class DiagnosisS34Status { LINKED, MISSING, NO_STRUCTURE }

/** Contagens de fontes já resolvidas pelo NoteSources. */
data class DiagnosisSourceCounts(
    val total: Int = 0,
    val resolved: Int = 0,
    val indexing: Int = 0,
    val noText: Int = 0,
    val blocking: Int = 0,
    val needsAction: Int = 0,
) {
    companion object { val NONE = DiagnosisSourceCounts() }
}

/** Subponto da nota já convertido para texto (sem HTML). */
data class NoteSubPointSnapshot(
    val id: String,
    val order: Int,
    val outlineText: String,
    val developedText: String,
) {
    val hasDevelopment: Boolean get() = developedText.isNotBlank()
}

/** Seção da nota já convertida para texto (sem HTML). */
data class NoteSectionSnapshot(
    val id: String,
    val order: Int,
    val role: SectionRole,
    val title: String,
    val minutes: Int,
    val bodyText: String,
    val objective: String? = null,
    val subPoints: List<NoteSubPointSnapshot> = emptyList(),
) {
    /** Intro/conclusão usam o corpo; BODY pode ser desenvolvido nos subpontos. */
    val hasContent: Boolean
        get() = when (role) {
            SectionRole.INTRO, SectionRole.CONCLUSION -> bodyText.isNotBlank()
            SectionRole.BODY -> bodyText.isNotBlank() || subPoints.any { it.hasDevelopment }
        }
}

/** Entrada pura do analisador. Nada de Room/Android aqui. */
data class OutlineDiagnosisInput(
    val s34: S34Document?,
    val noteTitle: String?,
    val sections: List<NoteSectionSnapshot>,
    val sourceCounts: DiagnosisSourceCounts,
)

enum class DiagnosisCriterionId {
    STRUCTURE,
    ALIGNMENT,
    TIMES,
    DEVELOPMENT,
    SOURCES,
    OBJECTIVE,
}

/** Critério exibido no card. A ação é textual (o botão fica no nextStep). */
data class DiagnosisCriterion(
    val id: DiagnosisCriterionId,
    val label: String,
    val level: DiagnosisLevel,
    val finding: String,
    val action: String? = null,
)

/** Ponto BODY com status próprio; a UI filtra atenção/“Ver todos”. */
data class DiagnosisPoint(
    val sectionId: String,
    val number: Int,
    val title: String,
    val minutes: Int,
    val level: DiagnosisLevel,
    val finding: String,
    /** Rótulo do botão; ambos apenas navegam para a seção. */
    val actionLabel: String,
)

/** Visibilidade dos pontos no card: atenção primeiro; tudo OK compacto. */
data class DiagnosisPointsVisibility(
    val visible: List<DiagnosisPoint>,
    val showToggle: Boolean,
    val compactOnly: Boolean,
)

fun diagnosisPointsVisibility(
    points: List<DiagnosisPoint>,
    expanded: Boolean,
): DiagnosisPointsVisibility {
    val attention = points.filter { it.level != DiagnosisLevel.OK }
    return when {
        points.isEmpty() -> DiagnosisPointsVisibility(emptyList(), false, false)
        attention.isEmpty() && !expanded ->
            DiagnosisPointsVisibility(emptyList(), true, true)
        attention.isEmpty() ->
            DiagnosisPointsVisibility(points, true, false)
        expanded ->
            DiagnosisPointsVisibility(points, true, false)
        attention.size == points.size ->
            DiagnosisPointsVisibility(points, false, false)
        else ->
            DiagnosisPointsVisibility(attention, true, false)
    }
}

/** Próximo passo acionável, sem LLM e sem edição. */
sealed interface DiagnosisNextStep {
    val label: String

    data class OpenSection(
        val sectionId: String,
        val title: String,
        val sectionNumber: Int,
        override val label: String,
    ) : DiagnosisNextStep

    data class OpenNote(override val label: String = "Abrir nota") : DiagnosisNextStep
    data class ShowSources(override val label: String = "Mostrar fontes") : DiagnosisNextStep
    data class LinkS34(override val label: String = "Vincular S-34") : DiagnosisNextStep
    data class ReimportS34(override val label: String = "Reimportar S-34") : DiagnosisNextStep
    data class Reanalyze(override val label: String = "Reanalisar") : DiagnosisNextStep
}

/** Resultado completo do diagnóstico (persistido no payload do chat). */
data class OutlineDiagnosis(
    val overall: DiagnosisLevel,
    val headline: String,
    val summary: String,
    val s34Status: DiagnosisS34Status,
    val s34Detail: String,
    val criteria: List<DiagnosisCriterion>,
    val points: List<DiagnosisPoint>,
    val priorities: List<String>,
    val nextStep: DiagnosisNextStep,
    val generatedAt: Long,
)

/**
 * Analisador determinístico do esboço.
 *
 * Regras estruturais:
 * - S-34 é pré-requisito (não entra nos seis critérios).
 * - Os seis critérios são: estrutura, alinhamento, tempos, desenvolvimento,
 *   fontes e objetivo.
 * - Só os BODY da nota são comparados com as seções do S-34; intro/conclusão
 *   são papéis da nota.
 * - Nada é inferido: objetivo ausente é NOT_INFORMED, não OK.
 * - `now` entra por parâmetro para o teste ser determinístico.
 */
object OutlineDiagnosisAnalyzer {

    fun analyze(input: OutlineDiagnosisInput, now: Long): OutlineDiagnosis {
        val sections = input.sections.sortedBy { it.order }
        val intro = sections.filter { it.role == SectionRole.INTRO }
        val body = sections.filter { it.role == SectionRole.BODY }
        val conclusion = sections.filter { it.role == SectionRole.CONCLUSION }
        val s34 = input.s34
        val s34Sections = s34?.sections?.sortedBy { it.order }.orEmpty()

        val s34Status = when {
            s34 == null -> DiagnosisS34Status.MISSING
            s34Sections.isEmpty() -> DiagnosisS34Status.NO_STRUCTURE
            else -> DiagnosisS34Status.LINKED
        }
        val s34Detail = when (s34Status) {
            DiagnosisS34Status.MISSING ->
                "Nenhum S-34 importado/vinculado à nota."
            DiagnosisS34Status.NO_STRUCTURE ->
                "S-34 vinculado, mas sem seções estruturadas."
            DiagnosisS34Status.LINKED ->
                "${s34?.title.orEmpty()} · ${s34Sections.size} pontos"
        }

        val structure = structureCriterion(sections, intro, body, conclusion)
        val alignment = alignmentCriterion(s34Status, body, s34Sections)
        val times = timesCriterion(
            body = body,
            intro = intro,
            conclusion = conclusion,
            allSections = sections,
            s34Status = s34Status,
            s34Sections = s34Sections,
        )
        val development = developmentCriterion(sections, body)
        val sources = sourcesCriterion(input.sourceCounts)
        val objective = objectiveCriterion(s34Status, s34, sections)
        val criteria = listOf(structure, alignment, times, development, sources, objective)

        val points = body.mapIndexed { index, section ->
            pointOf(section, index + 1)
        }
        val overall = when {
            s34Status != DiagnosisS34Status.LINKED -> DiagnosisLevel.MISSING
            sections.isEmpty() -> DiagnosisLevel.MISSING
            criteria.all { it.level == DiagnosisLevel.OK } -> DiagnosisLevel.OK
            else -> DiagnosisLevel.ATTENTION
        }
        val priorities = prioritiesOf(
            s34Status = s34Status,
            structure = structure,
            alignment = alignment,
            times = times,
            development = development,
            sources = sources,
            objective = objective,
            body = body,
        )
        val nextStep = nextStepOf(
            s34Status = s34Status,
            structure = structure,
            alignment = alignment,
            times = times,
            development = development,
            sources = sources,
            objective = objective,
            body = body,
            s34Sections = s34Sections,
        )
        val headline = when {
            s34Status == DiagnosisS34Status.MISSING -> "S-34 não vinculado"
            s34Status == DiagnosisS34Status.NO_STRUCTURE -> "S-34 sem estrutura"
            sections.isEmpty() -> "Nota sem estrutura"
            overall == DiagnosisLevel.OK -> "Esboço pronto para desenvolver"
            else -> "Esboço com pontos de atenção"
        }
        val summary = summaryOf(
            sections = sections,
            intro = intro,
            body = body,
            conclusion = conclusion,
            sourceCounts = input.sourceCounts,
        )

        return OutlineDiagnosis(
            overall = overall,
            headline = headline,
            summary = summary,
            s34Status = s34Status,
            s34Detail = s34Detail,
            criteria = criteria,
            points = points,
            priorities = priorities,
            nextStep = nextStep,
            generatedAt = now,
        )
    }

    // ---------- estrutura ----------

    private fun structureCriterion(
        sections: List<NoteSectionSnapshot>,
        intro: List<NoteSectionSnapshot>,
        body: List<NoteSectionSnapshot>,
        conclusion: List<NoteSectionSnapshot>,
    ): DiagnosisCriterion {
        if (sections.isEmpty()) {
            return DiagnosisCriterion(
                DiagnosisCriterionId.STRUCTURE,
                "Estrutura",
                DiagnosisLevel.MISSING,
                "A nota não tem seções.",
                "Abrir nota",
            )
        }
        val issues = buildList {
            if (intro.size != 1) add(
                if (intro.isEmpty()) "falta a introdução" else "${intro.size} introduções"
            )
            if (body.isEmpty()) add("faltam os pontos")
            if (conclusion.size != 1) add(
                if (conclusion.isEmpty()) "falta a conclusão" else "${conclusion.size} conclusões"
            )
        }
        return if (issues.isEmpty()) {
            DiagnosisCriterion(
                DiagnosisCriterionId.STRUCTURE,
                "Estrutura",
                DiagnosisLevel.OK,
                "${intro.size} introdução, ${body.size} pontos, ${conclusion.size} conclusão.",
            )
        } else {
            DiagnosisCriterion(
                DiagnosisCriterionId.STRUCTURE,
                "Estrutura",
                DiagnosisLevel.ATTENTION,
                "${issues.joinToString("; ")}; a nota tem ${sections.size} seções.",
                "Revisar estrutura",
            )
        }
    }

    // ---------- alinhamento ----------

    private fun alignmentCriterion(
        s34Status: DiagnosisS34Status,
        body: List<NoteSectionSnapshot>,
        s34Sections: List<S34Section>,
    ): DiagnosisCriterion {
        if (s34Status != DiagnosisS34Status.LINKED) {
            return DiagnosisCriterion(
                DiagnosisCriterionId.ALIGNMENT,
                "Alinhamento S-34 × nota",
                DiagnosisLevel.NOT_ASSESSED,
                "S-34 ausente ou sem estrutura.",
            )
        }
        if (body.isEmpty()) {
            return DiagnosisCriterion(
                DiagnosisCriterionId.ALIGNMENT,
                "Alinhamento S-34 × nota",
                DiagnosisLevel.ATTENTION,
                "A nota não tem pontos BODY para comparar.",
                "Revisar estrutura",
            )
        }
        val issues = mutableListOf<String>()
        if (body.size != s34Sections.size) {
            issues += "nota ${body.size} pontos × S-34 ${s34Sections.size}"
        }
        body.zip(s34Sections).forEachIndexed { index, (note, s34) ->
            val point = "ponto ${index + 1}"
            if (titleKey(note.title) != titleKey(s34.title)) {
                issues += "$point: “${note.title.trim()}” × “${s34.title.trim()}”"
            }
            if (note.subPoints.size != s34.subsections.size) {
                // S-34 sem subseções estruturadas: usa as linhas §N do
                // conteúdo como âncoras. Sem elas, não dá para comparar.
                val anchors = if (s34.subsections.isNotEmpty()) {
                    s34.subsections.sortedBy { it.order }.map { it.content }
                } else {
                    contentAnchors(s34.content)
                }
                if (anchors.isNotEmpty()) {
                    if (note.subPoints.size != anchors.size) {
                        issues += "$point: ${note.subPoints.size} subpontos × ${anchors.size} no S-34"
                    } else {
                        note.subPoints.sortedBy { it.order }
                            .zip(anchors)
                            .forEachIndexed { subIndex, (noteSub, anchor) ->
                                if (!anchorsMatch(noteSub.outlineText, anchor)) {
                                    issues += "$point, subponto ${subIndex + 1} diverge do S-34"
                                }
                            }
                    }
                }
            } else {
                note.subPoints.sortedBy { it.order }
                    .zip(s34.subsections.sortedBy { it.order })
                    .forEachIndexed { subIndex, (noteSub, s34Sub) ->
                        if (!anchorsMatch(noteSub.outlineText, s34Sub.content)) {
                            issues += "$point, subponto ${subIndex + 1} diverge do S-34"
                        }
                    }
            }
        }
        return if (issues.isEmpty()) {
            DiagnosisCriterion(
                DiagnosisCriterionId.ALIGNMENT,
                "Alinhamento S-34 × nota",
                DiagnosisLevel.OK,
                "${body.size} pontos BODY batem com as ${s34Sections.size} seções do S-34.",
            )
        } else {
            DiagnosisCriterion(
                DiagnosisCriterionId.ALIGNMENT,
                "Alinhamento S-34 × nota",
                DiagnosisLevel.ATTENTION,
                issues.take(2).joinToString("; ") +
                    if (issues.size > 2) " (+${issues.size - 2})" else "",
                "Revisar estrutura",
            )
        }
    }

    // ---------- tempos ----------

    private fun timesCriterion(
        body: List<NoteSectionSnapshot>,
        intro: List<NoteSectionSnapshot>,
        conclusion: List<NoteSectionSnapshot>,
        allSections: List<NoteSectionSnapshot>,
        s34Status: DiagnosisS34Status,
        s34Sections: List<S34Section>,
    ): DiagnosisCriterion {
        if (s34Status != DiagnosisS34Status.LINKED) {
            return DiagnosisCriterion(
                DiagnosisCriterionId.TIMES,
                "Tempos",
                DiagnosisLevel.NOT_ASSESSED,
                "S-34 ausente ou sem estrutura.",
            )
        }
        val s34WithMinutes = s34Sections.mapNotNull { it.minutes?.takeIf { m -> m > 0 } }
        val s34WithoutMinutes = s34Sections.count { it.minutes == null || it.minutes <= 0 }
        val noteBodyMinutes = body.sumOf { it.minutes }
        val noteTotal = allSections.sumOf { it.minutes }
        val introMinutes = intro.sumOf { it.minutes }
        val conclusionMinutes = conclusion.sumOf { it.minutes }
        val issues = mutableListOf<String>()

        if (s34WithoutMinutes > 0) {
            issues += "S-34 sem minutos em $s34WithoutMinutes seção(ões)"
        }
        if (s34WithMinutes.isNotEmpty() && body.isNotEmpty()) {
            val s34Sum = s34WithMinutes.sum()
            val tolerance = maxOf(1, (s34Sum * 0.10).roundToInt())
            if (abs(noteBodyMinutes - s34Sum) > tolerance) {
                issues += "pontos: nota $noteBodyMinutes min × S-34 $s34Sum min"
            }
        }
        body.zip(s34Sections).forEachIndexed { index, (note, s34) ->
            val s34Minutes = s34.minutes?.takeIf { it > 0 } ?: return@forEachIndexed
            if (note.minutes <= 0) {
                issues += "ponto ${index + 1} sem tempo na nota"
            } else {
                val tolerance = maxOf(1, (s34Minutes * 0.20).roundToInt())
                if (abs(note.minutes - s34Minutes) > tolerance) {
                    issues += "ponto ${index + 1}: ${note.minutes} min × $s34Minutes min"
                }
            }
        }
        if (introMinutes <= 0) issues += "introdução sem tempo"
        if (conclusionMinutes <= 0) issues += "conclusão sem tempo"

        val finding = if (issues.isEmpty()) {
            "Pontos: $noteBodyMinutes min (total da nota: $noteTotal min) · " +
                "S-34: ${s34WithMinutes.sum()} min."
        } else {
            issues.take(2).joinToString("; ") +
                if (issues.size > 2) " (+${issues.size - 2})" else ""
        }
        return DiagnosisCriterion(
            DiagnosisCriterionId.TIMES,
            "Tempos",
            if (issues.isEmpty()) DiagnosisLevel.OK else DiagnosisLevel.ATTENTION,
            finding,
            if (issues.isEmpty()) null else "Ajustar tempos",
        )
    }

    // ---------- desenvolvimento ----------

    private fun developmentCriterion(
        sections: List<NoteSectionSnapshot>,
        body: List<NoteSectionSnapshot>,
    ): DiagnosisCriterion {
        if (sections.isEmpty()) {
            return DiagnosisCriterion(
                DiagnosisCriterionId.DEVELOPMENT,
                "Desenvolvimento",
                DiagnosisLevel.MISSING,
                "A nota não tem seções.",
                "Abrir nota",
            )
        }
        val emptySections = sections.filter { !it.hasContent }
        val emptySubPoints = body.sumOf { section ->
            section.subPoints.count { !it.hasDevelopment }
        }
        return when {
            emptySections.size == sections.size -> DiagnosisCriterion(
                DiagnosisCriterionId.DEVELOPMENT,
                "Desenvolvimento",
                DiagnosisLevel.MISSING,
                "Nenhuma seção desenvolvida.",
                "Desenvolver ponto",
            )
            emptySections.isNotEmpty() || emptySubPoints > 0 -> DiagnosisCriterion(
                DiagnosisCriterionId.DEVELOPMENT,
                "Desenvolvimento",
                DiagnosisLevel.ATTENTION,
                buildString {
                    append("${sections.size - emptySections.size}/${sections.size} seções com texto")
                    if (emptySubPoints > 0) append(" · $emptySubPoints subponto(s) vazio(s)")
                },
                "Desenvolver ponto",
            )
            else -> DiagnosisCriterion(
                DiagnosisCriterionId.DEVELOPMENT,
                "Desenvolvimento",
                DiagnosisLevel.OK,
                "${sections.size}/${sections.size} seções com texto.",
            )
        }
    }

    // ---------- fontes ----------

    private fun sourcesCriterion(counts: DiagnosisSourceCounts): DiagnosisCriterion {
        if (counts.total == 0) {
            return DiagnosisCriterion(
                DiagnosisCriterionId.SOURCES,
                "Fontes",
                DiagnosisLevel.NOT_ASSESSED,
                "A nota não tem referências para avaliar.",
                "Mostrar fontes",
            )
        }
        val finding = buildString {
            append("${counts.resolved}/${counts.total} resolvidas")
            if (counts.noText > 0) append(" · ${counts.noText} sem texto")
            if (counts.indexing > 0) append(" · ${counts.indexing} indexando")
            if (counts.blocking > 0) append(" · ${counts.blocking} bloqueantes")
            if (counts.needsAction > 0) append(" · ${counts.needsAction} com ação")
        }
        return when {
            counts.blocking > 0 || counts.needsAction > 0 -> DiagnosisCriterion(
                DiagnosisCriterionId.SOURCES,
                "Fontes",
                DiagnosisLevel.ATTENTION,
                finding,
                "Mostrar fontes",
            )
            counts.indexing > 0 -> DiagnosisCriterion(
                DiagnosisCriterionId.SOURCES,
                "Fontes",
                DiagnosisLevel.ATTENTION,
                finding,
                "Aguardar indexação",
            )
            counts.noText > 0 -> DiagnosisCriterion(
                DiagnosisCriterionId.SOURCES,
                "Fontes",
                DiagnosisLevel.ATTENTION,
                finding,
                "Mostrar fontes",
            )
            else -> DiagnosisCriterion(
                DiagnosisCriterionId.SOURCES,
                "Fontes",
                DiagnosisLevel.OK,
                finding,
            )
        }
    }

    // ---------- objetivo ----------

    private fun objectiveCriterion(
        s34Status: DiagnosisS34Status,
        s34: S34Document?,
        sections: List<NoteSectionSnapshot>,
    ): DiagnosisCriterion {
        if (s34Status != DiagnosisS34Status.LINKED) {
            return DiagnosisCriterion(
                DiagnosisCriterionId.OBJECTIVE,
                "Objetivo",
                DiagnosisLevel.NOT_ASSESSED,
                "S-34 ausente ou sem estrutura.",
            )
        }
        val objective = s34?.objective?.trim().orEmpty()
        val noteObjectives = sections.count { !it.objective.isNullOrBlank() }
        return if (objective.isBlank()) {
            DiagnosisCriterion(
                DiagnosisCriterionId.OBJECTIVE,
                "Objetivo",
                DiagnosisLevel.NOT_INFORMED,
                "S-34 não declara objetivo; seções com objetivo: $noteObjectives/${sections.size}.",
                "Definir objetivo",
            )
        } else {
            val short = objective.take(140).let {
                if (objective.length > 140) "$it…" else it
            }
            DiagnosisCriterion(
                DiagnosisCriterionId.OBJECTIVE,
                "Objetivo",
                DiagnosisLevel.OK,
                "Objetivo: $short · seções com objetivo: $noteObjectives/${sections.size}.",
            )
        }
    }

    // ---------- pontos ----------

    private fun pointOf(section: NoteSectionSnapshot, number: Int): DiagnosisPoint {
        val totalSubs = section.subPoints.size
        val developedSubs = section.subPoints.count { it.hasDevelopment }
        val hasText = section.bodyText.isNotBlank()
        val level = when {
            hasText && (totalSubs == 0 || developedSubs == totalSubs) -> DiagnosisLevel.OK
            !hasText && totalSubs > 0 && developedSubs == totalSubs -> DiagnosisLevel.OK
            else -> DiagnosisLevel.ATTENTION
        }
        val finding = buildString {
            if (hasText) append("texto da seção") else append("sem texto na seção")
            if (totalSubs > 0) append(" · $developedSubs/$totalSubs subpontos")
        }
        return DiagnosisPoint(
            sectionId = section.id,
            number = number,
            title = section.title.ifBlank { "Ponto $number" },
            minutes = section.minutes,
            level = level,
            finding = finding,
            actionLabel = if (level == DiagnosisLevel.OK) "Abrir" else "Desenvolver",
        )
    }

    // ---------- prioridades / próximo passo ----------

    private fun prioritiesOf(
        s34Status: DiagnosisS34Status,
        structure: DiagnosisCriterion,
        alignment: DiagnosisCriterion,
        times: DiagnosisCriterion,
        development: DiagnosisCriterion,
        sources: DiagnosisCriterion,
        objective: DiagnosisCriterion,
        body: List<NoteSectionSnapshot>,
    ): List<String> = buildList {
        if (s34Status == DiagnosisS34Status.MISSING) {
            add("Vincular o S-34 à nota")
        } else if (s34Status == DiagnosisS34Status.NO_STRUCTURE) {
            add("Reimportar o S-34 com estrutura")
        }
        if (structure.level != DiagnosisLevel.OK) add("Completar a estrutura da nota")
        if (s34Status == DiagnosisS34Status.LINKED) {
            if (alignment.level != DiagnosisLevel.OK) {
                add("Alinhar os pontos e subpontos com o S-34")
            }
            if (times.level != DiagnosisLevel.OK) add("Ajustar os tempos dos pontos")
        }
        if (development.level != DiagnosisLevel.OK) {
            val first = body.firstOrNull { !it.hasContent }
            add(
                if (first != null) "Desenvolver o ponto “${first.title.ifBlank { "sem título" }}”"
                else "Desenvolver os pontos vazios"
            )
        }
        if (sources.level != DiagnosisLevel.OK) {
            add(
                if (sources.id == DiagnosisCriterionId.SOURCES &&
                    sources.level == DiagnosisLevel.NOT_ASSESSED
                ) {
                    "Adicionar as fontes do esboço"
                } else {
                    "Resolver as fontes pendentes"
                }
            )
        }
        if (s34Status == DiagnosisS34Status.LINKED &&
            objective.level != DiagnosisLevel.OK
        ) {
            add("Definir o objetivo do esboço")
        }
    }.distinct().take(3)

    private fun nextStepOf(
        s34Status: DiagnosisS34Status,
        structure: DiagnosisCriterion,
        alignment: DiagnosisCriterion,
        times: DiagnosisCriterion,
        development: DiagnosisCriterion,
        sources: DiagnosisCriterion,
        objective: DiagnosisCriterion,
        body: List<NoteSectionSnapshot>,
        s34Sections: List<S34Section>,
    ): DiagnosisNextStep {
        if (s34Status == DiagnosisS34Status.MISSING) return DiagnosisNextStep.LinkS34()
        if (s34Status == DiagnosisS34Status.NO_STRUCTURE) return DiagnosisNextStep.ReimportS34()
        if (structure.level != DiagnosisLevel.OK) {
            return DiagnosisNextStep.OpenNote("Abrir nota")
        }
        if (alignment.level != DiagnosisLevel.OK) {
            return DiagnosisNextStep.OpenNote("Revisar estrutura")
        }
        if (times.level != DiagnosisLevel.OK) {
            val divergent = body.zip(s34Sections).firstOrNull { (note, s34) ->
                val s34Minutes = s34.minutes?.takeIf { it > 0 } ?: return@firstOrNull false
                note.minutes <= 0 ||
                    abs(note.minutes - s34Minutes) >
                    maxOf(1, (s34Minutes * 0.20).roundToInt())
            }?.first
            return if (divergent != null) {
                DiagnosisNextStep.OpenSection(
                    sectionId = divergent.id,
                    title = divergent.title,
                    sectionNumber = body.indexOf(divergent) + 1,
                    label = "Ajustar tempos",
                )
            } else {
                DiagnosisNextStep.OpenNote("Ajustar tempos")
            }
        }
        val firstEmpty = body.firstOrNull { !it.hasContent }
        if (firstEmpty != null) {
            return DiagnosisNextStep.OpenSection(
                sectionId = firstEmpty.id,
                title = firstEmpty.title,
                sectionNumber = body.indexOf(firstEmpty) + 1,
                label = "Abrir o primeiro ponto vazio",
            )
        }
        if (development.level != DiagnosisLevel.OK) {
            val firstPartial = body.firstOrNull { section ->
                section.subPoints.any { !it.hasDevelopment }
            }
            if (firstPartial != null) {
                return DiagnosisNextStep.OpenSection(
                    sectionId = firstPartial.id,
                    title = firstPartial.title,
                    sectionNumber = body.indexOf(firstPartial) + 1,
                    label = "Abrir o primeiro ponto vazio",
                )
            }
        }
        if (sources.level != DiagnosisLevel.OK) {
            return DiagnosisNextStep.ShowSources(
                if (sources.action == "Aguardar indexação") "Acompanhar indexação"
                else "Mostrar fontes"
            )
        }
        if (objective.level != DiagnosisLevel.OK) {
            return DiagnosisNextStep.OpenNote("Definir objetivo")
        }
        val firstBody = body.firstOrNull()
        return if (firstBody != null) {
            DiagnosisNextStep.OpenSection(
                sectionId = firstBody.id,
                title = firstBody.title,
                sectionNumber = 1,
                label = "Abrir o primeiro ponto",
            )
        } else {
            DiagnosisNextStep.OpenNote()
        }
    }

    // ---------- resumo ----------

    private fun summaryOf(
        sections: List<NoteSectionSnapshot>,
        intro: List<NoteSectionSnapshot>,
        body: List<NoteSectionSnapshot>,
        conclusion: List<NoteSectionSnapshot>,
        sourceCounts: DiagnosisSourceCounts,
    ): String {
        if (sections.isEmpty()) return "Nota sem seções para diagnosticar."
        val totalMinutes = sections.sumOf { it.minutes }
        val developed = sections.count { it.hasContent }
        val refs = if (sourceCounts.total == 0) {
            "sem referências"
        } else {
            "${sourceCounts.resolved}/${sourceCounts.total} refs resolvidas"
        }
        return "Nota: ${sections.size} seções (${intro.size} intro, ${body.size} pontos, " +
            "${conclusion.size} conclusão) · $totalMinutes min · " +
            "$developed/${sections.size} com texto · $refs."
    }

    // ---------- helpers de texto ----------

    private fun titleKey(raw: String): String =
        normalizeText(raw)
            .replace(Regex("""\bpage\s+\d+\b"""), " ")
            .replace(Regex("""^\s*(ponto|topico)\s+\d+\b\s*"""), "")
            .replace(Regex("""^\s*\d+\b\s*"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

    /** Âncoras `§N` do conteúdo quando o S-34 não tem subseções estruturadas. */
    private val S34_ANCHOR_RE = Regex("""^§\s*\d+\s+(.+)$""")

    private fun contentAnchors(content: String): List<String> =
        content.lineSequence()
            .map { it.trim() }
            .mapNotNull { line -> S34_ANCHOR_RE.find(line)?.groupValues?.get(1) }
            .filterNot { it.startsWith("[") }
            .filterNot { it.contains("TEMPO TOTAL", ignoreCase = true) }
            .filterNot { it.startsWith("©") }
            .filterNot { it.startsWith("S-34") }
            .toList()

    private fun anchorsMatch(note: String, s34: String): Boolean {
        val n = normalizeText(note)
        val s = normalizeText(s34)
        if (n.isBlank() || s.isBlank()) return false
        return n == s || s.contains(n) || n.contains(s)
    }
}

/** Monta a entrada pura a partir dos modelos de domínio (sem Room). */
object OutlineDiagnosisInputBuilder {

    private val WHITESPACE = Regex("\\s+")

    private fun cleanText(raw: String): String =
        htmlToPlainText(raw).replace(WHITESPACE, " ").trim()

    fun build(
        s34: S34Document?,
        noteTitle: String?,
        sections: List<SpeechSection>,
        subPointsBySection: Map<String, List<SubPoint>>,
        sourceCounts: DiagnosisSourceCounts,
    ): OutlineDiagnosisInput = OutlineDiagnosisInput(
        s34 = s34,
        noteTitle = noteTitle?.trim()?.takeIf { it.isNotBlank() },
        sections = sections.sortedBy { it.order }.map { section ->
            NoteSectionSnapshot(
                id = section.id,
                order = section.order,
                role = section.role,
                title = section.title,
                minutes = section.minutes,
                bodyText = cleanText(section.contentHtml),
                objective = section.objective?.takeIf { it.isNotBlank() },
                subPoints = subPointsBySection[section.id].orEmpty()
                    .sortedBy { it.order }
                    .map { subPoint ->
                        NoteSubPointSnapshot(
                            id = subPoint.id,
                            order = subPoint.order,
                            outlineText = cleanText(subPoint.outlineText),
                            developedText = cleanText(subPoint.developedHtml),
                        )
                    },
            )
        },
        sourceCounts = sourceCounts,
    )
}
