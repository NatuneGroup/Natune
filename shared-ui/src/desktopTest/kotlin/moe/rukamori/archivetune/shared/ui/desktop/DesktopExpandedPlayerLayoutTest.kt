package moe.rukamori.archivetune.shared.ui.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import moe.rukamori.archivetune.shared.ui.player.playerFixture
import moe.rukamori.archivetune.shared.ui.player.playerSlots

/**
 * The Now Playing surface at the two window sizes the parity checklist names: the player column is a fixed measure and
 * the lyrics column is what is left of the row, at 1430 x 861 and at 1024 x 768.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopExpandedPlayerLayoutTest {
    @Test
    fun thePlayerColumnKeepsItsMeasureAndTheLyricsTakeTheRest() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(1430.dp, 861.dp)) {
                    DesktopExpandedPlayer(
                        state = playerFixture(),
                        slots = playerSlots(),
                        onClose = {},
                        onPlayPause = {},
                        onSeekTo = {},
                        onAction = {},
                        onVolumeChange = {},
                    )
                }
            }
        }
        onNodeWithTag("desktop-expanded-column").assertWidthIsEqualTo(648.dp)
        // 1430 - 2 x 32 padding - 32 gap - 648 leaves the lyrics the rest of the row instead of a second 667 dp half.
        onNodeWithTag("desktop-expanded-lyrics").assertWidthIsEqualTo(686.dp)
    }

    @Test
    fun theSameMeasureHoldsAtAThousandAndTwentyFour() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(1024.dp, 768.dp)) {
                    DesktopExpandedPlayer(
                        state = playerFixture(),
                        slots = playerSlots(),
                        onClose = {},
                        onPlayPause = {},
                        onSeekTo = {},
                        onAction = {},
                        onVolumeChange = {},
                    )
                }
            }
        }
        // 1024 - 64 padding = 960: the 648 measure plus the 32 gap leaves the lyrics exactly their 280 dp minimum.
        onNodeWithTag("desktop-expanded-column").assertWidthIsEqualTo(648.dp)
        onNodeWithTag("desktop-expanded-lyrics").assertWidthIsEqualTo(280.dp)
    }
}
