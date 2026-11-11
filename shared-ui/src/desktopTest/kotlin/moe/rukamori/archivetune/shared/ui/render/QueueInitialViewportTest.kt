package moe.rukamori.archivetune.shared.ui.render

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import moe.rukamori.archivetune.shared.ui.host.LocalAndroidPhonePresentation
import moe.rukamori.archivetune.shared.ui.navigation.LocalDesktopLayout
import moe.rukamori.archivetune.shared.ui.theme.ArchiveTuneTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class QueueInitialViewportTest {
    @Test fun theCinematicPhoneFooterOpensTheQueue() = runSkikoComposeUiTest(size = Size(393f, 852f)) {
        setContent {
            ArchiveTuneTheme(disableAnimations = true) {
                CompositionLocalProvider(LocalAndroidPhonePresentation provides true, LocalDesktopLayout provides false) {
                    RenderAuditFixture.Content("player", true)
                }
            }
        }
        onNodeWithTag("player-cinematic-sleep").assertIsDisplayed()
        onNodeWithTag("player-cinematic-lyrics").assertIsDisplayed()
        onNodeWithTag("player-cinematic-queue").assertIsDisplayed().performClick()
        onNodeWithTag("queue-jump").assertIsDisplayed()
        onNodeWithTag("queue-drag-strip").assertIsDisplayed()
        onNodeWithText("Lyrics").assertDoesNotExist()
    }

    @Test fun openingAtTheFirstTrackKeepsTheExpandedHeader() = runSkikoComposeUiTest(size = Size(393f, 852f)) {
        setContent {
            ArchiveTuneTheme(disableAnimations = true) {
                CompositionLocalProvider(LocalAndroidPhonePresentation provides true, LocalDesktopLayout provides false) {
                    RenderAuditFixture.Content("queue", true)
                }
            }
        }
        onNodeWithTag("queue-jump").assertIsDisplayed()
        onNodeWithTag("queue-infinite").assertIsDisplayed()
        onNodeWithTag("queue-compact-shuffle").assertDoesNotExist()
        onNodeWithTag("queue-row-s1@1").assertIsDisplayed()
        onNodeWithTag("queue-current-play-overlay", useUnmergedTree = true).assertExists()
    }
}
