package com.bettertalker.app.data.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncGateTest {
    @Test
    fun soAgendaComBuildBetaOptInFirebaseELogin() {
        assertTrue(syncGate(buildSupportsSync = true, optedIn = true, configured = true, signedIn = true))
    }

    @Test
    fun qualquerPreCondicaoAusenteDesligaSync() {
        assertFalse(syncGate(buildSupportsSync = false, optedIn = true, configured = true, signedIn = true))
        assertFalse(syncGate(buildSupportsSync = true, optedIn = false, configured = true, signedIn = true))
        assertFalse(syncGate(buildSupportsSync = true, optedIn = true, configured = false, signedIn = true))
        assertFalse(syncGate(buildSupportsSync = true, optedIn = true, configured = true, signedIn = false))
    }
}
