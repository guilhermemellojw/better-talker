package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.s34.S34Document
import com.bettertalker.app.data.s34.S34Section
import com.bettertalker.app.data.s34.S34Subsection
import com.bettertalker.app.domain.speech.SectionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutlineDiagnosisTest {

    private fun noteSection(
        order: Int,
        role: SectionRole,
        title: String,
        minutes: Int = 5,
        body: String = "texto desenvolvido",
        subs: List<String> = emptyList(),
    ) = NoteSectionSnapshot(
        id = "note-$order",
        order = order,
        role = role,
        title = title,
        minutes = minutes,
        bodyText = body,
        subPoints = subs.mapIndexed { index, anchor ->
            NoteSubPointSnapshot(
                id = "sub-$order-$index",
                order = index,
                outlineText = anchor,
                developedText = "desenvolvido",
            )
        },
    )

    private fun s34Section(
        order: Int,
        title: String,
        minutes: Int? = 5,
        subs: List<String> = emptyList(),
        content: String = "Conteúdo de $title",
    ) = S34Section(
        id = "sec-$order",
        order = order,
        title = title,
        minutes = minutes,
        content = content,
        subsections = subs.mapIndexed { index, subContent ->
            S34Subsection(
                id = "sec-$order-${index + 1}",
                order = index + 1,
                content = subContent,
                sourceLine = 1,
            )
        },
        sourceLine = 1,
    )

    private fun s34(
        objective: String? = "Objetivo do discurso",
        sections: List<S34Section> = (1..5).map {
            s34Section(it, "Ponto $it")
        },
    ) = S34Document(
        id = "s34-test",
        title = "Esboço de teste",
        objective = objective,
        sections = sections,
    )

    private fun perfectSections(): List<NoteSectionSnapshot> = buildList {
        add(noteSection(0, SectionRole.INTRO, "Introdução", minutes = 1))
        (1..5).forEach { add(noteSection(it, SectionRole.BODY, "Ponto $it")) }
        add(noteSection(6, SectionRole.CONCLUSION, "Conclusão", minutes = 1))
    }

    private fun sources(
        total: Int = 5,
        resolved: Int = 5,
        indexing: Int = 0,
        noText: Int = 0,
        blocking: Int = 0,
        needsAction: Int = 0,
    ) = DiagnosisSourceCounts(
        total = total,
        resolved = resolved,
        indexing = indexing,
        noText = noText,
        blocking = blocking,
        needsAction = needsAction,
    )

    private fun analyze(
        sections: List<NoteSectionSnapshot> = perfectSections(),
        s34: S34Document? = s34(),
        sourceCounts: DiagnosisSourceCounts = sources(),
    ) = OutlineDiagnosisAnalyzer.analyze(
        OutlineDiagnosisInput(
            s34 = s34,
            noteTitle = "Nota de teste",
            sections = sections,
            sourceCounts = sourceCounts,
        ),
        now = 123L,
    )

    private fun criterion(report: OutlineDiagnosis, id: DiagnosisCriterionId) =
        report.criteria.first { it.id == id }

    @Test
    fun esbocoPerfeitoOkSemPrioridadesEAbrePrimeiroPonto() {
        val report = analyze()

        assertEquals(DiagnosisLevel.OK, report.overall)
        assertEquals(DiagnosisS34Status.LINKED, report.s34Status)
        assertEquals(6, report.criteria.size)
        assertTrue(report.criteria.all { it.level == DiagnosisLevel.OK })
        assertTrue(report.priorities.isEmpty())
        assertTrue(report.points.all { it.level == DiagnosisLevel.OK })
        val next = report.nextStep
        assertTrue(next is DiagnosisNextStep.OpenSection)
        next as DiagnosisNextStep.OpenSection
        assertEquals("note-1", next.sectionId)
        assertEquals("Abrir o primeiro ponto", next.label)
    }

    @Test
    fun semS34MissingEProximoPassoVincular() {
        val report = analyze(s34 = null)

        assertEquals(DiagnosisLevel.MISSING, report.overall)
        assertEquals(DiagnosisS34Status.MISSING, report.s34Status)
        assertEquals(DiagnosisLevel.NOT_ASSESSED, criterion(report, DiagnosisCriterionId.ALIGNMENT).level)
        assertEquals(DiagnosisLevel.NOT_ASSESSED, criterion(report, DiagnosisCriterionId.TIMES).level)
        assertEquals(DiagnosisLevel.NOT_ASSESSED, criterion(report, DiagnosisCriterionId.OBJECTIVE).level)
        assertTrue(report.nextStep is DiagnosisNextStep.LinkS34)
        assertTrue(report.priorities.first().contains("Vincular"))
    }

    @Test
    fun s34SemEstruturaPedeReimportar() {
        val report = analyze(s34 = s34(sections = emptyList()))

        assertEquals(DiagnosisLevel.MISSING, report.overall)
        assertEquals(DiagnosisS34Status.NO_STRUCTURE, report.s34Status)
        assertTrue(report.nextStep is DiagnosisNextStep.ReimportS34)
        assertTrue(report.priorities.first().contains("Reimportar"))
    }

    @Test
    fun faltaConclusaoAttentionComPrioridadeEstrutura() {
        val sections = perfectSections().filter { it.role != SectionRole.CONCLUSION }
        val report = analyze(sections = sections)

        assertEquals(DiagnosisLevel.ATTENTION, report.overall)
        assertEquals(DiagnosisLevel.ATTENTION, criterion(report, DiagnosisCriterionId.STRUCTURE).level)
        assertTrue(report.priorities.any { it.contains("estrutura", ignoreCase = true) })
        assertTrue(report.nextStep is DiagnosisNextStep.OpenNote)
    }

    @Test
    fun estruturaComDuasIntroducoesNaoEhOk() {
        val sections = perfectSections() +
            noteSection(7, SectionRole.INTRO, "Introdução extra", minutes = 1)
        val report = analyze(sections = sections)

        val structure = criterion(report, DiagnosisCriterionId.STRUCTURE)
        assertEquals(DiagnosisLevel.ATTENTION, structure.level)
        assertTrue(structure.finding.contains("2 introduções"))
        assertTrue(report.priorities.any { it.contains("estrutura", ignoreCase = true) })
    }

    @Test
    fun semS34NaoAdicionaPrioridadesDeAlinhamentoNemTempos() {
        val report = analyze(s34 = null)

        assertTrue(report.nextStep is DiagnosisNextStep.LinkS34)
        assertTrue(report.priorities.first().contains("Vincular"))
        assertFalse(report.priorities.any { it.contains("Alinhar", ignoreCase = true) })
        assertFalse(report.priorities.any { it.contains("tempos", ignoreCase = true) })
    }

    @Test
    fun temposDivergentesAttentionEAjustarTempos() {
        val s34 = s34(sections = (1..5).map { s34Section(it, "Ponto $it", minutes = 7) })
        val report = analyze(s34 = s34)

        val times = criterion(report, DiagnosisCriterionId.TIMES)
        assertEquals(DiagnosisLevel.ATTENTION, times.level)
        assertTrue(times.finding.contains("nota 25 min"))
        assertTrue(times.finding.contains("S-34 35 min"))
        val next = report.nextStep
        assertTrue(next is DiagnosisNextStep.OpenSection)
        assertEquals("Ajustar tempos", (next as DiagnosisNextStep.OpenSection).label)
        assertEquals("note-1", next.sectionId)
        assertTrue(report.priorities.any { it.contains("tempos", ignoreCase = true) })
    }

    @Test
    fun s34SemMinutosNuncaOk() {
        val s34 = s34(sections = (1..5).map { s34Section(it, "Ponto $it", minutes = null) })
        val report = analyze(s34 = s34)

        val times = criterion(report, DiagnosisCriterionId.TIMES)
        assertEquals(DiagnosisLevel.ATTENTION, times.level)
        assertTrue(times.finding.contains("S-34 sem minutos"))
        assertEquals(DiagnosisLevel.ATTENTION, report.overall)
    }

    @Test
    fun pontoVazioAttentionEProximoPassoAbrePontoVazio() {
        val sections = perfectSections().map { section ->
            if (section.order == 3) section.copy(bodyText = "", subPoints = emptyList())
            else section
        }
        val report = analyze(sections = sections)

        assertEquals(DiagnosisLevel.ATTENTION, report.overall)
        val point = report.points.first { it.sectionId == "note-3" }
        assertEquals(DiagnosisLevel.ATTENTION, point.level)
        assertEquals("Desenvolver", point.actionLabel)
        val next = report.nextStep
        assertTrue(next is DiagnosisNextStep.OpenSection)
        next as DiagnosisNextStep.OpenSection
        assertEquals("note-3", next.sectionId)
        assertEquals("Abrir o primeiro ponto vazio", next.label)
    }

    @Test
    fun fontesSemRefsNaoAvaliadoNaoOk() {
        val report = analyze(sourceCounts = sources(total = 0, resolved = 0))

        assertEquals(DiagnosisLevel.ATTENTION, report.overall)
        assertEquals(
            DiagnosisLevel.NOT_ASSESSED,
            criterion(report, DiagnosisCriterionId.SOURCES).level,
        )
        assertTrue(report.nextStep is DiagnosisNextStep.ShowSources)
    }

    @Test
    fun fontesBloqueantesMostrarFontes() {
        val report = analyze(
            sourceCounts = sources(total = 5, resolved = 3, blocking = 2, needsAction = 2),
        )

        val sources = criterion(report, DiagnosisCriterionId.SOURCES)
        assertEquals(DiagnosisLevel.ATTENTION, sources.level)
        assertTrue(sources.finding.contains("2 bloqueantes"))
        assertTrue(report.nextStep is DiagnosisNextStep.ShowSources)
    }

    @Test
    fun fontesIndexandoAcompanharIndexacao() {
        val report = analyze(
            sourceCounts = sources(total = 5, resolved = 4, indexing = 1),
        )

        val sources = criterion(report, DiagnosisCriterionId.SOURCES)
        assertEquals(DiagnosisLevel.ATTENTION, sources.level)
        assertTrue(sources.finding.contains("1 indexando"))
        val next = report.nextStep
        assertTrue(next is DiagnosisNextStep.ShowSources)
        assertEquals("Acompanhar indexação", (next as DiagnosisNextStep.ShowSources).label)
    }

    @Test
    fun subpontosDivergentesAttention() {
        val s34 = s34(sections = (1..5).map {
            s34Section(it, "Ponto $it", subs = listOf("Primeiro subponto", "Segundo subponto"))
        })
        val report = analyze(s34 = s34)

        // A nota não tem subpontos; a contagem já diverge.
        assertEquals(
            DiagnosisLevel.ATTENTION,
            criterion(report, DiagnosisCriterionId.ALIGNMENT).level,
        )
        assertTrue(criterion(report, DiagnosisCriterionId.ALIGNMENT).finding.contains("subpontos"))
    }

    @Test
    fun s34SemSubsecoesUsaAncorasDoConteudo() {
        // S-34 real do debug: as subseções vêm como linhas §N no content.
        val s34 = s34(sections = (1..5).map {
            s34Section(
                order = it,
                title = "Ponto $it",
                content = "§1 Primeiro\n§2 Segundo",
            )
        })
        val sections = perfectSections().map { section ->
            if (section.role == SectionRole.BODY) {
                section.copy(
                    subPoints = listOf(
                        NoteSubPointSnapshot(
                            id = "a-${section.order}",
                            order = 0,
                            outlineText = "Primeiro",
                            developedText = "desenvolvido",
                        ),
                        NoteSubPointSnapshot(
                            id = "b-${section.order}",
                            order = 1,
                            outlineText = "Segundo",
                            developedText = "desenvolvido",
                        ),
                    ),
                )
            } else {
                section
            }
        }
        val report = analyze(sections = sections, s34 = s34)

        assertEquals(
            DiagnosisLevel.OK,
            criterion(report, DiagnosisCriterionId.ALIGNMENT).level,
        )
    }

    @Test
    fun objetivoAusenteNaoInformadoSemFalsoOk() {
        val report = analyze(s34 = s34(objective = null))

        val objective = criterion(report, DiagnosisCriterionId.OBJECTIVE)
        assertEquals(DiagnosisLevel.NOT_INFORMED, objective.level)
        assertEquals(DiagnosisLevel.ATTENTION, report.overall)
        assertTrue(report.priorities.any { it.contains("objetivo", ignoreCase = true) })
        val next = report.nextStep
        assertTrue(next is DiagnosisNextStep.OpenNote)
        assertEquals("Definir objetivo", (next as DiagnosisNextStep.OpenNote).label)
    }

    @Test
    fun prioridadesMaximoTresENextStepRespeitaOrdem() {
        val s34 = s34(
            sections = (1..5).map { s34Section(it, "Ponto $it", minutes = 7) },
        )
        val sections = perfectSections().map { section ->
            if (section.order == 2) section.copy(bodyText = "", subPoints = emptyList())
            else section
        }
        val report = analyze(
            sections = sections,
            s34 = s34,
            sourceCounts = sources(total = 5, resolved = 3, blocking = 2, needsAction = 2),
        )

        assertTrue(report.priorities.size <= 3)
        // Estrutura OK; tempos vêm antes de ponto vazio/fontes.
        val next = report.nextStep
        assertTrue(next is DiagnosisNextStep.OpenSection)
        assertEquals("Ajustar tempos", (next as DiagnosisNextStep.OpenSection).label)
    }

    @Test
    fun notaVaziaComS34PedeAbrirNota() {
        val report = analyze(sections = emptyList())

        assertEquals(DiagnosisLevel.MISSING, report.overall)
        assertEquals(DiagnosisLevel.MISSING, criterion(report, DiagnosisCriterionId.STRUCTURE).level)
        assertTrue(report.nextStep is DiagnosisNextStep.OpenNote)
    }

    @Test
    fun notaVaziaSemS34PedeVincular() {
        val report = analyze(sections = emptyList(), s34 = null)

        assertEquals(DiagnosisLevel.MISSING, report.overall)
        assertTrue(report.nextStep is DiagnosisNextStep.LinkS34)
    }

    @Test
    fun geradoEmEntraPorParametro() {
        val report = analyze()
        assertEquals(123L, report.generatedAt)
    }

    @Test
    fun alinhamentoComNotaDeSeteSecoesES34DeCincoNaoFalha() {
        val report = analyze()
        assertEquals(7, perfectSections().size)
        assertEquals(5, s34().sections.size)
        assertEquals(
            DiagnosisLevel.OK,
            criterion(report, DiagnosisCriterionId.ALIGNMENT).level,
        )
    }

    @Test
    fun desenvolvimentoParcialEmSubpontosAttention() {
        val sections = perfectSections().map { section ->
            if (section.order == 2) {
                section.copy(
                    bodyText = "",
                    subPoints = listOf(
                        NoteSubPointSnapshot("s1", 0, "A", "desenvolvido"),
                        NoteSubPointSnapshot("s2", 1, "B", ""),
                    ),
                )
            } else section
        }
        val report = analyze(sections = sections)

        assertEquals(DiagnosisLevel.ATTENTION, report.overall)
        assertEquals(
            DiagnosisLevel.ATTENTION,
            criterion(report, DiagnosisCriterionId.DEVELOPMENT).level,
        )
        val point = report.points.first { it.sectionId == "note-2" }
        assertEquals(DiagnosisLevel.ATTENTION, point.level)
        assertFalse(point.finding.isBlank())
    }

    @Test
    fun visibilidadeMostraSoAtencaoPorPadrao() {
        val points = listOf(
            point("s1", 1, DiagnosisLevel.OK),
            point("s2", 2, DiagnosisLevel.ATTENTION),
            point("s3", 3, DiagnosisLevel.OK),
        )
        val visibility = diagnosisPointsVisibility(points, expanded = false)

        assertEquals(listOf("s2"), visibility.visible.map { it.sectionId })
        assertTrue(visibility.showToggle)
        assertFalse(visibility.compactOnly)
    }

    @Test
    fun visibilidadeTudoOkCompactoEExpansivel() {
        val points = listOf(
            point("s1", 1, DiagnosisLevel.OK),
            point("s2", 2, DiagnosisLevel.OK),
        )
        val collapsed = diagnosisPointsVisibility(points, expanded = false)
        assertTrue(collapsed.visible.isEmpty())
        assertTrue(collapsed.compactOnly)
        assertTrue(collapsed.showToggle)

        val expanded = diagnosisPointsVisibility(points, expanded = true)
        assertEquals(2, expanded.visible.size)
        assertFalse(expanded.compactOnly)
    }

    @Test
    fun visibilidadeSemPontosNaoMostraToggle() {
        val visibility = diagnosisPointsVisibility(emptyList(), expanded = false)
        assertTrue(visibility.visible.isEmpty())
        assertFalse(visibility.showToggle)
        assertFalse(visibility.compactOnly)
    }

    @Test
    fun visibilidadeComTodosEmAtencaoNaoMostraToggleRedundante() {
        val points = listOf(
            point("s1", 1, DiagnosisLevel.ATTENTION),
            point("s2", 2, DiagnosisLevel.ATTENTION),
        )
        val visibility = diagnosisPointsVisibility(points, expanded = false)

        assertEquals(2, visibility.visible.size)
        assertFalse(visibility.showToggle)
        assertFalse(visibility.compactOnly)
    }

    private fun point(id: String, number: Int, level: DiagnosisLevel) = DiagnosisPoint(
        sectionId = id,
        number = number,
        title = "Ponto $number",
        minutes = 5,
        level = level,
        finding = "ok",
        actionLabel = "Abrir",
    )
}
