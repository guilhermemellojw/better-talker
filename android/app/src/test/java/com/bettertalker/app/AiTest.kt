package com.bettertalker.app

import com.bettertalker.app.data.ai.CitationCheck
import com.bettertalker.app.data.ai.LlmConfig
import com.bettertalker.app.data.ai.LlmModelConfig
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
    fun configLocksDeterminismAndGates() {
        assertEquals(0.0f, LlmConfig.TEMPERATURE)
        assertEquals(1, LlmConfig.TOP_K)
        assertTrue(LlmConfig.ramOk(8L * 1024 * 1024 * 1024))
        assertFalse(LlmConfig.ramOk(4L * 1024 * 1024 * 1024))
        assertTrue(LlmConfig.sizeOk(2_588_147_712L))
        assertFalse(LlmConfig.sizeOk(4L * 1024 * 1024 * 1024))
        assertFalse(LlmConfig.sizeOk(0L))
    }

    @Test
    fun gemmaModelConstantsArePinned() {
        assertEquals("gemma-4-E2B-it.litertlm", LlmModelConfig.FILE_NAME)
        assertEquals(2_588_147_712L, LlmModelConfig.EXPECTED_BYTES)
        assertEquals(64, LlmModelConfig.SHA256.length)
        assertTrue(LlmModelConfig.DOWNLOAD_URL.contains(LlmModelConfig.REVISION))
        assertTrue(LlmModelConfig.DOWNLOAD_URL.endsWith(LlmModelConfig.FILE_NAME))
        assertTrue(LlmModelConfig.configured())
        assertEquals("Apache-2.0", LlmModelConfig.LICENSE_NAME)
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

    @Test
    fun modelDownloadGuards() {
        assertEquals(-1f, com.bettertalker.app.data.ai.downloadProgress(10L, -1L))
        assertEquals(0.5f, com.bettertalker.app.data.ai.downloadProgress(5L, 10L))
        assertEquals(1.0f, com.bettertalker.app.data.ai.downloadProgress(99L, 10L))
        assertTrue(com.bettertalker.app.data.ai.modelSizeAllowed(-1L))
        assertTrue(com.bettertalker.app.data.ai.modelSizeAllowed(2_588_147_712L))
        assertFalse(com.bettertalker.app.data.ai.modelSizeAllowed(4L * 1024 * 1024 * 1024))
    }
}
