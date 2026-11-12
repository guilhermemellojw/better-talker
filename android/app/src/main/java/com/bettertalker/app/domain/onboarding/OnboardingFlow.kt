package com.bettertalker.app.domain.onboarding

/** O que fazer ao abrir o app, dado o estado persistido e as notas locais. */
sealed interface OnboardingLaunch {
    data object None : OnboardingLaunch
    /** Instalação nova: tour completo. */
    data object Full : OnboardingLaunch
    /** Já existiam notas: “Novidades do 5.0” curto. */
    data object UpgradeNotice : OnboardingLaunch
}

object OnboardingFlow {
    /** Título da nota demo criada automaticamente no primeiro banco. */
    const val SEED_NOTE_TITLE = "Meu primeiro discurso"

    fun isSeedNote(title: String): Boolean = title.trim() == SEED_NOTE_TITLE

    fun launchFor(state: OnboardingV5State, noteCount: Int): OnboardingLaunch = when {
        state != OnboardingV5State.NOT_STARTED -> OnboardingLaunch.None
        noteCount <= 0 -> OnboardingLaunch.Full
        else -> OnboardingLaunch.UpgradeNotice
    }

    /**
     * Decisão da 5.0.1: o sheet de upgrade só é suprimido por `dismissed`.
     * `UPGRADE_SEEN` sem dismiss é resgatado na próxima abertura.
     */
    fun upgradeNoticeAction(
        state: OnboardingV5State,
        meaningfulNotes: Int,
        dismissed: Boolean,
    ): UpgradeNoticeAction {
        if (state == OnboardingV5State.NOT_STARTED && meaningfulNotes == 0) {
            return UpgradeNoticeAction.FULL_ONBOARDING
        }
        if (dismissed) return UpgradeNoticeAction.NONE
        if (state == OnboardingV5State.DONE || state == OnboardingV5State.DISMISSED) {
            return UpgradeNoticeAction.NONE
        }
        if (state == OnboardingV5State.NOT_STARTED && meaningfulNotes > 0) {
            return UpgradeNoticeAction.SHOW_UPGRADE
        }
        if (state == OnboardingV5State.UPGRADE_SEEN) {
            return UpgradeNoticeAction.SHOW_UPGRADE
        }
        return UpgradeNoticeAction.NONE
    }

    /** Primeiro versionCode em que as novidades do 6.0 aparecem sozinhas. */
    const val V6_UPGRADE_MIN_VERSION_CODE = 71

    /**
     * “Novidades do 6.0” só no primeiro build 71/72, para quem já tem notas.
     * `seen` garante o uma vez só; `dismissed` nunca reabre sozinho.
     */
    fun upgradeV6Action(
        seen: Boolean,
        dismissed: Boolean,
        versionCode: Int,
        meaningfulNotes: Int,
    ): UpgradeV6Action = when {
        versionCode < V6_UPGRADE_MIN_VERSION_CODE -> UpgradeV6Action.NONE
        meaningfulNotes <= 0 -> UpgradeV6Action.NONE
        dismissed || seen -> UpgradeV6Action.NONE
        else -> UpgradeV6Action.SHOW
    }
}

/** Ação da decisão de abertura para “Novidades do 6.0”. */
enum class UpgradeV6Action {
    NONE,
    SHOW,
}

/** Ação da decisão de abertura para “Novidades do 5.0”. */
enum class UpgradeNoticeAction {
    NONE,
    FULL_ONBOARDING,
    SHOW_UPGRADE,
}
