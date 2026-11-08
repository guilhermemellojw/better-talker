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
}
