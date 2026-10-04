package com.bettertalker.app.data.llm

import com.bettertalker.app.data.copilot.ProviderErrorCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** F2.1 — provider local. Puro JVM (fake engine, sem Context, sem Log). */
class LocalGemmaProviderTest {

    private class FakeEngine(
        var failWarmup: Boolean = false,
        var failGenerate: Boolean = false,
        val chunks: List<String> = listOf("Olá, ", "mundo."),
    ) : GemmaEngine {
        var warmedUp = false
        var lastSystem = ""
        var lastUser = ""
        var lastMaxTokens = 0
        override suspend fun warmup() {
            warmedUp = true
            if (failWarmup) throw IOException("boot falhou")
        }
        override fun generate(system: String, user: String, maxOutputTokens: Int): Flow<String> = flow {
            lastSystem = system
            lastUser = user
            lastMaxTokens = maxOutputTokens
            if (failGenerate) throw IOException("geração falhou")
            chunks.forEach { emit(it) }
        }
    }

    private val logs = mutableListOf<String>()

    private fun provider(
        engine: FakeEngine,
        present: Boolean = true,
        request: LlmRequest = LlmRequest(text = "bloco", message = "oi"),
    ) = Triple(
        LocalGemmaProvider(null, engine, { present }, log = { logs += it }),
        engine,
        request,
    )

    @Test
    fun success_is_offline_with_timing_log() = runBlocking {
        val (p, engine, req) = provider(FakeEngine())
        val res = p.generate(req)
        assertEquals("gemma_local", p.id)
        assertEquals("gemma_local", res.meta.providerId)
        assertTrue(res.meta.offline)
        assertEquals("Olá, mundo.", res.text)
        assertTrue(engine.warmedUp)
        assertTrue(logs.any { it.startsWith("gemma_local ok load=") })
    }

    @Test
    fun system_carries_grounding_block() = runBlocking {
        val (p, engine, req) = provider(FakeEngine())
        p.generate(req)
        assertTrue(engine.lastSystem.contains("A fonte controla o conteúdo"))
        assertTrue(engine.lastSystem.contains("Não encontrei suporte suficiente nas fontes disponíveis."))
        assertTrue(engine.lastUser.isNotBlank())
    }

    @Test
    fun missing_model_is_unavailable() = runBlocking {
        val (p, _, req) = provider(FakeEngine(), present = false)
        try {
            p.generate(req)
            fail("deveria lançar")
        } catch (e: ProviderError) {
            assertEquals(ProviderErrorCode.UNAVAILABLE, e.code)
            assertEquals("gemma_local", e.providerId)
        }
    }

    @Test
    fun warmup_failure_is_unavailable() = runBlocking {
        val (p, _, req) = provider(FakeEngine(failWarmup = true))
        try {
            p.generate(req)
            fail("deveria lançar")
        } catch (e: ProviderError) {
            assertEquals(ProviderErrorCode.UNAVAILABLE, e.code)
        }
    }

    @Test
    fun generate_failure_is_unavailable_without_cloud_fallback() = runBlocking {
        val (p, _, req) = provider(FakeEngine(failGenerate = true))
        try {
            p.generate(req)
            fail("deveria lançar")
        } catch (e: ProviderError) {
            // Erro honesto local; a decisão de fallback é do chat (legado/offline).
            assertEquals(ProviderErrorCode.UNAVAILABLE, e.code)
        }
    }

    @Test
    fun verifier_removes_unsupported_quote_inline() = runBlocking {
        val engine = FakeEngine(
            chunks = listOf(
                "A fonte diz \"Deus criou tudo em seis dias literais de 24 horas\". Fim."
            )
        )
        val pack = com.bettertalker.app.data.domain.ContextPack(
            listOf(
                com.bettertalker.app.data.domain.EvidenceSource(
                    id = "s1", reference = "Gên 1:26",
                    text = "Deus criou os humanos para viver para sempre.",
                    sourceType = com.bettertalker.app.data.domain.SourceType.CONTENT,
                    publication = null, section = null, paragraph = null, page = null,
                )
            ),
            emptyList(),
        )
        val (p, _, req) = provider(engine, request = LlmRequest(text = "b", contextPack = pack))
        val res = p.generate(req)
        assertTrue(res.text.contains("Fim."))
        assertTrue(res.text.contains("removi uma frase"))
        assertTrue(!res.text.contains("seis dias literais"))
    }

    @Test
    fun phases_progress_and_partial_accumulates() = runBlocking {
        val (p, _, req) = provider(FakeEngine(chunks = listOf("a", "b")))
        LocalProgress.reset()
        p.generate(req)
        assertEquals(LocalPhase.Done, LocalProgress.phase.value)
        assertEquals("ab", LocalProgress.partialText.value)
    }

    @Test
    fun dossier_quote_is_supported() = runBlocking {
        // F2.2: citação fiel ao dossiê da seção em foco não pode ser removida.
        val engine = FakeEngine(
            chunks = listOf("Como diz o ponto: \"Precisamos estar vivos para ter esperança\". Fim.")
        )
        val dossier = "## DOSSIÊ\nPrecisamos estar vivos para ter esperança e fazer planos."
        val (p, _, req) = provider(
            engine,
            request = LlmRequest(text = "b", contextBlock = dossier),
        )
        val res = p.generate(req)
        assertTrue(res.text.contains("Precisamos estar vivos para ter esperança"))
        assertTrue(!res.text.contains("removi uma frase"))
    }
}
