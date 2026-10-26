package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.ui.navigation.writeM4Screenshot
import kotlin.test.*
import androidx.compose.runtime.setValue

@OptIn(ExperimentalTestApi::class)
class CollectionManagementTest {
    private val item = ContentItem(ContentRef("local", ContentKind.SONG, "one"), "Track", "Artist")
    private val art: ContentArtwork = { _, modifier -> Box(modifier.background(Color.Blue)) }

    @Test fun localSortFilterAndScanPermissionAreIndependentOfPlayback() = runComposeUiTest {
        var permissions = 0
        var scans = 0
        var queue = emptyList<ContentOccurrence>()
        var allowed by mutableStateOf(false)
        val rows = listOf(LocalSongUi(ContentOccurrence("a", item), "Album", 2),
            LocalSongUi(ContentOccurrence("b", item.copy(title = "Other")), "Else", 1))
        assertEquals(listOf("a"), visibleLocalSongs(rows, "album", LocalSongSort.NAME, false).map { it.row.occurrenceId })
        assertEquals(listOf("a", "b"), visibleLocalSongs(rows, "", LocalSongSort.MODIFIED, true).map { it.row.occurrenceId })
        setContent {
            MaterialTheme {
                LocalSongsScreen(LocalSongsUi(rows = rows, scan = LocalScanUi(permissionGranted = allowed)),
                    LocalSongsActions({}, {}, { _, _ -> }, { permissions++ }, { scans++ }, {}, { _, _ -> }, { _, _ -> },
                        { _, rows -> queue = rows }, {}, null), art, Modifier.size(640.dp, 900.dp))
            }
        }
        onNodeWithTag("local-scan-primary").performClick()
        assertEquals(1, permissions); assertEquals(0, scans)
        runOnIdle { allowed = true }
        onNodeWithTag("local-scan-primary").performClick()
        assertEquals(1, scans)
        onNodeWithTag("local-song-b").performClick()
        assertEquals(listOf("a", "b"), queue.map { it.occurrenceId })
        onRoot().writeM4Screenshot("m4-local-songs.png")
    }

    @Test fun downloadTabAndFilteredBatchUseExactVisibleDownloadIds() = runComposeUiTest {
        var paused = emptyList<String>()
        var removed = emptyList<String>()
        var tab by mutableStateOf(DownloadLibraryTab.PROGRESS)
        var query by mutableStateOf("Track")
        val entries = listOf(DownloadEntryUi("one", item, listOf("same", "second"), .25f),
            DownloadEntryUi("two", item.copy(title = "Other"), listOf("third"), .5f))
        setContent {
            MaterialTheme {
                DownloadLibraryScreen(DownloadsUi(tab, query, progress = listOf(DownloadSectionUi(DownloadMediaType.SONG, entries))),
                    DownloadActions({ tab = it }, { query = it }, {}, {}, { paused = it }, {}, { removed = it }, {}), art,
                    Modifier.size(700.dp, 800.dp))
            }
        }
        onNodeWithTag("download-section-pause-SONG").performClick()
        assertEquals(listOf("same", "second"), paused)
        onNodeWithTag("download-remove-one").performClick()
        assertEquals(listOf("same", "second"), removed)
        onNodeWithTag("downloads-tab-DOWNLOADED").performClick()
        onNodeWithTag("downloads-tab-PROGRESS").assertIsDisplayed()
        onNodeWithText("Nothing here yet").assertIsDisplayed()
        runOnIdle { tab = DownloadLibraryTab.PROGRESS; query = "" }
        onRoot().writeM4Screenshot("m4-download-library.png")
    }

    @Test fun historyUsesEventIdentityAndNeverOffersLocalDeleteForRemoteHistory() = runComposeUiTest {
        var source by mutableStateOf(HistorySource.LOCAL)
        var selected by mutableStateOf(setOf(12L))
        var removed = emptySet<Long>()
        val events = listOf(HistoryEventUi(11, ContentOccurrence("first", item), "10:00"),
            HistoryEventUi(12, ContentOccurrence("second", item), "10:01"))
        setContent {
            MaterialTheme {
                HistoryScreen(HistoryUi(source = source, availableSources = HistorySource.entries.toSet(),
                    sections = listOf(HistorySectionUi("today", "Today", events)),
                    selectedEventIds = selected), HistoryActions({ source = it }, {}, {}, { _, _ -> }, {},
                    { selected = if (it in selected) selected - it else selected + it }, { selected = it },
                    { selected = emptySet() }, { removed = it }, {}, {}),
                    art, Modifier.size(700.dp, 850.dp))
            }
        }
        onNodeWithTag("history-remove").performClick()
        onNodeWithTag("history-confirm-remove").performClick()
        assertEquals(setOf(12L), removed)
        runOnIdle { source = HistorySource.REMOTE }
        onNodeWithTag("history-remove").assertDoesNotExist()
        onNodeWithTag("history-event-first").performClick()
        onRoot().writeM4Screenshot("m4-history.png")
    }

    @Test fun historyDefaultsToLocalAvailabilityAndRemoteRowsNeedNoInventedDatabaseIds() = runComposeUiTest {
        var state by mutableStateOf(HistoryUi(source = HistorySource.SPOTIFY))
        var played: String? = null
        setContent { MaterialTheme {
            HistoryScreen(state, HistoryActions({ state = state.copy(source = it) }, {}, {},
                { event, _ -> played = event.row.occurrenceId }, {}, {}, {}, {}, {}, {}, {}),
                art, Modifier.size(700.dp, 850.dp))
        } }
        runOnIdle {
            assertEquals(HistorySource.LOCAL, state.source)
            state = state.copy(source = HistorySource.REMOTE, availableSources = setOf(HistorySource.LOCAL, HistorySource.REMOTE),
                sections = listOf(HistorySectionUi("remote", "Today", listOf(
                    HistoryEventUi(null, ContentOccurrence("remote-one", item), "10:00"),
                    HistoryEventUi(null, ContentOccurrence("remote-two", item), "10:01")))))
        }
        onNodeWithText("Spotify history").assertDoesNotExist()
        onNodeWithTag("history-event-remote-two").performClick()
        assertEquals("remote-two", played)
        onNodeWithTag("history-remove").assertDoesNotExist()
    }
}
