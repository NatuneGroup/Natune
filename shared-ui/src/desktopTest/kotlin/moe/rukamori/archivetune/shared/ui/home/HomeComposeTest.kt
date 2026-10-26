package moe.rukamori.archivetune.shared.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.ui.navigation.writeM4Screenshot
import moe.rukamori.archivetune.shared.ui.screens.*
import kotlin.test.*
import androidx.compose.runtime.setValue

@OptIn(ExperimentalTestApi::class)
class HomeComposeTest {
    @Test fun sourceAndShelfActionsAreRealAndChangingLookKeepsTheSameRows() = runComposeUiTest {
        val row = ContentOccurrence("track-1", ContentItem(ContentRef("youtube", ContentKind.SONG, "id"), "Track", "Artist"))
        var selectedSource: HomeSource? = null
        var selectedRow: String? = null
        var more = 0
        var design by mutableStateOf(HomeFeedDesign.Rukamori)
        setContent {
            val list = rememberLazyListState()
            MaterialTheme {
                HomeScreen(HomeSource.YOUTUBE, HomeSource.entries, { selectedSource = it }, design,
                    listOf(HomeShelf("picks", "Provider shelf", listOf(row), { more++ })),
                    PagedContentState(initialized = true, items = listOf(row)),
                    ContentActions({ selectedRow = it.occurrenceId }, null, {}, {}),
                    { _, modifier -> Box(modifier.background(Color.Blue)) }, {}, Modifier.size(600.dp, 720.dp), list)
            }
        }
        onNodeWithTag("home-source-SPOTIFY").performClick()
        assertEquals(HomeSource.SPOTIFY, selectedSource)
        onNodeWithTag("home-picks-track-1").performClick()
        assertEquals("track-1", selectedRow)
        onNodeWithTag("shelf-more-picks").performClick()
        assertEquals(1, more)
        onRoot().writeM4Screenshot("m4-home-rukamori.png")
        HomeFeedDesign.entries.forEach { look ->
            runOnIdle { design = look }
            onNodeWithTag("home-picks-track-1").assertIsDisplayed()
        }
    }
}
