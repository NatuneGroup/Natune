package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.settings.*
import kotlin.math.abs
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class QualityBadgeTest {
    @Test fun bitChordSeekRowCentresTheLosslessBadge() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(400.dp, 740.dp)) {
                    BottomSheetPlayer(playerFixture().copy(formatLabel = "Lossless", formatLossless = true),
                        PlayerTestActions().callbacks,
                        PreferenceSnapshot.of(PlayerDesignStyleKey to PlayerDesignStyle.BITCHORD.name), playerSlots(), true)
                }
            }
        }
        onNodeWithText("Lossless").assertIsDisplayed()
        assertBadgeCentred("player-seek-quality-row", "player-seek-quality")
    }

    @Test fun fullscreenLyricsTimeRowCentresTheLosslessBadge() = runComposeUiTest {
        val interaction = PlayerInteractionState().apply { fullscreenLyrics = true; panel = PlayerPanel.LYRICS }
        setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(393.dp, 740.dp)) {
                    BottomSheetPlayer(playerFixture().copy(formatLabel = "Lossless", formatLossless = true),
                        PlayerTestActions().callbacks, PreferenceSnapshot.EMPTY,
                        playerSlots(PlayerLyricsStatus("track@1", false)), false, interaction = interaction)
                }
            }
        }
        onNodeWithText("Lossless").assertIsDisplayed()
        assertBadgeCentred("fullscreen-lyrics-time", "fullscreen-lyrics-quality")
    }

    private fun ComposeUiTest.assertBadgeCentred(rowTag: String, badgeTag: String) {
        val row = onNodeWithTag(rowTag).fetchSemanticsNode().boundsInRoot
        val badge = onNodeWithTag(badgeTag).fetchSemanticsNode().boundsInRoot
        assertTrue(abs(row.center.x - badge.center.x) <= 1f,
            "badge centre ${badge.center.x} is not on the row centre ${row.center.x}")
    }
}
