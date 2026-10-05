package com.bettertalker.app

import com.bettertalker.app.data.llm.ProviderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T3 — resolução pura do provider, incluindo o novo "auto". */
class ProviderFactoryTest {

    @Test
    fun autoPrefersLocalModelWhenPresent() {
        val c = ProviderFactory.resolveProvider("auto", modelPresent = true, geminiKey = "AIza", groqKey = "gsk")
        assertEquals("gemma_local", c.providerId)
        assertEquals("", c.apiKey)
    }

    @Test
    fun autoFallsBackToGeminiKey() {
        val c = ProviderFactory.resolveProvider("auto", modelPresent = false, geminiKey = "AIza", groqKey = "gsk")
        assertEquals("gemini", c.providerId)
        assertEquals("AIza", c.apiKey)
    }

    @Test
    fun autoFallsBackToGroqKeyWhenOnlyGroqExists() {
        val c = ProviderFactory.resolveProvider("auto", modelPresent = false, geminiKey = "", groqKey = "gsk")
        assertEquals("qwen", c.providerId)
        assertEquals("gsk", c.apiKey)
    }

    @Test
    fun autoWithoutAnythingGoesDeterministic() {
        val c = ProviderFactory.resolveProvider("auto", modelPresent = false, geminiKey = "", groqKey = "")
        assertEquals("gemini", c.providerId)
        assertEquals("", c.apiKey)
        assertFalse(ProviderFactory.useRemoteRoute(c))
    }

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
    }
}
