package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.ui.home.HomeFeedDesign
import moe.rukamori.archivetune.shared.ui.home.HomeSource
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveDestination
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphActions
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveNavigationHost
import moe.rukamori.archivetune.shared.ui.navigation.ShellNavigation
import moe.rukamori.archivetune.shared.ui.navigation.ShellTab
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The search shortcut's one visible effect: it opens Search with the query field focused, so the next keystroke is text
 * rather than a transport key. The request is one-shot; a tab visit the user makes themselves leaves focus alone.
 */
@OptIn(ExperimentalTestApi::class)
class SearchFocusTest {
    @Test
    fun requestedFocusLandsOnTheFieldAndTypingEntersText() = runComposeUiTest {
        var query by mutableStateOf("")
        var consumed = 0
        setContent {
            MaterialTheme {
                SearchScreen(
                    query = query,
                    scope = SearchScope.Online,
                    onQuery = { query = it },
                    onScope = {},
                    onSubmit = {},
                    onVoice = null,
                    onRecognition = null,
                    state = PagedContentState(initialized = true),
                    actions = ContentActions({}, null, {}, {}),
                    artwork = { _, modifier -> Box(modifier) },
                    focusRequested = true,
                    onFocusConsumed = { consumed++ },
                )
            }
        }
        onNodeWithTag("search-query").assertIsFocused()
        onNodeWithTag("search-query").performTextInput("abc")
        assertEquals("abc", query)
        assertEquals(1, consumed)
    }

    @Test
    fun aTabVisitWithoutAShortcutDoesNotTakeFocus() = runComposeUiTest {
        var query by mutableStateOf("")
        var consumed = 0
        setContent {
            MaterialTheme {
                SearchScreen(
                    query = query,
                    scope = SearchScope.Online,
                    onQuery = { query = it },
                    onScope = {},
                    onSubmit = {},
                    onVoice = null,
                    onRecognition = null,
                    state = PagedContentState(),
                    actions = ContentActions({ _ -> }, null, {}, {}),
                    artwork = { _, modifier -> Box(modifier) },
                    focusRequested = false,
                    onFocusConsumed = { consumed++ },
                )
            }
        }
        onNodeWithTag("search-query").assertIsNotFocused()
        assertEquals(0, consumed)
    }

    /**
     * The shortcut's navigation half through the real host: the shell moves to the Search root, the screen takes the
     * field, and the request is consumed once so it cannot replay on a later visit.
     */
    @Test
    fun theShortcutNavigationFocusesTheFieldThroughTheRealNavigationHost() = runComposeUiTest {
        val navigation = ShellNavigation()
        var query by mutableStateOf("")
        var focusRequested by mutableStateOf(false)
        var consumed = 0
        setContent {
            MaterialTheme {
                ArchiveNavigationHost(
                    navigation = navigation,
                    page = { route ->
                        when (route.destination) {
                            ArchiveDestination.HOME -> ArchiveGraphPage.Home(
                                HomeSource.YOUTUBE, listOf(HomeSource.YOUTUBE), {}, HomeFeedDesign.Classic,
                                emptyList(), PagedContentState(initialized = true), {}, {},
                            )
                            ArchiveDestination.SEARCH, ArchiveDestination.SEARCH_RESULTS ->
                                ArchiveGraphPage.Search(query, SearchScope.Online, ContentProvider.YOUTUBE,
                                    PagedContentState(initialized = true), { query = it }, {}, null, null, {}, {})
                            else -> error("Unexpected fixture destination ${route.destination}")
                        }
                    },
                    actions = ArchiveGraphActions({ _, _ -> error("not playing") }, { error("not playing") }, null, {}),
                    artwork = { _, modifier -> Box(modifier) },
                    newsImage = { _, modifier -> Box(modifier) },
                    formatNewsDate = { "Date" },
                    librarySourceSelector = {},
                    hostScreen = { _, _ -> error("No host route expected") },
                    overlays = {},
                    modifier = Modifier.size(700.dp, 900.dp),
                    searchFocusRequested = focusRequested,
                    onSearchFocusConsumed = { focusRequested = false; consumed++ },
                )
            }
        }
        // The root's KeymapAction.Search: select the Search root, then ask this screen for the field once.
        runOnIdle {
            navigation.select(ShellTab.Search, toRoot = true)
            focusRequested = true
        }
        onNodeWithTag("search-query").assertIsFocused()
        onNodeWithTag("search-query").performTextInput("abc")
        assertEquals("abc", query)
        assertEquals(1, consumed)
        assertEquals(false, focusRequested)
    }
}
