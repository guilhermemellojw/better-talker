package com.bettertalker.app.data.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/** #23-fix-a: regra do toggle "Voz premium" (puro/testável). */
class VoicePremiumSettingsTest {

    @Test
    fun ligarSemModeloPedeDownload() {
        assertEquals(VoicePremiumToggle.NeedsDownload, resolveVoicePremiumToggle(true, false))
    }

    @Test
    fun ligarComModeloAtiva() {
        assertEquals(VoicePremiumToggle.Activated, resolveVoicePremiumToggle(true, true))
    }

    @Test
    fun desligarSempreFunciona() {
        assertEquals(VoicePremiumToggle.Deactivated, resolveVoicePremiumToggle(false, false))
        assertEquals(VoicePremiumToggle.Deactivated, resolveVoicePremiumToggle(false, true))
    }
}
