package com.bettertalker.app.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #14-fix: "Conversar sobre este tópico" ativa a seção (borda + canal de
 * bloco) — só alvos de SEÇÃO ativam; sub-ponto/nulo apenas empurram contexto.
 */
class ChatActivationTest {

    private val titles = mapOf("s1" to "Introdução", "s2" to "Conclusão")

    @Test
    fun sectionTarget_ativaComTitulo() {
        assertEquals(
            "s2" to "Conclusão",
            chatAboutActivation(DraftTarget.Section("s2"), titles),
        )
    }

    @Test
    fun subPointTarget_naoAtiva() {
        assertNull(chatAboutActivation(DraftTarget.SubPoint("s2", "sp1"), titles))
    }

    @Test
    fun targetNulo_naoAtiva() {
        assertNull(chatAboutActivation(null, titles))
    }

    @Test
    fun secaoDesconhecida_naoAtiva() {
        assertNull(chatAboutActivation(DraftTarget.Section("sX"), titles))
    }
}
