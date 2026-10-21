package com.bettertalker.app.data.copilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * exp/chat-quality (2026-10-21) — H1: o dossiê entra no prompt só quando a
 * pergunta fala do conteúdo (ref/seção/publicação/tópico).
 */
class DossierGateTest {

    @Test
    fun perguntaGenerica_naoInjetaDossie() {
        assertFalse(shouldInjectDossier("me ajude a entender o que é arrependimento"))
        assertFalse(shouldInjectDossier("como melhorar minha oratória?"))
        assertFalse(shouldInjectDossier(""))
    }

    @Test
    fun perguntaComRefBiblica_injetaDossie() {
        assertTrue(shouldInjectDossier("o que Jer. 45:3 ensina?"))
        assertTrue(shouldInjectDossier("explique 1 Coríntios 4:6"))
    }

    @Test
    fun perguntaComPublicacao_injetaDossie() {
        assertTrue(shouldInjectDossier("veja w25.07 22 §§ 8-9"))
        assertTrue(shouldInjectDossier("o que diz a w25.07?"))
    }

    @Test
    fun perguntaComSecaoInjetaDossie() {
        assertTrue(shouldInjectDossier("revise a introdução"))
        assertTrue(shouldInjectDossier("qual é a ideia do ponto 2?"))
        assertTrue(shouldInjectDossier("desenvolva o Tópico 1"))
        assertTrue(shouldInjectDossier("trabalhe a conclusão do discurso"))
    }
}
