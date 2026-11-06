package moe.rukamori.archivetune.shared.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.*
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot

/**
 * The mini-player slot is chrome over the page: it paints a surface, so covered cards cannot show through, and it
 * claims the presses its own controls did not take, so a covered card cannot be clicked through it either.
 */
@OptIn(ExperimentalTestApi::class)
class MiniPlayerStripTest {
    @Test fun aClickOnTheStripNeverReachesThePageCardAndItsOwnControlsStillWork() = runComposeUiTest {
        var cardClicks = 0
        var buttonClicks = 0
        setContent {
            MaterialTheme {
                Box(Modifier.size(1000.dp, 720.dp)) {
                    ArchiveTuneShell(
                        navigation = ShellNavigation(),
                        preferences = PreferenceSnapshot.EMPTY,
                        onSearch = {},
                        miniPlayer = {
                            // A mini player is a bar: its own content sets the slot height, so the fixture must not
                            // fill the page area that the strip sits in front of.
                            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = { buttonClicks++ },
                                    modifier = Modifier.testTag("strip-button"),
                                ) { Text("Play") }
                                Box(Modifier.weight(1f).fillMaxHeight().testTag("strip-empty"))
                            }
                        },
                        content = {
                            Box(
                                Modifier.fillMaxSize().background(Color.Red).clickable { cardClicks++ }
                                    .testTag("under-card"),
                            )
                        },
                    )
                }
            }
        }
        // The card is a real click target where the chrome does not cover it.
        onNodeWithTag("under-card").performTouchInput { click(center) }
        assertEquals(1, cardClicks)
        // Over the strip's empty area the same press is claimed by the chrome above the page.
        onNodeWithTag("strip-empty").performTouchInput { click(center) }
        assertEquals(1, cardClicks)
        // A control inside the strip dispatches first and keeps its click.
        onNodeWithTag("strip-button").performClick()
        assertEquals(1, buttonClicks)
        // The empty area adds no click action, focus or semantics of its own.
        onNodeWithTag("strip-empty").assertHasNoClickAction()
    }
}
