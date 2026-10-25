package com.bettertalker.app.data.copilot

import com.bettertalker.app.domain.planning.GenerationProfile
import com.bettertalker.app.domain.speech.DiscourseType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fase A — personas de mentoria separadas por tipo de geração. */
class GenerationPromptsTest {

    /** Corpo da persona, sem o guia de linguagem apensado. */
    private fun body(prompt: String): String =
        prompt.substringBefore("## DIRETRIZES DE LINGUAGEM")

    @Test
    fun speechMentor_orientaEstudoBiblicoENaoTemFormatoDeDialogo() {
        val p = body(GenerationPrompts.SPEECH_MENTOR)
        assertTrue(p.contains("orientador"))
        assertTrue(p.contains("estudo bíblico"))
        assertFalse(p.contains("irmão"))
        assertFalse(p.contains("exegese"))
        assertTrue(p.contains("<texto-do-card>"))
        assertTrue(p.contains("NUNCA use formato de diálogo"))
        assertFalse(p.contains("<b>Publicador:</b>"))
        assertFalse(p.contains("<b>Morador:</b>"))
    }

    @Test
    fun speech_mentor_nao_menciona_irmao() {
        assertFalse(GenerationPrompts.SPEECH_MENTOR.contains("irmão"))
        assertFalse(GenerationPrompts.SPEECH_MENTOR.contains("irmã"))
    }

    @Test
    fun speech_mentor_nao_menciona_exegese() {
        // O guia apensado lista "exegese" em EVITE (por design); a persona
        // não deve usá-la como descrição do próprio papel.
        assertFalse(body(GenerationPrompts.SPEECH_MENTOR).contains("exegese"))
    }

    @Test
    fun demo_scriptwriter_nao_menciona_irmao() {
        assertFalse(GenerationPrompts.DEMO_SCRIPTWRITER.contains("irmão"))
        assertFalse(GenerationPrompts.DEMO_SCRIPTWRITER.contains("irmã"))
    }

    @Test
    fun demo_scriptwriter_nao_menciona_superintendente() {
        // O guia apensado preserva "superintendente" como termo TJ; a persona
        // não deve usá-lo como metáfora de identidade do Copilot.
        assertFalse(body(GenerationPrompts.DEMO_SCRIPTWRITER).contains("superintendente"))
        assertTrue(body(GenerationPrompts.DEMO_SCRIPTWRITER).contains("instrutor de campo"))
    }

    @Test
    fun speech_mentor_inclui_principios_de_oratoria() {
        val p = GenerationPrompts.SPEECH_MENTOR
        assertTrue(p.contains("ESTRUTURA:"))
        assertTrue(p.contains("ENSINO:"))
        assertTrue(p.contains("ENTREGA:"))
        assertTrue(p.contains("Modulação"))
    }

    @Test
    fun demo_scriptwriter_inclui_principios_adaptados() {
        val p = body(GenerationPrompts.DEMO_SCRIPTWRITER)
        assertTrue(p.contains("ESTRUTURA DA CONVERSA"))
        assertTrue(p.contains("ENSINO NO DIÁLOGO"))
        assertTrue(p.contains("ENTREGA NATURAL"))
        assertTrue(p.contains("Não é discurso: é conversa"))
    }

    @Test
    fun speech_mentor_chat_hint_entrega_card_direto_quando_usuario_pede_geracao() {
        val p = GenerationPrompts.SPEECH_MENTOR_CHAT_HINT
        assertTrue(p.contains("Quando o usuário disser 'gera', 'escreve' ou 'entrega'"))
        assertTrue(p.contains("sem orientar antes"))
    }

    @Test
    fun prompt_nao_ultrapassa_5000_chars() {
        // Teto de sanidade: a persona + guia de linguagem cabem no system
        // cacheável sem inflar o prompt.
        assertTrue(GenerationPrompts.SPEECH_MENTOR.length < 5000)
        assertTrue(GenerationPrompts.DEMO_SCRIPTWRITER.length < 5000)
    }

    // v2.7.0 (Opção B) — persona enxuta; fontes BE/TH vão para o contexto.

    @Test
    fun persona_enxuta_nao_ultrapassa_2000_chars() {
        // Opção B: identidade + 3 princípios + flexibilidade, sem guia empilhado.
        assertTrue(GenerationPrompts.SPEECH_MENTOR.length < 2000)
        assertTrue(GenerationPrompts.DEMO_SCRIPTWRITER.length < 2000)
    }

    @Test
    fun persona_enxuta_nao_carrega_fontes_no_system() {
        // As fontes BE/TH são contexto (dossiê), não persona estável.
        for (p in listOf(GenerationPrompts.SPEECH_MENTOR, GenerationPrompts.DEMO_SCRIPTWRITER)) {
            assertFalse(p.contains("BE 39-42"))
            assertFalse(p.contains("TH 3-19"))
        }
    }

    @Test
    fun speech_mentor_usa_modulacao_e_nao_entonacao() {
        val p = GenerationPrompts.SPEECH_MENTOR
        assertTrue(p.contains("Modulação"))
        assertTrue(p.contains("volume, emoção e ritmo"))
        assertFalse(p.contains("entonação"))
    }

    @Test
    fun speech_mentor_distingue_conclusao_de_aplicacao() {
        val p = GenerationPrompts.SPEECH_MENTOR
        assertTrue(p.contains("conclusão que recapitula e incentiva"))
        assertTrue(p.contains("aplicação ao longo do discurso"))
    }

    // ---------- Fase 3: demonstração livre ----------

    @Test
    fun freeDemo_mapeiaParaPersonaNeutra() {
        assertEquals(
            GenerationProfile.FREE_DEMO,
            GenerationProfile.forDiscourseType(DiscourseType.FREE_DEMO.name),
        )
        val p = GenerationPrompts.systemPromptFor(GenerationProfile.FREE_DEMO).orEmpty()
        assertTrue(p.contains("mentor de comunicação"))
        assertFalse(p.contains("Faça Seu Melhor"))
        assertTrue(GenerationPrompts.FREE_DEMO_CHAT_HINT.contains("Não use S-38"))
    }
}
