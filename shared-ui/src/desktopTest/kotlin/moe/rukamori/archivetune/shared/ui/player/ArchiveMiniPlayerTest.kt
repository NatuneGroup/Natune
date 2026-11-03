package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.ui.screens.ContentActions
import moe.rukamori.archivetune.shared.ui.screens.DetailActions
import moe.rukamori.archivetune.shared.ui.screens.DetailKind
import moe.rukamori.archivetune.shared.ui.screens.DetailScreen
import moe.rukamori.archivetune.shared.ui.screens.PagedContentState
import moe.rukamori.archivetune.shared.ui.screens.isWideDetailHero
import moe.rukamori.archivetune.shared.ui.screens.playlistGridColumns
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ArchiveMiniPlayerTest {
    private val item = ContentItem(ContentRef("youtube", ContentKind.SONG, "track"), "Test song", "Test artist")
    private val occurrence = ContentOccurrence("track@1", item)

    @Test
    fun splitViewportAndAdaptiveBreakpointsCoverFoldableAndDesktop() {
        assertFalse(isSplitPlayerViewport(400.dp, 740.dp))
        assertTrue(isSplitPlayerViewport(680.dp, 780.dp))
        assertTrue(isSplitPlayerViewport(768.dp, 1024.dp))
        assertTrue(isSplitPlayerViewport(1000.dp, 600.dp))
        assertFalse(isWideTransportBar(400.dp))
        assertTrue(isWideTransportBar(680.dp))
        assertFalse(isWideDetailHero(540.dp))
        assertTrue(isWideDetailHero(680.dp))
        assertEquals(1, playlistGridColumns(false, 1280.dp))
        assertEquals(2, playlistGridColumns(true, 400.dp))
        assertEquals(3, playlistGridColumns(true, 680.dp))
        assertEquals(4, playlistGridColumns(true, 900.dp))
        assertEquals(5, playlistGridColumns(true, 1280.dp))
    }

    @Test
    fun miniPlayerSwitchesFromCompactBarToWideTransportBar() = runComposeUiTest {
        var wide by mutableStateOf(false)
        val actions = mutableListOf<PlayerAction>()
        val panels = mutableListOf<PlayerPanel>()
        var expanded = 0
        var playPause = 0
        val playback = playerFixture().playback
        val nativeState = PlayerNativeState(
            occurrenceId = "track@1",
            playbackGeneration = 1L,
            canPrevious = true,
            canNext = true,
            canSeek = true,
            shuffle = false,
            repeat = PlayerRepeat.OFF,
            liked = true,
            volume = 0.75f,
            canvasAvailable = false,
            palette = emptyList(),
        )
        setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(if (wide) 960.dp else 420.dp, 96.dp)) {
                    ArchiveMiniPlayer(
                        current = occurrence,
                        playback = playback,
                        nativeState = nativeState,
                        artwork = { _, modifier -> Box(modifier.background(Color.Cyan)) },
                        motion = false,
                        onExpand = { expanded++ },
                        onOpenPanel = { panels += it },
                        onPlayPause = { playPause++ },
                        onSeekTo = {},
                        onAction = { actions += it },
                        onVolumeChange = {},
                    )
                }
            }
        }
        onNodeWithTag("compact-mini-player").assertExists()
        onNodeWithText("Pause").performClick()
        assertEquals(1, playPause)

        runOnIdle { wide = true }
        onNodeWithTag("desktop-transport-bar").assertExists()
        onNodeWithTag("mini-player-seek").assertExists()
        onNodeWithTag("mini-player-volume").assertExists()
        onNodeWithContentDescription("Next track").performClick()
        onNodeWithContentDescription("Lyrics").performClick()
        onNodeWithContentDescription("Queue").performClick()
        onNodeWithContentDescription("Open the player").performClick()
        assertEquals(listOf(PlayerAction.NEXT), actions)
        assertEquals(listOf(PlayerPanel.LYRICS, PlayerPanel.QUEUE), panels)
        assertEquals(1, expanded)
    }

    @Test
    fun detailHeroSwitchesToWideRowAboveFoldableBreakpoint() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DetailScreen(
                    kind = DetailKind.Playlist,
                    header = item,
                    state = PagedContentState(items = listOf(occurrence), initialized = true, complete = true),
                    actions = DetailActions(ContentActions({}, null, {}, {}), {}, {}, {}, null, null),
                    artwork = { _, modifier -> Box(modifier.background(Color.Blue)) },
                    modifier = Modifier.requiredSize(720.dp, 800.dp),
                )
            }
        }
        onNodeWithTag("detail-hero-wide").assertIsDisplayed()
        onNodeWithTag("detail-play").assertIsDisplayed()
        onNodeWithTag("detail-shuffle").assertIsDisplayed()
    }
}
