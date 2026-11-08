package com.bettertalker.app.domain.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingFlowTest {

    @Test
    fun instalacaoNovaAbreTourCompleto() {
        assertEquals(
            OnboardingLaunch.Full,
            OnboardingFlow.launchFor(OnboardingV5State.NOT_STARTED, noteCount = 0),
        )
    }

    @Test
    fun upgradeComNotasMostraNovidades() {
        assertEquals(
            OnboardingLaunch.UpgradeNotice,
            OnboardingFlow.launchFor(OnboardingV5State.NOT_STARTED, noteCount = 2),
        )
    }

    @Test
    fun estadosConcluidosNaoReabremAutomaticamente() {
        listOf(
            OnboardingV5State.DONE,
            OnboardingV5State.DISMISSED,
            OnboardingV5State.UPGRADE_SEEN,
        ).forEach { state ->
            assertEquals(
                OnboardingLaunch.None,
                OnboardingFlow.launchFor(state, noteCount = 0),
            )
            assertTrue(state.settled)
        }
        assertFalse(OnboardingV5State.NOT_STARTED.settled)
    }

    @Test
    fun notaDemoNaoContaComoUsoReal() {
        assertTrue(OnboardingFlow.isSeedNote("Meu primeiro discurso"))
        assertTrue(OnboardingFlow.isSeedNote("  Meu primeiro discurso  "))
        assertFalse(OnboardingFlow.isSeedNote("Discurso de domingo"))
    }
}
