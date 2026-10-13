package com.bettertalker.app

import com.bettertalker.app.data.ai.CitationCheck
import com.bettertalker.app.data.ai.RagContext
import com.bettertalker.app.data.ai.RagOrientation
import com.bettertalker.app.data.ai.RagPassage
import com.bettertalker.app.data.ai.buildRagPrompt
import com.bettertalker.app.data.ai.checkCitations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTest {

    private fun ctx() = RagContext(
        sectionTitle = "Fomos criados para viver para sempre",
        minutes = 5,
        passages = listOf(
            RagPassage(1, "Muitos vão viver para sempre em um paraíso na Terra.", "A Sentinela N.º 3 2019"),
            RagPassage(2, "O tempo passa muito rápido e a vida parece muito curta.", "Despertai! 8/2013")
        ),
        orientation = RagOrientation("th, lição 12", "introdução"),
        history = listOf("usuário pediu a introdução", "copilot listou seções"),
        taskKind = "introdução"
    )

    @Test
    fun promptHasBlockClauseAndNumberedRefs() {
        val p = buildRagPrompt(ctx())
        assertTrue(p.contains("PROIBIDO"))
        assertTrue(p.contains("[1] [A Sentinela N.º 3 2019]"))
        assertTrue(p.contains("[2] [Despertai! 8/2013]"))
        assertTrue(p.contains("th, lição 12"))
        assertTrue(p.contains("INTERAÇÃO ANTERIOR"))
    }

    @Test
    fun promptNeverLeaksGuideText() {
        val guideBody = "texto secreto da publicação-guia que nunca pode entrar"
        val p = buildRagPrompt(ctx().copy(
            passages = listOf(RagPassage(1, "trecho da matéria citada aqui", "Revista")),
            orientation = RagOrientation("th, lição 1", "introdução")
        ))
        assertFalse(p.contains(guideBody))
        assertTrue(p.contains("trecho da matéria citada aqui"))
    }

    @Test
    fun promptEmptyPassagesMarksMissing() {
        val p = buildRagPrompt(ctx().copy(passages = emptyList()))
        assertTrue(p.contains("não foi encontrada"))
    }



    @Test
    fun citationsPassWhenGrounded() {
        val passages = listOf(
            "Muitos vão viver para sempre em um paraíso na Terra",
            "O tempo passa muito rápido e a vida parece muito curta"
        )
        val ok = checkCitations(
            "Como diz [1], muitos vão viver para sempre em um paraíso na Terra.",
            passages
        )
        assertTrue(ok.ok)
        assertTrue(ok.violations.isEmpty())
    }

    @Test
    fun citationsFailWhenInvented() {
        val passages = listOf("Muitos vão viver para sempre em um paraíso na Terra")
        val badIndex = checkCitations("Veja [9] sobre isso.", passages)
        assertFalse(badIndex.ok)
        val badVerse = checkCitations("Como diz Gên 99:99 sobre isso [1].", passages)
        assertFalse(badVerse.ok)
        val badQuote = checkCitations(
            "“Esta frase longa e completamente inventada nunca apareceu em trecho algum da base” [1].",
            passages
        )
        assertFalse(badQuote.ok)
    }

    @Test
    fun repetitionGuard() {
        assertTrue(
            com.bettertalker.app.data.ai.hasRepetition(
                "Queremos viver para sempre, queremos viver para sempre. " +
                    "Queremos viver para sempre, queremos viver para sempre."
            )
        )
        assertFalse(
            com.bettertalker.app.data.ai.hasRepetition(
                "Primeira frase sobre o tema aqui. Segunda frase diferente sobre outro ponto."
            )
        )
        assertFalse(com.bettertalker.app.data.ai.hasRepetition("Ok."))
    }

}
