package com.bettertalker.app.domain.onboarding

/** Estado persistido da primeira abertura guiada da v5.0. */
enum class OnboardingV5State {
    NOT_STARTED,
    DONE,
    DISMISSED,
    UPGRADE_SEEN;

    val settled: Boolean
        get() = this != NOT_STARTED

    companion object {
        fun fromName(value: String?): OnboardingV5State =
            runCatching { valueOf(value ?: "") }.getOrDefault(NOT_STARTED)
    }
}
