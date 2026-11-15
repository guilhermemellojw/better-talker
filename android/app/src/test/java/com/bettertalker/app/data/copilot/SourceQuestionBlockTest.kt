package com.bettertalker.app.data.copilot

import com.bettertalker.app.data.domain.ContextPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 6.0.5 — pergunta sobre fonte: bloco prioritário e prompt sem dossiê/S-34. */
class SourceQuestionBlockTest {

    @Test
    fun blocoIncluiInterpretacaoETexto() {
        val block = buildSourceQuestionBlock(
            question = "O que diz jr 29?",
            interpretations = listOf("Jeremias — Uma Mensagem de Deus para Nós — p. 29"),
            evidence = listOf(
                SourceQuestionEvidence(
                    reference = "jr 29",
                    text = "Zedequias não fez o que era certo.",
                )
            ),
        )
        assertTrue(block.contains("PERGUNTA SOBRE FONTE"))
        assertTrue(block.contains("Jeremias — Uma Mensagem de Deus para Nós — p. 29"))
        assertTrue(block.contains("Zedequias não fez o que era certo."))
        assertTrue(block.contains("não use o ponto/S-34 como substituto"))
    }

    @Test
    fun blocoSemEvidenciaPedeHonestidadeENaoInventar() {
        val block = buildSourceQuestionBlock(
            question = "O que diz w99.01?",
            interpretations = listOf("A Sentinela — janeiro de 1999"),
            evidence = emptyList(),
        )
        assertTrue(block.contains("Não encontrei o texto desta fonte no acervo local"))
        assertTrue(block.contains("não invente"))
        assertTrue(block.contains("Abrir Biblioteca"))
    }

    @Test
    fun blocoMarcaTruncamento() {
        val block = buildSourceQuestionBlock(
            question = "O que diz o capítulo?",
            interpretations = listOf("Jeremias 29 — Bíblia"),
            evidence = listOf(
                SourceQuestionEvidence(
                    reference = "Je 29:1",
                    text = "x".repeat(500),
                )
            ),
            maxChars = 180,
        )
        assertTrue(block.contains("truncado no prompt"))
        assertTrue(block.contains("O leitor mostra o texto completo"))
    }

    @Test
    fun promptFonteNaoIncluiS34NemDossieNemBloco() {
        val prompts = buildDeepSeekPrompts(
            message = "O que diz Je 29?",
            history = emptyList(),
            isFirstMessage = true,
            pack = ContextPack(emptyList(), emptyList()),
            blockTitle = "Ponto 2",
            blockMinutes = 4,
            blockText = "corpo antigo do ponto 2",
            contextBlock = "DOSSIÊ ANTIGO",
            structural = null,
            sourceQuestionMode = true,
            sourceQuestionBlock = buildSourceQuestionBlock(
                question = "O que diz Je 29?",
                interpretations = listOf("Jeremias 29 — Bíblia"),
                evidence = listOf(
                    SourceQuestionEvidence("Je 29:11", "Porque eu bem sei os planos...")
                ),
            ),
        )
        assertTrue(prompts.system.contains("MODO PERGUNTA SOBRE FONTE"))
        assertFalse(prompts.system.contains("DOSSIÊ ANTIGO"))
        assertFalse(prompts.system.contains("BLOCO ATUAL"))
        assertTrue(prompts.user.startsWith("## PERGUNTA SOBRE FONTE"))
        assertFalse(prompts.user.contains("Texto do bloco em foco"))
        assertFalse(prompts.user.contains("corpo antigo do ponto 2"))
    }

    @Test
    fun driftRefsDetectaPublicacaoForaDaFonte() {
        val allowed = setOf("w|2017|10")
        val extras = sourceQuestionDriftRefs(
            answer = "A fonte w17.10 diz X; para o Ponto 1, w24.08 28 §§7-9 fala de Y.",
            allowedEditionKeys = allowed,
        )
        assertEquals(listOf("w24.08 28 §§7-9"), extras)
    }

    @Test
    fun driftRefsNaoMarcaAFontePedida() {
        assertTrue(
            sourceQuestionDriftRefs(
                answer = "Segundo w17.10 23 §§9-10, o texto trata de Zedequias.",
                allowedEditionKeys = setOf("w|2017|10"),
            ).isEmpty()
        )
    }

    @Test
    fun modoFonteReconhecePerguntaDeLeitura() {
        assertTrue(
            isSourceQuestionMessage(
                "O que diz w17.10 23 §§9-10 sobre a esperança?",
                hasDetectedRef = true,
            )
        )
        assertTrue(isSourceQuestionMessage("Leia Je 29", hasDetectedRef = true))
        assertTrue(isSourceQuestionMessage("w17.10 23 §§9-10", hasDetectedRef = true))
    }

    @Test
    fun modoFonteNaoSequestraGeracaoNemUsoNoPonto() {
        assertFalse(
            isSourceQuestionMessage(
                "Desenvolva o ponto 2 com w17.10 23",
                hasDetectedRef = true,
            )
        )
        assertFalse(
            isSourceQuestionMessage(
                "Use João 3:16 no ponto 2",
                hasDetectedRef = true,
            )
        )
        assertFalse(
            isSourceQuestionMessage(
                "Melhore o ponto 2 com w17.10",
                hasDetectedRef = true,
            )
        )
        assertFalse(isSourceQuestionMessage("O que diz w17.10?", hasDetectedRef = false))
    }

    @Test
    fun promptNormalContinuaComDossie() {
        val prompts = buildDeepSeekPrompts(
            message = "melhore o ponto 2",
            history = emptyList(),
            isFirstMessage = false,
            pack = ContextPack(emptyList(), emptyList()),
            blockTitle = "Ponto 2",
            blockMinutes = 4,
            blockText = "corpo",
            contextBlock = "DOSSIÊ ATUAL",
            sourceQuestionMode = false,
        )
        assertTrue(prompts.system.contains("DOSSIÊ ATUAL"))
        assertTrue(prompts.user.contains("Texto do bloco em foco"))
    }
}
