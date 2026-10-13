package com.bettertalker.app

import com.bettertalker.app.data.llm.DeepSeekProvider
import com.bettertalker.app.data.llm.ProviderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cleanup multi-LLM: resolução pura do ÚNICO provider (DeepSeek BYOD). */
class ProviderFactoryTest {

    @Test
    fun resolveProvider_comChave_devolveDeepSeek() {
        val c = ProviderFactory.resolveProvider(deepseekKey = "dsk", deepseekModel = "deepseek-v4-pro")
        assertEquals("deepseek", c.providerId)
        assertEquals("dsk", c.apiKey)
        assertEquals("deepseek-v4-pro", c.model)
        assertTrue(ProviderFactory.useRemoteRoute(c))
    }

    @Test
    fun resolveProvider_modeloDefaultEhFlash() {
        val c = ProviderFactory.resolveProvider(deepseekKey = "dsk")
        assertEquals(DeepSeekProvider.MODEL_FLASH, c.model)
    }

    @Test
    fun resolveProvider_semChave_naoAtivaRotaRemota() {
        val c = ProviderFactory.resolveProvider(deepseekKey = "")
        assertEquals("deepseek", c.providerId)
        assertEquals("", c.apiKey)
        assertFalse(ProviderFactory.useRemoteRoute(c))
        assertFalse(ProviderFactory.useRemoteRoute(""))
        assertFalse(ProviderFactory.useRemoteRoute(null))
        assertTrue(ProviderFactory.useRemoteRoute("dsk"))
    }

    @Test
    fun createForInstanciaDeepSeekComModelo() {
        val p = ProviderFactory.createFor(ProviderFactory.RemoteConfig("deepseek", "dsk", "deepseek-v4-pro"))
        assertEquals("deepseek", p.id)
        assertEquals("deepseek-v4-pro", p.model)

        val flash = ProviderFactory.createFor(ProviderFactory.RemoteConfig(apiKey = "dsk"))
        assertEquals(DeepSeekProvider.MODEL_FLASH, flash.model)
    }
}
