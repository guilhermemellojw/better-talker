package com.bettertalker.app.data.llm

import android.content.Context
import com.bettertalker.app.data.copilot.ChatTurn
import com.bettertalker.app.data.prefs.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * BETA-TESTABILITY/P10 — equivalência da rota produtiva e controle do fake.
 * O fake vive somente no source set de teste: nada é empacotado no APK.
 */
class ProviderSourceTest {

    private class FakeProviderSource : ProviderSource {
        val requests = mutableListOf<LlmRequest>()
        var gate: CompletableDeferred<Unit>? = null
        var failWith: Throwable? = null
        var response = LlmResponse(
            "resposta determinística",
            LlmResponseMeta("fake", "fake-model", 1L, 1, offline = true),
        )

        override suspend fun resolveRemote(
            settings: SettingsStore,
            context: Context?,
        ): ProviderFactory.RemoteConfig = ProviderFactory.RemoteConfig(apiKey = "fake-key")

        override fun createFor(
            config: ProviderFactory.RemoteConfig,
            context: Context?,
            effort: String,
        ): LlmProvider = object : LlmProvider {
            override val id = "fake"
            override val model = "fake-model"
            override suspend fun generate(request: LlmRequest): LlmResponse {
                requests += request
                gate?.await()
                failWith?.let { throw it }
                return response
            }
        }

        override fun useRemoteRoute(config: ProviderFactory.RemoteConfig): Boolean = true
    }

    @Test
    fun defaultSourcePreservesProductionRouteDecision() {
        listOf("", "deepseek-key").forEach { key ->
            val config = ProviderFactory.RemoteConfig(apiKey = key)
            assertEquals(
                ProviderFactory.useRemoteRoute(config),
                DefaultProviderSource.useRemoteRoute(config),
            )
        }
    }

    @Test
    fun fakeHasNoCallsBeforeGenerate() {
        val fake = FakeProviderSource()
        assertTrue(fake.requests.isEmpty())
        fake.createFor(ProviderFactory.RemoteConfig(apiKey = "fake-key"))
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun fakeCapturesFullHistory() = runBlocking {
        val fake = FakeProviderSource()
        val history = listOf(ChatTurn(true, "pergunta"), ChatTurn(false, "resposta"))
        val provider = fake.createFor(ProviderFactory.RemoteConfig(apiKey = "fake"))
        val result = provider.generate(LlmRequest(text = "", history = history))
        assertEquals("resposta determinística", result.text)
        assertEquals(1, fake.requests.size)
        assertEquals(history, fake.requests.single().history)
    }

    @Test
    fun fakeGateBlocksAndCancellationIsObserved() = runBlocking {
        val fake = FakeProviderSource()
        val gate = CompletableDeferred<Unit>()
        fake.gate = gate
        val provider = fake.createFor(ProviderFactory.RemoteConfig(apiKey = "fake"))
        val pending = async { provider.generate(LlmRequest(text = "")) }
        while (fake.requests.isEmpty()) kotlinx.coroutines.yield()
        assertFalse(pending.isCompleted)
        pending.cancel()
        try {
            pending.await()
            fail("esperado cancelamento")
        } catch (_: CancellationException) {
            assertEquals(1, fake.requests.size)
        }
    }

    @Test
    fun fakeFailurePropagates() = runBlocking {
        val fake = FakeProviderSource()
        fake.failWith = IllegalStateException("falha controlada")
        val provider = fake.createFor(ProviderFactory.RemoteConfig(apiKey = "fake"))
        try {
            provider.generate(LlmRequest(text = ""))
            fail("esperado erro")
        } catch (e: IllegalStateException) {
            assertEquals("falha controlada", e.message)
        }
    }
}
