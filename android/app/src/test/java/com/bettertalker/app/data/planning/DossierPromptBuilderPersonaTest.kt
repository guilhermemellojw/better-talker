package com.bettertalker.app.data.planning

import com.bettertalker.app.data.copilot.GenerationPrompts
import com.bettertalker.app.domain.planning.Dossier
import com.bettertalker.app.domain.planning.SpeechPacing
import com.bettertalker.app.domain.speech.DiscourseType
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fase A — o dossier separa demonstração (diálogo) de discurso (fala
 * contínua), inclusive a parte 6 `MINISTRY_TALK`, e injeta o modo mentor
 * apenas no contexto do CHAT (nunca no prompt JSON de draft).
 */
class DossierPromptBuilderPersonaTest {

    private fun dossier(
        type: DiscourseType,
        minutes: Int = 5,
        title: String = "Título do tópico",
    ) = Dossier(
        currentSection = SpeechSection(
            id = "s1", noteId = "n1", order = 0, role = SectionRole.BODY,
            title = title, minutes = minutes, contentHtml = "",
            bibleRefs = emptyList(), publicationRefs = emptyList(),
            methodPrinciple = null, createdAt = 1L, updatedAt = 1L,
        ),
        currentSubPoint = null,
        selectedText = null,
        fullContentHtml = "",
        overview = emptyList(),
        bibleTexts = emptyList(),
        publicationTexts = emptyList(),
        methodPrinciples = emptyList(),
        unresolvedRefs = emptyList(),
        transitionContext = null,
        noteTitle = "$title ($minutes min)",
        discourseType = type.name,
    )

    @Test
    fun demo_recebeFormatoDeDialogoEMetaDeReplicas() {
        val prompt = DefaultDossierPromptBuilder().build(dossier(DiscourseType.MINISTRY_PART, 4))
        assertTrue(prompt.contains("## FORMATO DE DEMONSTRAÇÃO"))
        assertTrue(prompt.contains("## DIRETRIZES DA REUNIÃO (S-38)"))
        assertTrue(prompt.contains("≈ 10 réplicas"))
        assertTrue(prompt.contains("máx. 3 frases"))
        // No draft JSON o "MODO MENTOR" de chat não entra.
        assertFalse(prompt.contains("MODO MENTOR"))
    }

    @Test
    fun ministryTalk_naoRecebeFormatoDeDialogo_recebeDiscursoEMetaDePalavras() {
        val prompt = DefaultDossierPromptBuilder().build(dossier(DiscourseType.MINISTRY_TALK, 5))
        assertFalse(prompt.contains("## FORMATO DE DEMONSTRAÇÃO"))
        assertTrue(prompt.contains("## DIRETRIZES DA REUNIÃO (S-38)"))
        assertTrue(prompt.contains("parte de DISCURSO"))
        assertTrue(prompt.contains("≈ ${SpeechPacing.targetWords(5)} palavras"))
        // A instrução de demo ("não faça um discurso longo") NÃO se aplica.
        assertFalse(prompt.contains("não faça um discurso longo"))
    }

    @Test
    fun tesourosTalk_naoRecebeS38DeMinisterio() {
        val prompt = DefaultDossierPromptBuilder().build(
            dossier(DiscourseType.TREASURES_TALK, 10, "A ordem nos céus")
        )
        assertFalse(prompt.contains("## FORMATO DE DEMONSTRAÇÃO"))
        assertFalse(prompt.contains("## DIRETRIZES DA REUNIÃO (S-38)"))
        assertTrue(prompt.contains("Tipo: TREASURES_TALK"))
    }

    @Test
    fun contextBlockDeDiscurso_injetaModoMentorDeOratoria() {
        val block = DefaultDossierPromptBuilder()
            .buildContextBlock(dossier(DiscourseType.S34_DISCOURSE, 10))
        assertTrue(block.contains(GenerationPrompts.SPEECH_MENTOR_CHAT_HINT))
        assertTrue(block.contains("MODO MENTOR (ORATÓRIA)"))
        assertTrue(block.contains("≈ ${SpeechPacing.targetWords(10)} palavras"))
    }

    @Test
    fun contextBlockDeDemo_injetaModoMentorDeCampo() {
        val block = DefaultDossierPromptBuilder()
            .buildContextBlock(dossier(DiscourseType.MINISTRY_PART, 4))
        assertTrue(block.contains(GenerationPrompts.DEMO_SCRIPTWRITER_CHAT_HINT))
        assertTrue(block.contains("MODO MENTOR (TREINADOR DE CAMPO)"))
        assertTrue(block.contains("≈ ${SpeechPacing.targetTurns(4)} réplicas"))
    }

    @Test
    fun targetWordsForMinutes_usaSpeechPacing() {
        assertEquals(1300, targetWordsForMinutes(10))
        assertEquals(520, targetWordsForMinutes(4))
        assertNull(targetWordsForMinutes(null))
        assertNull(targetWordsForMinutes(0))
    }
}
