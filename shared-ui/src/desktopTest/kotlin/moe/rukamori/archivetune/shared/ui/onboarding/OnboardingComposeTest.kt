package moe.rukamori.archivetune.shared.ui.onboarding

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import moe.rukamori.archivetune.shared.ui.resources.*
import kotlin.test.*
import androidx.compose.runtime.setValue

@OptIn(ExperimentalTestApi::class)
class OnboardingComposeTest {
    @Test fun optionalPermissionAndLoginDoNotBlockProgressAndFinishWaitsForCommit() = runComposeUiTest {
        var page by mutableStateOf(OnboardingPage.Permissions)
        var saving by mutableStateOf(false)
        var grants = 0
        var finished = 0
        setContent {
            MaterialTheme {
                OnboardingScreen(page,
                    listOf(PermissionRow("microphone", Res.string.m4_voice_search, Res.string.m4_permissions_description,
                        PermissionStatus.NeedsAction, { grants++ })),
                    false, saving, false,
                    OnboardingActions({ page = OnboardingPage.Login }, { page = OnboardingPage.Permissions },
                        { finished++; saving = true }, {}), {})
            }
        }
        onNodeWithTag("permission-microphone").performClick()
        assertEquals(1, grants)
        onNodeWithTag("onboarding-next").performClick()
        onNodeWithText("Log in").assertIsDisplayed()
        runOnIdle { page = OnboardingPage.Community }
        onNodeWithTag("onboarding-next").performClick()
        assertEquals(1, finished)
        onNodeWithTag("onboarding-next").assertIsNotEnabled()
    }
}
