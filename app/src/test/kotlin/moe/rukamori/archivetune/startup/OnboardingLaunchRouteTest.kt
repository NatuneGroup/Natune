package moe.rukamori.archivetune.startup

import com.google.common.collect.ImmutableList
import moe.rukamori.archivetune.onboarding.OnboardingScreenState
import moe.rukamori.archivetune.onboarding.OnboardingUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingLaunchRouteTest {
    @Test
    fun unresolvedOnboardingDrawsItsLoadingBranch() {
        assertTrue(OnboardingScreenState.Loading.opensOnboardingRoute())
    }

    @Test
    fun anEmptyOnboardingStoreOpensOnboarding() {
        assertTrue(OnboardingScreenState.Empty.opensOnboardingRoute())
    }

    @Test
    fun anOnboardingErrorFallsBackToTheApp() {
        assertFalse(OnboardingScreenState.Error(messageResId = 0).opensOnboardingRoute())
    }

    @Test
    fun theResolvedDecisionIsTheOnlySourceForAReturningReader() {
        assertTrue(OnboardingScreenState.Success(uiState(show = true)).opensOnboardingRoute())
        assertFalse(OnboardingScreenState.Success(uiState(show = false)).opensOnboardingRoute())
    }

    private fun uiState(show: Boolean) =
        OnboardingUiState(
            shouldShowOnboarding = show,
            currentPage = 0,
            variantLabelResId = 0,
            versionName = "",
            pages = ImmutableList.of(),
            permissions = ImmutableList.of(),
            loginBenefits = ImmutableList.of(),
            communityActions = ImmutableList.of(),
        )
}
