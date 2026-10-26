/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.library.*
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.ui.navigation.writeM4Screenshot
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class DetailScreenTest {
    private val item = ContentItem(ContentRef("youtube", ContentKind.SONG, "same"), "Same track", "Artist")
    private val entries = listOf(ContentOccurrence("first", item, "set-1"), ContentOccurrence("second", item, "set-2"))
    private val artwork: ContentArtwork = { _, modifier -> Box(modifier.background(Color.Blue)) }

    @Test fun emptyPageWithContinuationCanAdvanceAndProviderKeysCannotCollideWithFooter() = runComposeUiTest {
        var state by mutableStateOf(PagedContentState(initialized = true, continuation = "tail"))
        var loads = 0
        setContent {
            MaterialTheme {
                DetailScreen(DetailKind.Playlist, null, state,
                    DetailActions(ContentActions({}, null, { loads++ }, {}), {}, null, null, null, null),
                    artwork, Modifier.size(540.dp, 600.dp))
            }
        }
        onNodeWithTag("content-load-more").performClick()
        assertEquals(1, loads)
        runOnIdle {
            state = PagedContentState(initialized = true, items = listOf(ContentOccurrence("m4-pagination-status", item)))
        }
        onNodeWithTag("occurrence-m4-pagination-status").assertIsDisplayed()
    }

    @Test fun playlistRowsDispatchTheSelectedOccurrenceAndKeepHeaderAndContinuation() = runComposeUiTest {
        var selected: String? = null
        var more = 0
        var plays = 0
        val content = ContentActions({ selected = it.occurrenceId }, null, { more++ }, { error("not failed") })
        setContent {
            MaterialTheme {
                DetailScreen(DetailKind.Playlist, item.copy(title = "Playlist"),
                    PagedContentState(items = entries, continuation = "next", initialized = true),
                    DetailActions(content, {}, { plays++ }, {}, null, null), artwork,
                    Modifier.size(540.dp, 920.dp))
            }
        }
        onNodeWithTag("occurrence-second").performClick()
        assertEquals("second", selected)
        onNodeWithTag("detail-play").performClick()
        assertEquals(1, plays)
        onNodeWithTag("detail-shuffle").assertIsNotEnabled()
        onNodeWithTag("content-load-more").performScrollTo().performClick()
        assertEquals(1, more)
        onRoot().writeM4Screenshot("m4-playlist-detail.png")
    }
    @Test fun podcastDoesNotInventHeroActionsAndEmptyStillHasBack() = runComposeUiTest {
        var back = 0
        setContent {
            MaterialTheme {
                DetailScreen(DetailKind.Podcast, null, PagedContentState(initialized = true),
                    DetailActions(ContentActions({}, null, {}, {}), { back++ }, null,
                        { error("podcast shuffle") }, { error("podcast library") }, { error("podcast overflow") }),
                    artwork, Modifier.size(540.dp, 600.dp))
            }
        }
        onNodeWithText("Back").performClick()
        assertEquals(1, back)
        onNodeWithText("Nothing here yet").assertIsDisplayed()
        onNodeWithTag("detail-shuffle").assertDoesNotExist()
        onNodeWithTag("detail-more").assertDoesNotExist()
    }
    @Test fun searchDraftDoesNotSubmitUntilExplicitSearchAndScopeDoesNotEraseIt() = runComposeUiTest {
        var submitted = ""
        var submits = 0
        var scope = SearchScope.Online
        setContent {
            var draft by remember { mutableStateOf("") }
            MaterialTheme {
                SearchScreen(draft, scope, { draft = it }, { scope = it }, { submitted = draft; submits++ }, null, null,
                    PagedContentState(), ContentActions({}, null, {}, {}), artwork)
            }
        }
        onNodeWithTag("search-query").performClick().performImeAction()
        assertEquals(0, submits)
        onNodeWithTag("search-submit").assertIsNotEnabled()
        onNodeWithTag("search-query").performTextInput("query")
        assertEquals("", submitted)
        onNodeWithTag("search-submit").performClick()
        assertEquals("query", submitted)
        assertEquals(1, submits)
        onNodeWithText("Local").performClick()
        assertEquals(SearchScope.Local, scope)
        onNodeWithTag("search-query").assertTextContains("query")
    }
    @Test fun libraryUsesPersistedFilterIdentityRatherThanLabelRoundTrip() = runComposeUiTest {
        var filter = LibraryFilter.LIBRARY
        setContent {
            MaterialTheme {
                LibraryScreen(filter, DefaultLibraryFilterOrder, { filter = it },
                    PagedContentState(initialized = true), ContentActions({}, null, {}, {}), artwork, {}, {})
            }
        }
        onNodeWithTag("library-filter-PLAYLISTS").performClick()
        assertEquals(LibraryFilter.PLAYLISTS, filter)
    }
}
