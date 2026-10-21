package com.bettertalker.app.data.copilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatExperimentFlagsTest {

    @Test
    fun perguntaGenerica_naoInjetaDossie() {
        assertFalse(shouldInjectDossier("me ajude a entender o que é arrependimento"))
        assertFalse(shouldInjectDossier("como melhorar minha oratória?"))
    }

    @Test
    fun perguntaComRefOuSecao_injetaDossie() {
        assertTrue(shouldInjectDossier("o que Jer. 45:3 ensina?"))
        assertTrue(shouldInjectDossier("revise a introdução"))
        assertTrue(shouldInjectDossier("veja w25.07 22"))
        assertTrue(shouldInjectDossier("qual é a ideia do ponto 2?"))
    }

    @Test
    fun defaultsPreservamComportamentoAtual() {
        val f = ChatExperimentFlags.DEFAULT
        assertTrue(f.injectDossierAlways)
        assertTrue(f.enableCardIntent)
        assertTrue(f.includeMentorHint)
        assertFalse(f.useFullHistory)
        assertFalse(f.useUnifiedPersona)
        assertTrue(f.temperature == 0.2f)
    }
}
