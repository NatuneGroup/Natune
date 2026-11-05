package moe.rukamori.archivetune.startup

import moe.rukamori.archivetune.onboarding.OnboardingScreenState

/**
 * Whether this launch opens onboarding instead of the app.
 *
 * Loading opens onboarding because its loading indicator is the branch the first frame can draw;
 * the resolved state then decides for real. An error is not onboarding: it falls back to the app
 * rather than a dead-end screen.
 */
internal fun OnboardingScreenState.opensOnboardingRoute(): Boolean =
    when (this) {
        OnboardingScreenState.Loading -> true
        OnboardingScreenState.Empty -> true
        is OnboardingScreenState.Error -> false
        is OnboardingScreenState.Success -> uiState.shouldShowOnboarding
    }
