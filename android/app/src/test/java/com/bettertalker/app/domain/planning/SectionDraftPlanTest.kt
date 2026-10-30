package com.bettertalker.app.domain.planning

import com.bettertalker.app.domain.speech.SectionRole
import org.junit.Assert.assertEquals
import org.junit.Test

class SectionDraftPlanTest {

    private fun budget45() = OutputBudget.longText(
        LongTextRequest(
            kind = LongTextKind.SPEECH,
            scope = LongTextScope.SECTION,
            minutes = 45,
            sectionMinutes = 45,
        ),
    )

    private fun plan(
        role: SectionRole = SectionRole.BODY,
        minutes: Int,
        hasSubPoints: Boolean = false,
        subPointCount: Int = 0,
        kind: LongTextKind = LongTextKind.SPEECH,
        budget: LongTextBudget = OutputBudget.longText(
            LongTextRequest(
                kind = kind,
                scope = LongTextScope.SECTION,
                minutes = minutes,
                sectionMinutes = minutes,
            ),
        ),
    ) = sectionDraftPlan(
        role = role,
        sectionMinutes = minutes,
        kind = kind,
        hasSubPoints = hasSubPoints,
        subPointCount = subPointCount,
        budget = budget,
    )

    @Test
    fun quarentaECinco_viraDuasPartes23e22() {
        val p = plan(minutes = 45, budget = budget45())
        assertEquals(SectionDraftMode.CONTINUATION, p.mode)
        assertEquals(2, p.parts)
        assertEquals(listOf(23, 22), p.partMinutes)
        assertEquals(listOf(2990, 2860), p.partTargetWords)
        assertEquals(5850, p.totalTargetWords)
        assertEquals(45, p.totalMinutes)
    }

    @Test
    fun trinta_continuaSingle() {
        val p = plan(minutes = 30)
        assertEquals(SectionDraftMode.SINGLE, p.mode)
        assertEquals(1, p.parts)
        assertEquals(listOf(30), p.partMinutes)
        assertEquals(listOf(3900), p.partTargetWords)
    }

    @Test
    fun vinteSemSubpontos_continuaSingle() {
        val p = plan(minutes = 20)
        assertEquals(SectionDraftMode.SINGLE, p.mode)
        assertEquals(1, p.parts)
    }

    @Test
    fun vinteComSubpontos_viraBySubPoint() {
        val p = plan(minutes = 20, hasSubPoints = true, subPointCount = 5)
        assertEquals(SectionDraftMode.BY_SUBPOINT, p.mode)
        assertEquals(5, p.parts)
        assertEquals(listOf(4, 4, 4, 4, 4), p.partMinutes)
        assertEquals(listOf(520, 520, 520, 520, 520), p.partTargetWords)
    }

    @Test
    fun quarentaECincoComSubpontos_naoViraContinuation() {
        val p = plan(minutes = 45, hasSubPoints = true, subPointCount = 5)
        assertEquals(SectionDraftMode.BY_SUBPOINT, p.mode)
        assertEquals(5, p.parts)
        assertEquals(45, p.totalMinutes)
    }

    @Test
    fun introEConclusao_continuamSingle() {
        assertEquals(SectionDraftMode.SINGLE, plan(role = SectionRole.INTRO, minutes = 45).mode)
        assertEquals(SectionDraftMode.SINGLE, plan(role = SectionRole.CONCLUSION, minutes = 45).mode)
    }

    @Test
    fun demo_continuaSingle() {
        val p = plan(minutes = 45, kind = LongTextKind.DEMO)
        assertEquals(SectionDraftMode.SINGLE, p.mode)
        assertEquals(1, p.parts)
        assertEquals(0, p.totalTargetWords)
    }

    @Test
    fun distribuicaoNaoPerdeMinuto() {
        assertEquals(listOf(12, 11, 11, 11), distributeMinutes(45, 4))
        assertEquals(45, distributeMinutes(45, 4).sum())
        assertEquals(listOf(23, 22), distributeMinutes(45, 2))
        assertEquals(listOf(10, 10, 10), distributeMinutes(30, 3))
        assertEquals(listOf(1, 0), distributeMinutes(1, 2))
    }

    @Test
    fun orcamento45_temDuasPartesE4488() {
        val b = budget45()
        assertEquals(2, b.parts)
        assertEquals(4488, b.maxTokens)
    }
}
