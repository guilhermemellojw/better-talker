package com.bettertalker.app.data.copilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #8f — rota de Necessidades Locais: reconhece a moldura, não sequestra. Puro. */
class LocalNeedsRouterTest {

    @Test
    fun discursoCurtoSobre() {
        assertTrue(ChatRouter.isLocalNeedsCommand("cria um discurso curto sobre esperança"))
    }

    @Test
    fun necessidadesLocais() {
        assertTrue(
            ChatRouter.isLocalNeedsCommand(
                "preciso de um discurso para as necessidades locais sobre perdão"
            )
        )
    }

    @Test
    fun nlSobre() {
        assertTrue(ChatRouter.isLocalNeedsCommand("gera uma NL sobre fé"))
    }

    @Test
    fun discursoDeMinutos() {
        assertTrue(ChatRouter.isLocalNeedsCommand("discurso de 5 minutos sobre X"))
    }

    @Test
    fun naoSequestraOratoria() {
        assertFalse(ChatRouter.isLocalNeedsCommand("desenvolva o ponto 1"))
    }

    @Test
    fun naoSequestraAngulos() {
        assertFalse(ChatRouter.isLocalNeedsCommand("3 ângulos para X"))
    }
}
