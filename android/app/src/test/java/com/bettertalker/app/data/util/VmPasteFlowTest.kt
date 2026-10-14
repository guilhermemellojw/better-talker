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

        val section = vmTalkSection(talk.talk, noteId = "n1", order = 0, id = "s1", now = 1L)
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
        assertTrue(msg.contains("nenhuma das partes é um discurso"))
        assertTrue(msg.contains("Joias espirituais"))
        assertTrue(msg.contains("O app processa apenas discursos"))
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
    fun demo_reconhecido_comAvisoEmBreve() {
        val plan = vmPastePlan(
            """
            FAÇA SEU MELHOR NO MINISTÉRIO, ponto 4. (3 min)
            Demonstração: Comece uma conversa
            Corpo.
            """.trimIndent()
        )
        assertTrue(plan is VmPastePlan.Rejected)
        val msg = vmRejectionMessage((plan as VmPastePlan.Rejected).parts)
        assertTrue(msg.contains("demonstração (em breve)"))
    }

    @Test
    fun s34_naoEhVm_fluxoGenerico() {
        assertTrue(vmPastePlan(S34Fixture.TEXT) is VmPastePlan.Generic)
    }

    @Test
    fun materialNature_minutosDoHint() {
        assertEquals(10, materialMinutesOf("TALK:10min"))
        assertEquals(15, materialMinutesOf("TALK:15 min"))
        assertEquals(null, materialMinutesOf(null))
        assertEquals(null, materialMinutesOf("TALK:min"))
    }
}
