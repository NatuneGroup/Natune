package moe.rukamori.archivetune.shared.ui.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import moe.rukamori.archivetune.shared.domain.lyrics.SyncedLine
import moe.rukamori.archivetune.shared.ui.host.HostLyricsDocument
import moe.rukamori.archivetune.shared.ui.host.HostLyricsStatus
import kotlin.test.Test

/**
 * A track change reaches the player before it reaches the lyrics owner, so for one window the owner still publishes the
 * previous track's document. With the occurrence gate gone the pane drew that document's lines under the new track's
 * title; the pane resolves the document against the occurrence on screen instead and reads as loading until the
 * document for that occurrence arrives. A failed or absent player snapshot keeps the queue's current occurrence
 * readable.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopLyricsPaneTest {
    private val lines = listOf(SyncedLine(time = 0L, text = "One more time"))

    private fun document(occurrenceId: String) =
        HostLyricsDocument(occurrenceId, lines, loading = false, status = HostLyricsStatus.READY)

    @Test
    fun aTrackChangeNeverDrawsThePreviousTracksLines() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DesktopLyricsPane(
                    document = document("queued:a"),
                    playbackOccurrenceId = "queued:b",
                    queueOccurrenceId = "queued:b",
                    onRetry = {},
                ) { _ -> Text("lines", Modifier.testTag("lyrics-lines")) }
            }
        }
        onNodeWithTag("lyrics-status-loading").assertExists()
        onAllNodesWithTag("lyrics-lines").assertCountEquals(0)
    }

    @Test
    fun theOccurrenceOnScreenDrawsItsOwnLines() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DesktopLyricsPane(
                    document = document("queued:b"),
                    playbackOccurrenceId = "queued:b",
                    queueOccurrenceId = "queued:b",
                    onRetry = {},
                ) { _ -> Text("lines", Modifier.testTag("lyrics-lines")) }
            }
        }
        onNodeWithTag("lyrics-lines").assertExists()
    }

    /** The player holds the queue's current occurrence before playback starts and after it fails, so it still reads. */
    @Test
    fun noPlayerSnapshotFallsBackToTheQueuesCurrentOccurrence() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DesktopLyricsPane(
                    document = document("queued:a"),
                    playbackOccurrenceId = null,
                    queueOccurrenceId = "queued:a",
                    onRetry = {},
                ) { _ -> Text("lines", Modifier.testTag("lyrics-lines")) }
            }
        }
        onNodeWithTag("lyrics-lines").assertExists()
    }

    /** The queue has not advanced yet; what the player is playing is the track on screen. */
    @Test
    fun theQueuesCurrentOccurrenceCannotOverrideOneThatIsPlaying() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DesktopLyricsPane(
                    document = document("queued:a"),
                    playbackOccurrenceId = "queued:b",
                    queueOccurrenceId = "queued:a",
                    onRetry = {},
                ) { _ -> Text("lines", Modifier.testTag("lyrics-lines")) }
            }
        }
        onNodeWithTag("lyrics-status-loading").assertExists()
        onAllNodesWithTag("lyrics-lines").assertCountEquals(0)
    }
}
