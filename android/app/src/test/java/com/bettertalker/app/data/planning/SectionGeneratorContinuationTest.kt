package com.bettertalker.app.data.planning

import com.bettertalker.app.data.llm.LlmProvider
import com.bettertalker.app.data.llm.LlmRequest
import com.bettertalker.app.data.llm.LlmResponse
import com.bettertalker.app.data.llm.LlmResponseMeta
import com.bettertalker.app.domain.planning.Dossier
import com.bettertalker.app.domain.planning.DraftProgress
import com.bettertalker.app.domain.planning.ResolvedBibleText
import com.bettertalker.app.domain.planning.ReferenceStatus
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SectionGeneratorContinuationTest {

    private class FakeLlm(
        private val behavior: (LlmRequest) -> LlmResponse,
    ) : LlmProvider {
        val received = mutableListOf<LlmRequest>()
        override val id: String = "fake"
        override val model: String = "fake-model"
        override suspend fun generate(request: LlmRequest): LlmResponse {
            received.add(request)
            return behavior(request)
        }
    }

    private class ThrowingLlm(private val error: Throwable) : LlmProvider {
        override val id: String = "fake"
        override val model: String = "fake-model"
        override suspend fun generate(request: LlmRequest): LlmResponse = throw error
    }

    private fun response(text: String, finish: String? = "stop") = LlmResponse(
        text = text,
        meta = LlmResponseMeta(
            providerId = "fake",
            model = "fake-model",
            durationMs = 10L,
            attempts = 1,
            offline = false,
            finishReason = finish,
        ),
    )

    private fun json(text: String, sources: List<String> = emptyList()): String =
        "{\"text\":\"$text\",\"usedSources\":[" +
            sources.joinToString(",") { "\"$it\"" } + "]}"

    private fun words(count: Int, end: String = "Fim."): String =
        "palavra ".repeat(count.coerceAtLeast(1)) + end

    private fun dossier45() = Dossier(
        currentSection = SpeechSection(
            id = "s1", noteId = "n1", order = 0, role = SectionRole.BODY,
            title = "Tema de 45 min", minutes = 45, contentHtml = "",
            bibleRefs = emptyList(), publicationRefs = emptyList(),
            methodPrinciple = null, createdAt = 0, updatedAt = 0,
        ),
        currentSubPoint = null,
        selectedText = null,
        fullContentHtml = "",
        overview = emptyList(),
        bibleTexts = listOf(
            ResolvedBibleText("Gên 3:6", "Texto literal", ReferenceStatus.RESOLVED)
        ),
        publicationTexts = emptyList(),
        methodPrinciples = emptyList(),
        unresolvedRefs = emptyList(),
        transitionContext = null,
    )

    @Test
    fun quarentaECinco_duasChamadasComMetasEProgresso() = runBlocking {
        val fake = FakeLlm { req ->
            if (req.text.contains("PARTE 2 DE 2")) {
                response(json("<p>${words(2860)}</p>"))
            } else {
                response(json("<p>${words(3000)}</p>", listOf("Gên 3:6")))
            }
        }
        val progress = mutableListOf<DraftProgress>()
        val draft = SectionGeneratorImpl(fake).generateWithProgress(dossier45()) {
            progress += it
        }

        assertNotNull(draft)
        assertEquals(2, fake.received.size)
        val p1 = fake.received[0]
        val p2 = fake.received[1]
        assertEquals(23, p1.longTextRequest?.minutes)
        assertEquals(45, p1.longTextRequest?.sectionMinutes)
        assertEquals(2990, p1.longTextRequest?.targetWords)
        assertEquals(1, p1.longTextRequest?.partIndex)
        assertEquals(2, p1.longTextRequest?.partCount)
        assertEquals(4488, p1.maxOutputTokens)
        assertTrue(p1.text.contains("PARTE 1 DE 2"))
        assertEquals(22, p2.longTextRequest?.minutes)
        assertEquals(2860, p2.longTextRequest?.targetWords)
        assertEquals(2, p2.longTextRequest?.partIndex)
        assertTrue(p2.text.contains("PARTE 2 DE 2"))
        assertTrue(p2.text.contains("FINAL DA PARTE ANTERIOR"))

        assertEquals(2, progress.size)
        assertEquals(1, progress[0].partIndex)
        assertEquals(2990, progress[0].targetWords)
        assertEquals(0, progress[0].wordsSoFar)
        assertEquals(2, progress[1].partIndex)
        assertEquals(2860, progress[1].targetWords)
        assertTrue(progress[1].wordsSoFar >= 2900)

        assertEquals(listOf("Gên 3:6"), draft!!.usedSources)
        assertTrue(draft.textHtml.contains("<p>"))
        assertTrue(draft.textHtml.contains("Fim."))
        assertEquals(false, draft.possiblyTruncated)
    }

    @Test
    fun parteCurtaComStop_disparaExpansaoNoGerador() = runBlocking {
        val fake = FakeLlm { req ->
            when {
                req.text.contains("PARTE 2 DE 2") ->
                    response(json("<p>${words(2860)}</p>"))
                req.text.contains("CORREÇÃO DE EXTENSÃO") ->
                    response(json("<p>${words(3000)}</p>"))
                else ->
                    response(json("<p>${words(1000)}</p>"))
            }
        }
        val draft = SectionGeneratorImpl(fake).generate(dossier45())

        assertNotNull(draft)
        assertEquals(3, fake.received.size)
        val expansion = fake.received[1]
        assertTrue(expansion.text.contains("CORREÇÃO DE EXTENSÃO"))
        assertEquals(8192, expansion.maxOutputTokens)
        assertEquals(8192, expansion.longTextRequest?.maxTokensOverride)
        assertEquals(false, draft!!.possiblyTruncated)
        assertTrue(fake.received[2].text.contains("PARTE 2 DE 2"))
    }

    @Test
    fun parte2Cortada_marcaTruncadoERetentaUmaVez() = runBlocking {
        var part2Calls = 0
        val fake = FakeLlm { req ->
            when {
                req.text.contains("PARTE 2 DE 2") -> {
                    part2Calls++
                    response(
                        json("<p>${words(100, end = "corte")}</p>"),
                        finish = "length",
                    )
                }
                else -> response(json("<p>${words(3000)}</p>"))
            }
        }
        val draft = SectionGeneratorImpl(fake).generate(dossier45())

        assertNotNull(draft)
        assertEquals(2, part2Calls) // chamada + retry
        assertTrue(draft!!.possiblyTruncated)
        assertTrue(fake.received.size >= 3)
    }

    @Test
    fun parte2Falha_retornaParte1Marcada() = runBlocking {
        val fake = FakeLlm { req ->
            if (req.text.contains("PARTE 2 DE 2")) {
                response("   ")
            } else {
                response(json("<p>${words(3000)}</p>"))
            }
        }
        val draft = SectionGeneratorImpl(fake).generate(dossier45())

        assertNotNull(draft)
        assertTrue(draft!!.possiblyTruncated)
        assertTrue(draft.textHtml.contains("Fim."))
        assertTrue(fake.received.size >= 2)
    }

    @Test
    fun parte2JsonCortado_salvaParcialEmVezDeDescartar() = runBlocking {
        val fake = FakeLlm { req ->
            if (req.text.contains("PARTE 2 DE 2")) {
                // JSON cortado no meio do texto (sem fechar string/objeto).
                response("{\"text\":\"<p>${words(2000)}</p>")
            } else {
                response(json("<p>${words(3000)}</p>"))
            }
        }
        val draft = SectionGeneratorImpl(fake).generate(dossier45())

        assertNotNull(draft)
        assertTrue(draft!!.textHtml.contains("palavra"))
        assertTrue(draft.textHtml.length > 2000)
        assertTrue(draft.possiblyTruncated)
    }

    @Test
    fun parte1Falha_retornaNull() = runBlocking {
        val fake = FakeLlm { response("não é json") }
        assertNull(SectionGeneratorImpl(fake).generate(dossier45()))
    }

    @Test
    fun cancellation_propaga() = runBlocking {
        val gen = SectionGeneratorImpl(ThrowingLlm(CancellationException("cancelado")))
        try {
            gen.generate(dossier45())
            fail("Esperava CancellationException")
        } catch (_: CancellationException) {
            // esperado
        }
    }

    @Test
    fun continuarGeracao_estendeSemRepetir() = runBlocking {
        val current = "<p>${words(3000)}</p>"
        val fake = FakeLlm { req ->
            assertTrue(req.text.contains("CONTINUAÇÃO DIRIGIDA"))
            assertTrue(req.text.contains("FINAL DA SEÇÃO"))
            assertTrue(req.text.contains("NÃO escreva uma nova conclusão"))
            response(json("<p>${words(2860)}</p>"))
        }
        val draft = SectionGeneratorImpl(fake).continueDraft(
            dossier = dossier45(),
            currentText = current,
            currentUsedSources = listOf("Gên 3:6"),
            targetWords = 5850,
        )

        assertNotNull(draft)
        assertTrue(draft!!.textHtml.startsWith("<p>palavra"))
        assertTrue(draft.textHtml.contains("Fim."))
        assertEquals(false, draft.possiblyTruncated)
        assertEquals(1, fake.received.size)
        assertTrue(fake.received[0].longTextRequest?.partIndex == 2)
        assertTrue((fake.received[0].longTextRequest?.targetWords ?: 0) in 2800..2900)
    }
}
