package com.bettertalker.app.ui.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #ui-polish — merge do "Continuar gerando": o marcador de corte "[...]" sai
 * e a continuação entra como parágrafo novo na MESMA resposta. Puro.
 */
class ContinueGenerationTest {

    @Test
    fun continuacao_removeMarcadorDeCorteEAnexa() {
        assertEquals(
            "Primeira parte.\n\nSegunda parte.",
            mergeContinuation("Primeira parte. [...]", "Segunda parte."),
        )
    }

    @Test
    fun continuacao_semMarcador_apenasAnexa() {
        assertEquals("Antiga\n\nNova", mergeContinuation("Antiga", "Nova"))
    }

    @Test
    fun continuacao_textoVazio_mantemOriginalIntacto() {
        assertEquals("Original [...]", mergeContinuation("Original [...]", "   "))
    }

    @Test
    fun continuacao_baseVazia_usaSoAContinuacao() {
        assertEquals("Nova", mergeContinuation("", "Nova"))
    }

    @Test
    fun instrucaoDeContinuacao_naoVazia() {
        assertTrue(CONTINUE_INSTRUCTION.isNotBlank())
    }
}
