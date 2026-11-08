package com.bettertalker.app.domain.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HelpTriggerEngineTest {

    private val now = 1_000_000_000L

    @Test
    fun prioridadeEscolheNoKeyAntesDeEmptyLibrary() {
        val decision = HelpTriggerEngine.choose(
            candidates = setOf(HelpTriggerId.EMPTY_LIBRARY, HelpTriggerId.NO_KEY),
            states = emptyMap(),
            now = now,
            onboardingSettled = true,
        )
        assertEquals(HelpTriggerId.NO_KEY, decision?.id)
    }

    @Test
    fun onboardingAbertoOuContextoOcupadoNaoMostra() {
        assertNull(
            HelpTriggerEngine.choose(
                setOf(HelpTriggerId.EMPTY_LIBRARY), emptyMap(), now,
                onboardingSettled = false,
            )
        )
        assertNull(
            HelpTriggerEngine.choose(
                setOf(HelpTriggerId.EMPTY_LIBRARY), emptyMap(), now,
                onboardingSettled = true, busy = true,
            )
        )
        assertNull(
            HelpTriggerEngine.choose(
                setOf(HelpTriggerId.EMPTY_LIBRARY), emptyMap(), now,
                onboardingSettled = true, immersive = true,
            )
        )
        assertNull(
            HelpTriggerEngine.choose(
                setOf(HelpTriggerId.EMPTY_LIBRARY), emptyMap(), now,
                onboardingSettled = true, sheetOpen = true,
            )
        )
        assertNull(
            HelpTriggerEngine.choose(
                setOf(HelpTriggerId.EMPTY_LIBRARY), emptyMap(), now,
                onboardingSettled = true, shownThisSession = true,
            )
        )
    }

    @Test
    fun cooldownDeSeteDiasEsperaEDepoisLibera() {
        val state = mapOf(
            HelpTriggerId.EMPTY_LIBRARY to HelpTriggerState(
                seen = true,
                lastShownAt = now - 24 * 60 * 60 * 1000L,
            )
        )
        assertNull(
            HelpTriggerEngine.choose(
                setOf(HelpTriggerId.EMPTY_LIBRARY), state, now, onboardingSettled = true,
            )
        )
        assertTrue(
            HelpTriggerEngine.choose(
                setOf(HelpTriggerId.EMPTY_LIBRARY),
                state,
                now + HELP_TRIGGER_COOLDOWN_MS,
                onboardingSettled = true,
            ) != null
        )
    }

    @Test
    fun dismissPermanenteNuncaVolta() {
        val state = mapOf(
            HelpTriggerId.EMPTY_LIBRARY to HelpTriggerState(
                seen = true,
                dismissed = true,
                lastShownAt = 0L,
            )
        )
        assertNull(
            HelpTriggerEngine.choose(
                setOf(HelpTriggerId.EMPTY_LIBRARY), state, now + 10 * HELP_TRIGGER_COOLDOWN_MS,
                onboardingSettled = true,
            )
        )
    }

    @Test
    fun stageTribunaNaoDuplicaGatilho() {
        assertNull(
            HelpTriggerEngine.choose(
                setOf(HelpTriggerId.STAGE_TRIBUNA), emptyMap(), now,
                onboardingSettled = true,
            )
        )
    }
}
