package moe.rukamori.archivetune.shared.ui.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import moe.rukamori.archivetune.shared.domain.lyrics.SyncedLine
import moe.rukamori.archivetune.shared.ui.host.HostLyricsDocument
import moe.rukamori.archivetune.shared.ui.host.HostLyricsStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The lyrics surface has to name the state of the lookup it is in. Before this, a miss for a track no provider knows
 * drew the same empty box as a read still in flight, with nothing to retry: the Linux desktop QA read that as "no lyric
 * line ever renders" and had no control to ask again.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopLyricsBodyTest {
    private val lines = listOf(SyncedLine(time = 0L, text = "One more time"), SyncedLine(time = 4_000L, text = "We're gonna celebrate"))

    private fun document(status: HostLyricsStatus, lines: List<SyncedLine> = emptyList()) =
        HostLyricsDocument("queued:1", lines, loading = status == HostLyricsStatus.LOADING, status = status)

    @Test
    fun aMissNamesItselfAndOffersTheLookupAgain() = runComposeUiTest {
        var retries = 0
        setContent {
            MaterialTheme {
                DesktopLyricsBody(document = document(HostLyricsStatus.NOT_FOUND), onRetry = { retries++ }) { _ ->
                    Text("lines", Modifier.testTag("lyrics-lines"))
                }
            }
        }
        onNodeWithTag("lyrics-status-not_found").assertExists()
        onAllNodesWithTag("lyrics-lines").assertCountEquals(0)
        onNodeWithTag("lyrics-retry").performClick()
        runOnIdle { assertEquals(1, retries, "the empty state asks the providers again") }
    }

    @Test
    fun aFailedLookupKeepsItsOwnStateAndRetry() = runComposeUiTest {
        var retries = 0
        setContent {
            MaterialTheme {
                DesktopLyricsBody(document = document(HostLyricsStatus.FAILED), onRetry = { retries++ }) { _ ->
                    Text("lines", Modifier.testTag("lyrics-lines"))
                }
            }
        }
        onNodeWithTag("lyrics-status-failed").assertExists()
        onNodeWithTag("lyrics-retry").performClick()
        runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun aReadingLookupShowsProgressAndNoRetry() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DesktopLyricsBody(document = document(HostLyricsStatus.LOADING), onRetry = {}) { _ ->
                    Text("lines", Modifier.testTag("lyrics-lines"))
                }
            }
        }
        onNodeWithTag("lyrics-status-loading").assertExists()
        onAllNodesWithTag("lyrics-retry").assertCountEquals(0)
    }

    @Test
    fun aReadyDocumentDrawsTheRenderer() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DesktopLyricsBody(
                    document = HostLyricsDocument("queued:1", lines, loading = false, status = HostLyricsStatus.READY),
                    onRetry = {},
                ) { _ -> Text("lines", Modifier.testTag("lyrics-lines")) }
            }
        }
        onNodeWithTag("lyrics-lines").assertExists()
        onAllNodesWithTag("lyrics-retry").assertCountEquals(0)
    }

    @Test
    fun noDocumentAtAllIsAReadingLookup() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DesktopLyricsBody(document = null, onRetry = {}) { _ ->
                    Text("lines", Modifier.testTag("lyrics-lines"))
                }
            }
        }
        onNodeWithTag("lyrics-status-loading").assertExists()
    }
}
