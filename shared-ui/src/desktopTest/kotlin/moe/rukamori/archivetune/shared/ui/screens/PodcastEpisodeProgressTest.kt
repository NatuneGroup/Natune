package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import moe.rukamori.archivetune.shared.domain.playback.PodcastProgress
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentRef

@OptIn(ExperimentalTestApi::class)
class PodcastEpisodeProgressTest {
    @Test fun rowsReactToBookmarksAndPlayedMarkersWithoutBorrowingAnotherProvidersProgress() = runComposeUiTest {
        val ref = ContentRef("youtube", ContentKind.EPISODE, "episode")
        var rows by mutableStateOf(mapOf("youtube:episode" to PodcastProgress("youtube:episode", 60_000L, 600_000L, false, 1L)))
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalPodcastProgress provides rows) {
                    Column {
                        PodcastEpisodeProgress(ref)
                        PodcastEpisodeProgress(ref.copy(provider = "local"))
                    }
                }
            }
        }
        onNodeWithTag("episode-progress-youtube:episode").assertExists()
        onNodeWithText("9:00 remaining").assertExists()
        onNodeWithTag("episode-progress-local:episode").assertDoesNotExist()
        runOnIdle { rows = mapOf("youtube:episode" to PodcastProgress("youtube:episode", 0L, 600_000L, true, 2L)) }
        onNodeWithText("Played", substring = false).assertExists()
        onNodeWithText("9:00 remaining").assertDoesNotExist()
        runOnIdle { rows = emptyMap() }
        onNodeWithTag("episode-progress-youtube:episode").assertDoesNotExist()
    }

    @Test fun unknownDurationShowsPlayedPositionInsteadOfInventingARemainingTime() = runComposeUiTest {
        val ref = ContentRef("youtube", ContentKind.EPISODE, "unknown")
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalPodcastProgress provides mapOf(
                    "youtube:unknown" to PodcastProgress("youtube:unknown", 65_000L, null, false, 1L),
                )) { PodcastEpisodeProgress(ref) }
            }
        }
        onNodeWithText("Played 1:05").assertExists()
    }
}
