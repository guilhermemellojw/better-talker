package com.bettertalker.app.data.util

import com.bettertalker.app.S34Fixture
import com.bettertalker.app.domain.speech.SectionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #8b — roteamento do paste de material V&M: TALK gera (seção BODY + hint),
 * rejeitados postam mensagem, S-34 segue o fluxo genérico.
 *
 * O CopilotViewModel só consome estas funções puras (o fluxo de UI é fino
 * demais para a suíte JVM sem Robolectric).
 */
class VmPasteFlowTest {

    private val talkText = """
        TESOUROS DA PALAVRA DE DEUS, ponto 1. (10min)
        Eles perguntaram: "Por que vocês fazem isso?"
        Corpo do discurso com João 3:16.
    """.trimIndent()

    private val gemsText = """
        TESOUROS DA PALAVRA DE DEUS

        2. Joias espirituais
        (8 min)

        Corpo das joias.
    """.trimIndent()

    @Test
    fun talk_gera_secaoComParteEMensagemDeGeracao() {
        val plan = vmPastePlan(talkText)
        assertTrue("esperava Talk: $plan", plan is VmPastePlan.Talk)
        val talk = plan as VmPastePlan.Talk
        assertEquals(1, talk.talk.partNumber)
        assertTrue(talk.ignored.isEmpty())

        val section = vmPartSection(talk.talk, noteId = "n1", order = 0, id = "s1", now = 1L)
        assertEquals(SectionRole.BODY, section.role)
        assertEquals("Eles perguntaram: \"Por que vocês fazem isso?\"", section.title)
        assertEquals(10, section.minutes)
        assertTrue(section.contentHtml.contains("<p>"))
        assertTrue(section.contentHtml.contains("João 3:16"))

        val msg = vmTalkMessage(talk.talk, talk.ignored)
        assertTrue(msg.contains("Reconheci material V&M."))
        assertTrue(msg.contains("vou gerar discurso completo"))
        assertTrue(msg.contains("Parte 1 (TESOUROS DA PALAVRA DE DEUS)"))
    }

    @Test
    fun gems_rejeita_comMensagemSemDraft() {
        val plan = vmPastePlan(gemsText)
        assertTrue("esperava Rejected: $plan", plan is VmPastePlan.Rejected)
        val msg = vmRejectionMessage((plan as VmPastePlan.Rejected).parts)
        assertTrue(msg.contains("nenhuma das partes gera discurso ou demonstração"))
        assertTrue(msg.contains("Joias espirituais"))
        assertTrue(msg.contains("demonstrações (FAÇA SEU MELHOR 4-6)"))
    }

    @Test
    fun misto_processaTalkEInformaOResto() {
        val plan = vmPastePlan(talkText + "\n" + gemsText)
        assertTrue("esperava Talk: $plan", plan is VmPastePlan.Talk)
        val talk = plan as VmPastePlan.Talk
        assertEquals(1, talk.talk.partNumber)
        assertEquals(1, talk.ignored.size)
        assertEquals(VmNature.GEMS, talk.ignored.single().nature)
        val msg = vmTalkMessage(talk.talk, talk.ignored)
        assertTrue(msg.contains("Ignorei:"))
        assertTrue(msg.contains("Joias espirituais"))
    }

    @Test
    fun demo_comCabecalho_geraDemonstracao() {
        val plan = vmPastePlan(
            """
            FAÇA SEU MELHOR NO MINISTÉRIO, ponto 4. (3 min)
            Demonstração: Comece uma conversa
            Corpo.
            """.trimIndent()
        )
        assertTrue("esperava Demo: $plan", plan is VmPastePlan.Demo)
        val demo = plan as VmPastePlan.Demo
        assertEquals(4, demo.demo.partNumber)
        assertEquals(VmNature.DEMO, demo.demo.nature)

        val section = vmPartSection(demo.demo, noteId = "n1", order = 0, id = "s1", now = 1L)
        assertEquals(SectionRole.BODY, section.role)
        assertEquals(3, section.minutes)

        val msg = vmDemoMessage(demo.demo, demo.ignored)
        assertTrue(msg.contains("vou gerar a demonstração (diálogo)"))
        assertTrue(msg.contains("Parte 4 (FAÇA SEU MELHOR NO MINISTÉRIO)"))
    }

    @Test
    fun demo_semCabecalho_tambemAceita() {
        // Bug do usuário: parte solta de demonstração sem o cabeçalho da seção.
        val plan = vmPastePlan(
            """
            5. Cultivando o interesse (4 min)
            DE CASA EM CASA
            Fale sobre uma das verdades do apêndice A (lmd lição 7 ponto 4).
            """.trimIndent()
        )
        assertTrue("esperava Demo: $plan", plan is VmPastePlan.Demo)
        val demo = plan as VmPastePlan.Demo
        assertEquals(5, demo.demo.partNumber)
        assertEquals(VmNature.DEMO, demo.demo.nature)
        assertEquals(4, demo.demo.minutes)
    }

    @Test
    fun consideracao_eRejeitada() {
        val plan = vmPastePlan(
            """
            6. O que você diria? (6 min) Consideração.
            Pergunte à assistência como cada um responderia.
            """.trimIndent()
        )
        assertTrue("esperava Rejected: $plan", plan is VmPastePlan.Rejected)
        val rejected = plan as VmPastePlan.Rejected
        assertTrue(rejected.parts.any { it.nature == VmNature.CONSIDERATION })
    }

    @Test
    fun s34_naoEhVm_fluxoGenerico() {
        assertTrue(vmPastePlan(S34Fixture.TEXT) is VmPastePlan.Generic)
    }

    @Test
    fun materialNature_minutosDoHint() {
        assertEquals(10, materialMinutesOf("TALK:10min"))
        assertEquals(15, materialMinutesOf("TALK:15 min"))
        assertEquals(7, materialMinutesOf("DEMO:7min"))
        assertEquals(null, materialMinutesOf(null))
        assertEquals(null, materialMinutesOf("TALK:min"))
    }
}
