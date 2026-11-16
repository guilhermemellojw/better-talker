package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.s34.S34RefType
import com.bettertalker.app.data.s34.S34Reference
import com.bettertalker.app.data.s34.S34Section
import com.bettertalker.app.domain.speech.SectionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PointReviewTest {

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

    private fun s34Section() = S34Section(
        id = "sec-2",
        order = 2,
        title = "COMO A VIDA ETERNA FOI PERDIDA",
        minutes = 4,
        content = "Conteúdo do S-34",
        references = listOf(
            S34Reference(
                type = S34RefType.BIBLE,
                rawText = "João 3:16",
                normalizedReference = "João|3|16",
                order = 1,
                sourceLine = 1,
            ),
        ),
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
    ) = PointReviewAnalyzer.analyze(
        PointReviewInput(
            section = section,
            pointNumber = 2,
            s34Section = s34,
            sourceCounts = sourceCounts,
            nextPointTitle = nextPointTitle,
        ),
        now = 123L,
    )

    @Test
    fun pontoVazioApontaPrimeiroSubpontoENaoReescreve() {
        val report = analyze(
            section = section(subs = listOf("Primeiro" to "", "Segundo" to "")),
        )

        assertEquals(DiagnosisLevel.ATTENTION, report.overall)
        assertTrue(report.strengths.any { it.contains("subponto") })
        assertTrue(report.improvements.first().contains("Comece pelo primeiro subponto"))
        assertTrue(report.improvements.size <= 3)
        assertEquals(123L, report.generatedAt)
        assertTrue(report.nextStep is PointReviewNextStep.OpenEditor)
    }

    @Test
    fun pontoCompletoOkComAplicacaoETransicao() {
        val report = analyze(
            section = section(
                bodyText = "texto do ponto",
                subs = listOf("Primeiro" to "desenvolvido", "Segundo" to "desenvolvido"),
            ),
        )

        assertEquals(DiagnosisLevel.OK, report.overall)
        assertTrue(report.strengths.any { it.contains("Todos os subpontos") })
        assertTrue(report.improvements.any { it.contains("aplicação prática") })
        assertTrue(report.improvements.any { it.contains("transição") })
        assertTrue(report.improvements.size <= 3)
    }

    @Test
    fun subpontoParcialViraMelhoria() {
        val report = analyze(
            section = section(
                bodyText = "",
                subs = listOf("Primeiro" to "desenvolvido", "Segundo" to ""),
            ),
        )

        assertEquals(DiagnosisLevel.ATTENTION, report.overall)
        assertTrue(report.improvements.any { it.contains("Desenvolva") })
        assertTrue(report.improvements.any { it.contains("ainda está só no esboço") })
    }

    @Test
    fun fontesPendentesViramMelhoria() {
        val report = analyze(
            sourceCounts = counts(total = 5, resolved = 3, blocking = 1, needsAction = 1),
            section = section(
                bodyText = "texto",
                subs = listOf("Primeiro" to "desenvolvido"),
            ),
        )

        assertEquals(DiagnosisLevel.ATTENTION, report.overall)
        assertTrue(report.improvements.any { it.contains("bloqueante") })
    }

    @Test
    fun textCurtoViraSugestaoDeEstimativa() {
        val report = analyze(
            section = section(
                bodyText = "texto curto",
                subs = listOf("Primeiro" to "desenvolvido"),
                minutes = 5,
            ),
        )

        assertTrue(report.improvements.any { it.contains("pode faltar desenvolvimento") })
    }

    @Test
    fun semS34NaoQuebra() {
        val report = analyze(s34 = null)

        assertTrue(report.strengths.none { it.contains("S-34") })
        assertFalse(report.improvements.isEmpty())
    }

    @Test
    fun introNaoEAceita() {
        val intro = section().copy(role = SectionRole.INTRO)
        try {
            PointReviewAnalyzer.analyze(PointReviewInput(intro, pointNumber = 1), now = 1L)
            assertTrue("esperava IllegalArgumentException", false)
        } catch (_: IllegalArgumentException) {
            // ok
        }
    }
}
