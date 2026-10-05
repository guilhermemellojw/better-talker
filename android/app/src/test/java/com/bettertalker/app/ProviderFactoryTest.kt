package com.bettertalker.app

import com.bettertalker.app.data.llm.DeepSeekProvider
import com.bettertalker.app.data.llm.ProviderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T3 (IA Local) + T3 (DeepSeek) — resolução pura do provider, incluindo "auto". */
class ProviderFactoryTest {

    // ---------- auto: DeepSeek → remoto → local → determinístico ----------

    @Test
    fun autoPrefersDeepSeekWithKeyAndOnline() {
        val c = ProviderFactory.resolveProvider(
            selected = "auto", modelPresent = true, geminiKey = "AIza", groqKey = "gsk",
            deepseekKey = "dsk", online = true, deepseekModel = "deepseek-v4-pro"
        )
        assertEquals("deepseek", c.providerId)
        assertEquals("dsk", c.apiKey)
        assertEquals("deepseek-v4-pro", c.model)
    }

    @Test
    fun autoFallsBackToGeminiWithoutDeepSeekKey() {
        val c = ProviderFactory.resolveProvider("auto", modelPresent = false, geminiKey = "AIza", groqKey = "gsk")
        assertEquals("gemini", c.providerId)
        assertEquals("AIza", c.apiKey)
    }

    @Test
    fun autoFallsBackToGroqWhenOnlyGroqExists() {
        val c = ProviderFactory.resolveProvider("auto", modelPresent = false, geminiKey = "", groqKey = "gsk")
        assertEquals("qwen", c.providerId)
        assertEquals("gsk", c.apiKey)
    }

    @Test
    fun autoUsesLocalModelWhenNoRemoteKeys() {
        val c = ProviderFactory.resolveProvider("auto", modelPresent = true, geminiKey = "", groqKey = "")
        assertEquals("gemma_local", c.providerId)
        assertTrue(ProviderFactory.useRemoteRoute(c))
    }

    @Test
    fun autoWithoutAnythingGoesDeterministic() {
        val c = ProviderFactory.resolveProvider("auto", modelPresent = false, geminiKey = "", groqKey = "")
        assertEquals("gemini", c.providerId)
        assertEquals("", c.apiKey)
        assertFalse(ProviderFactory.useRemoteRoute(c))
    }

    @Test
    fun autoOfflineFallsBackToLocalThenDeterministic() {
        val withModel = ProviderFactory.resolveProvider(
            "auto", modelPresent = true, geminiKey = "AIza", groqKey = "gsk",
            deepseekKey = "dsk", online = false
        )
        assertEquals("gemma_local", withModel.providerId)

        val noModel = ProviderFactory.resolveProvider(
            "auto", modelPresent = false, geminiKey = "AIza", groqKey = "gsk",
            deepseekKey = "dsk", online = false
        )
        assertEquals("gemini", noModel.providerId)
        assertEquals("", noModel.apiKey) // determinístico, nunca remoto offline
        assertFalse(ProviderFactory.useRemoteRoute(noModel))
    }

    // ---------- explícitos: comportamento inalterado ----------

    @Test
    fun explicitProvidersKeepOldBehavior() {
        val qwen = ProviderFactory.resolveProvider("qwen", modelPresent = true, geminiKey = "AIza", groqKey = "gsk")
        assertEquals("qwen", qwen.providerId)
        assertEquals("gsk", qwen.apiKey)

        val gemini = ProviderFactory.resolveProvider("gemini", modelPresent = true, geminiKey = "AIza", groqKey = "gsk")
        assertEquals("gemini", gemini.providerId)
        assertEquals("AIza", gemini.apiKey)

        val local = ProviderFactory.resolveProvider("gemma_local", modelPresent = false, geminiKey = "", groqKey = "")
        assertEquals("gemma_local", local.providerId)
        assertTrue(ProviderFactory.useRemoteRoute(local))

        // Explícito não checa rede: o provider falha honesto se estiver offline.
        val deepseek = ProviderFactory.resolveProvider(
            "deepseek", modelPresent = false, geminiKey = "", groqKey = "",
            deepseekKey = "dsk", online = false
        )
        assertEquals("deepseek", deepseek.providerId)
        assertEquals("dsk", deepseek.apiKey)
        assertEquals(DeepSeekProvider.MODEL_FLASH, deepseek.model)
    }

    @Test
    fun createForInstanciaDeepSeekComModelo() {
        val p = ProviderFactory.createFor(ProviderFactory.RemoteConfig("deepseek", "dsk", "deepseek-v4-pro"))
        assertEquals("deepseek", p.id)
        assertEquals("deepseek-v4-pro", p.model)

        val flash = ProviderFactory.createFor(ProviderFactory.RemoteConfig("deepseek", "dsk"))
        assertEquals(DeepSeekProvider.MODEL_FLASH, flash.model)
    }
}
