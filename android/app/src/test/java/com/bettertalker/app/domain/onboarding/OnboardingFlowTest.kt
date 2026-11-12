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
        assertEquals(
            UpgradeNoticeAction.FULL_ONBOARDING,
            OnboardingFlow.upgradeNoticeAction(
                OnboardingV5State.NOT_STARTED,
                meaningfulNotes = 0,
                dismissed = false,
            ),
        )
    }

    @Test
    fun upgradeComNotasMostraNovidades() {
        assertEquals(
            OnboardingLaunch.UpgradeNotice,
            OnboardingFlow.launchFor(OnboardingV5State.NOT_STARTED, noteCount = 2),
        )
        assertEquals(
            UpgradeNoticeAction.SHOW_UPGRADE,
            OnboardingFlow.upgradeNoticeAction(
                OnboardingV5State.NOT_STARTED,
                meaningfulNotes = 2,
                dismissed = false,
            ),
        )
    }

    @Test
    fun upgradeSeenSemDismissEhResgatado() {
        assertEquals(
            UpgradeNoticeAction.SHOW_UPGRADE,
            OnboardingFlow.upgradeNoticeAction(
                OnboardingV5State.UPGRADE_SEEN,
                meaningfulNotes = 2,
                dismissed = false,
            ),
        )
        assertEquals(
            UpgradeNoticeAction.NONE,
            OnboardingFlow.upgradeNoticeAction(
                OnboardingV5State.UPGRADE_SEEN,
                meaningfulNotes = 2,
                dismissed = true,
            ),
        )
    }

    @Test
    fun dismissEDoneNaoReabrem() {
        listOf(OnboardingV5State.DONE, OnboardingV5State.DISMISSED).forEach { state ->
            assertEquals(
                UpgradeNoticeAction.NONE,
                OnboardingFlow.upgradeNoticeAction(state, meaningfulNotes = 2, dismissed = false),
            )
        }
        assertEquals(
            UpgradeNoticeAction.NONE,
            OnboardingFlow.upgradeNoticeAction(
                OnboardingV5State.NOT_STARTED,
                meaningfulNotes = 2,
                dismissed = true,
            ),
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
    fun novidadesDoSeisSoNoPrimeiroBuild71ComNotas() {
        assertEquals(
            UpgradeV6Action.SHOW,
            OnboardingFlow.upgradeV6Action(
                seen = false,
                dismissed = false,
                versionCode = 71,
                meaningfulNotes = 2,
            ),
        )
        assertEquals(
            UpgradeV6Action.SHOW,
            OnboardingFlow.upgradeV6Action(
                seen = false,
                dismissed = false,
                versionCode = 72,
                meaningfulNotes = 2,
            ),
        )
        assertEquals(
            UpgradeV6Action.NONE,
            OnboardingFlow.upgradeV6Action(
                seen = false,
                dismissed = false,
                versionCode = 70,
                meaningfulNotes = 2,
            ),
        )
        assertEquals(
            UpgradeV6Action.NONE,
            OnboardingFlow.upgradeV6Action(
                seen = false,
                dismissed = false,
                versionCode = 71,
                meaningfulNotes = 0,
            ),
        )
    }

    @Test
    fun novidadesDoSeisNaoReabremSozinhas() {
        assertEquals(
            UpgradeV6Action.NONE,
            OnboardingFlow.upgradeV6Action(
                seen = true,
                dismissed = false,
                versionCode = 71,
                meaningfulNotes = 2,
            ),
        )
        assertEquals(
            UpgradeV6Action.NONE,
            OnboardingFlow.upgradeV6Action(
                seen = false,
                dismissed = true,
                versionCode = 71,
                meaningfulNotes = 2,
            ),
        )
    }

    @Test
    fun notaDemoNaoContaComoUsoReal() {
        assertTrue(OnboardingFlow.isSeedNote("Meu primeiro discurso"))
        assertTrue(OnboardingFlow.isSeedNote("  Meu primeiro discurso  "))
        assertFalse(OnboardingFlow.isSeedNote("Discurso de domingo"))
    }
}
