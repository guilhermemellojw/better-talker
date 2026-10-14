package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #8c (GAP-4): instrução discreta só com decisão — e sem duplicar. */
class InsertInstructionTest {

    @Test
    fun comDecisao_instrucaoAnexada() {
        val out = appendInsertInstruction(
            "Fica valendo esta redação.",
            "〈decisão〉Texto final confirmado.〈/decisão〉",
        )
        assertTrue(out.endsWith(INSERT_INSTRUCTION))
        assertTrue(out.startsWith("Fica valendo esta redação."))
    }

    @Test
    fun semDecisao_naoAnexa() {
        assertEquals(
            "Resposta comum sem decisão.",
            appendInsertInstruction("Resposta comum sem decisão.", "Resposta comum sem decisão."),
        )
    }

    @Test
    fun chamadaDupla_semDuplicacao() {
        val once = appendInsertInstruction("Texto.", "〈decisão〉decidido.〈/decisão〉")
        val twice = appendInsertInstruction(once, "〈decisão〉decidido.〈/decisão〉")
        assertEquals(once, twice)
        assertEquals(1, Regex(Regex.escape(INSERT_INSTRUCTION)).findAll(twice).count())
    }
}
