package moe.rukamori.archivetune.shared.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import moe.rukamori.archivetune.shared.ui.screens.YouTubeLoginActions
import moe.rukamori.archivetune.shared.ui.screens.YouTubeLoginError
import moe.rukamori.archivetune.shared.ui.screens.YouTubeLoginScreen
import moe.rukamori.archivetune.shared.ui.screens.YouTubeLoginStatus
import moe.rukamori.archivetune.shared.ui.screens.YouTubeLoginUi

/** Layout regressions only, not native WebView, keyboard or provider-authentication evidence. */
@OptIn(ExperimentalTestApi::class)
class AuthWebViewLayoutTest {
    @Test
    fun oversizedStatusKeepsABrowserViewportAndScrollableRecoveryActions() = runComposeUiTest {
        setContent {
            MaterialTheme {
                AuthWebViewLayout(
                    modifier = Modifier.size(360.dp, 220.dp),
                    header = {
                        Spacer(Modifier.height(500.dp))
                        TextButton(onClick = {}, modifier = Modifier.testTag("recovery")) { Text("Retry") }
                    },
                ) {
                    Box(Modifier.fillMaxWidth().weight(1f).testTag("browser"))
                }
            }
        }
        onNodeWithTag("browser").assertHeightIsAtLeast(100.dp)
        onNodeWithTag("recovery").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun sharedYouTubeErrorKeepsTheBrowserVisibleInAShortViewport() = runComposeUiTest {
        setContent {
            MaterialTheme {
                YouTubeLoginScreen(
                    state = YouTubeLoginUi(YouTubeLoginStatus.Failed(YouTubeLoginError.MissingDataSyncId)),
                    actions = YouTubeLoginActions(back = {}, retry = {}),
                    webSurface = { modifier -> Box(modifier.testTag("browser")) },
                    modifier = Modifier.size(360.dp, 240.dp),
                )
            }
        }
        onNodeWithTag("browser").assertHeightIsAtLeast(60.dp)
        onNodeWithTag("youtube-login-retry").performScrollTo().assertIsDisplayed()
    }
}
