package com.bettertalker.app.data.planning

import com.bettertalker.app.data.llm.LlmProvider
import com.bettertalker.app.data.llm.LlmRequest
import com.bettertalker.app.data.llm.LlmResponse
import com.bettertalker.app.data.llm.LlmResponseMeta
import com.bettertalker.app.domain.planning.Dossier
import com.bettertalker.app.domain.speech.SectionRole
import com.bettertalker.app.domain.speech.SpeechSection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F2.3: gerador do mini discurso (texto puro, compatível com o Gemma local).
 * Puro/testável: provider fake, sem Room/rede.
 */
class MiniSpeechGeneratorTest {

    private class FakeProvider(
        private val text: String,
        private val finish: String? = "stop",
        private val throwIt: Boolean = false,
    ) : LlmProvider {
        override val id: String = "gemma_local"
        override val model: String = "fake"
        var lastRequest: LlmRequest? = null
        override suspend fun generate(request: LlmRequest): LlmResponse {
            lastRequest = request
            if (throwIt) error("boom")
            return LlmResponse(
                text,
                LlmResponseMeta(id, model, 1L, 1, offline = true, finishReason = finish),
            )
        }
    }

    private fun generator(provider: LlmProvider) = MiniSpeechGenerator(provider, log = {})

    private fun dossier(objective: String? = null, approach: String? = null) = Dossier(
        currentSection = SpeechSection(
            id = "s1", noteId = "n1", order = 1, role = SectionRole.BODY,
            title = "Vida eterna", minutes = 5, contentHtml = "",
            bibleRefs = emptyList(), publicationRefs = emptyList(),
            methodPrinciple = null, objective = objective, agreedApproach = approach,
            createdAt = 0, updatedAt = 0,
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
    )

    @Test
    fun paragraphsToHtml_splitsBlankLinesAndEscapes() = runBlocking {
        val gen = generator(FakeProvider("x"))
        val html = gen.paragraphsToHtml("Primeiro <p>.\n\nSegundo & fim.")
        assertEquals("<p>Primeiro &lt;p&gt;.</p><p>Segundo &amp; fim.</p>", html)
    }

    @Test
    fun paragraphsToHtml_singleLineFallsBackToWholeText() = runBlocking {
        val gen = generator(FakeProvider("x"))
        assertEquals("<p>só uma linha</p>", gen.paragraphsToHtml("só uma linha"))
    }

    @Test
    fun generate_buildsSingleHtmlDraft() = runBlocking {
        val provider = FakeProvider("Parágrafo um.\n\nParágrafo dois.")
        val gen = generator(provider)
        val draft = gen.generate(dossier(objective = "Levar à ação"))

        assertEquals("<p>Parágrafo um.</p><p>Parágrafo dois.</p>", draft!!.textHtml)
        assertTrue(draft.usedSources.isEmpty())
        assertTrue(draft.validation.ok)
    }

    @Test
    fun generate_promptCarriesTopicContext() = runBlocking {
        val provider = FakeProvider("ok")
        val gen = generator(provider)
        gen.generate(dossier(objective = "Objetivo X", approach = "Abordagem Y"))

        val prompt = provider.lastRequest!!.message
        assertTrue(prompt.contains("Objetivo X"))
        assertTrue(prompt.contains("Abordagem Y"))
        assertTrue(prompt.contains("MINI DISCURSO"))
    }

    @Test
    fun generate_blankResponse_returnsNull() = runBlocking {
        val gen = generator(FakeProvider("   "))
        assertNull(gen.generate(dossier()))
    }

    @Test
    fun generate_providerFailure_returnsNull() = runBlocking {
        val gen = generator(FakeProvider("x", throwIt = true))
        assertNull(gen.generate(dossier()))
    }
}
