package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.s34.S34Reference
import com.bettertalker.app.data.s34.S34RefType
import com.bettertalker.app.data.s34.S34Section
import com.bettertalker.app.domain.speech.SectionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PointCoachingTest {

    private fun section(
        bodyText: String = "",
        subs: List<Pair<String, String>> = emptyList(),
        minutes: Int = 4,
    ) = NoteSectionSnapshot(
        id = "section-2",
        order = 2,
        role = SectionRole.BODY,
        title = "COMO A VIDA ETERNA FOI PERDIDA",
        minutes = minutes,
        bodyText = bodyText,
        subPoints = subs.mapIndexed { index, (outline, developed) ->
            NoteSubPointSnapshot(
                id = "sub-$index",
                order = index,
                outlineText = outline,
                developedText = developed,
            )
        },
    )

    private fun s34Section(refs: List<String> = listOf("João 3:16")) = S34Section(
        id = "sec-2",
        order = 2,
        title = "COMO A VIDA ETERNA FOI PERDIDA",
        minutes = 4,
        content = "Conteúdo do S-34",
        references = refs.mapIndexed { index, raw ->
            S34Reference(
                type = S34RefType.BIBLE,
                rawText = raw,
                normalizedReference = raw,
                order = index + 1,
                sourceLine = 1,
            )
        },
        sourceLine = 1,
    )

    private fun counts(
        total: Int = 5,
        resolved: Int = 5,
        indexing: Int = 0,
        noText: Int = 0,
        blocking: Int = 0,
        needsAction: Int = 0,
    ) = DiagnosisSourceCounts(total, resolved, indexing, noText, blocking, needsAction)

    private fun analyze(
        section: NoteSectionSnapshot = section(),
        s34: S34Section? = s34Section(),
        sourceCounts: DiagnosisSourceCounts = counts(),
        nextPointTitle: String? = "COMO É POSSÍVEL TER VIDA ETERNA",
    ) = PointCoachingAnalyzer.analyze(
        PointCoachingInput(
            section = section,
            pointNumber = 2,
            s34Section = s34,
            sourceCounts = sourceCounts,
            nextPointTitle = nextPointTitle,
        ),
        now = 123L,
    )

    @Test
    fun pontoVazioPedeDesenvolvimentoEAbreEditor() {
        val report = analyze(
            section = section(subs = listOf("Primeiro" to "", "Segundo" to "")),
        )

        assertEquals(DiagnosisLevel.ATTENTION, report.overall)
        assertEquals(123L, report.generatedAt)
        assertTrue(report.reading.contains("0/2 subpontos"))
        assertTrue(report.steps.first().contains("Leia o ponto"))
        assertTrue(report.steps.any { it.contains("Desenvolva") })
        assertTrue(report.steps.any { it.contains("João 3:16") })
        assertTrue(report.steps.any { it.contains("transição") })
        assertTrue(report.nextStep is PointCoachingNextStep.OpenEditor)
        assertEquals("section-2", (report.nextStep as PointCoachingNextStep.OpenEditor).sectionId)
    }

    @Test
    fun pontoCompletoOkEPedeRevisao() {
        val report = analyze(
            section = section(
                bodyText = "texto do ponto",
                subs = listOf("Primeiro" to "desenvolvido", "Segundo" to "desenvolvido"),
            ),
        )

        assertEquals(DiagnosisLevel.OK, report.overall)
        assertTrue(report.reading.contains("2/2 subpontos"))
        assertTrue(report.steps.first().contains("Leia o ponto em voz alta"))
        assertTrue(report.steps.any { it.contains("Ajuste a aplicação") })
        assertTrue(report.findings.first { it.label == "Subpontos" }.level == DiagnosisLevel.OK)
    }

    @Test
    fun semS34NaoAvaliadoNaoQuebra() {
        val report = analyze(s34 = null)

        val finding = report.findings.first { it.label == "S-34" }
        assertEquals(DiagnosisLevel.NOT_ASSESSED, finding.level)
        assertTrue(finding.detail.contains("Não encontrei"))
    }

    @Test
    fun fontesPendentesAttention() {
        val report = analyze(
            sourceCounts = counts(total = 5, resolved = 3, indexing = 1, blocking = 1, needsAction = 1),
        )

        val finding = report.findings.first { it.label == "Fontes da nota" }
        assertEquals(DiagnosisLevel.ATTENTION, finding.level)
        assertTrue(finding.detail.contains("indexando"))
    }

    @Test
    fun semFontesNaoAvaliado() {
        val report = analyze(sourceCounts = counts(total = 0, resolved = 0))

        val finding = report.findings.first { it.label == "Fontes da nota" }
        assertEquals(DiagnosisLevel.NOT_ASSESSED, finding.level)
    }

    @Test
    fun introNaoEAceitaPeloAnalisador() {
        val intro = section().copy(role = SectionRole.INTRO)
        try {
            PointCoachingAnalyzer.analyze(
                PointCoachingInput(intro, pointNumber = 1),
                now = 1L,
            )
            assertTrue("esperava IllegalArgumentException", false)
        } catch (_: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun passosNaoPassamDeQuatro() {
        val report = analyze(
            section = section(
                subs = listOf("A" to "", "B" to "", "C" to ""),
            ),
        )

        assertTrue(report.steps.size <= 4)
        assertFalse(report.steps.isEmpty())
    }
}
