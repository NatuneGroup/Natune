package moe.rukamori.archivetune.shared.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.ui.home.*
import moe.rukamori.archivetune.shared.ui.screens.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ArchiveNavigationHostTest {
    @Test fun homeMenuReachesNewsArticleAndBackWithoutHostRouting() = runComposeUiTest {
        val navigation = ShellNavigation()
        val news = ContentNews("id/with?params", "A real headline", "Summary", emptyList(), false, "Author", 5)
        val visited = mutableListOf<ArchiveRoute>()
        setContent { MaterialTheme {
            ArchiveNavigationHost(navigation, { route ->
                visited += route
                when (route.destination) {
                    ArchiveDestination.HOME -> ArchiveGraphPage.Home(HomeSource.YOUTUBE, listOf(HomeSource.YOUTUBE), {},
                        HomeFeedDesign.Classic, emptyList(), PagedContentState(initialized = true), {}, {})
                    ArchiveDestination.NEWS -> ArchiveGraphPage.News(NewsUi(listOf(news), available = true), {}, {}, {})
                    ArchiveDestination.ARTICLE -> {
                        assertEquals(news.id, route.arguments["newsId"])
                        ArchiveGraphPage.Article(NewsArticleUi(news, "# Article body"), {})
                    }
                    else -> error("Unexpected fixture destination")
                }
            }, ArchiveGraphActions({ _, _ -> error("not playing") }, { error("not playing") }, null, {}),
                { _, modifier -> Box(modifier.background(Color.Blue)) }, { _, modifier -> Box(modifier) }, { "Date" },
                {}, { _, _ -> error("No host route expected") }, {}, Modifier.size(700.dp, 900.dp))
        } }
        onNodeWithTag("archive-destinations").performClick()
        onNodeWithTag("archive-open-news").performClick()
        onNodeWithTag("news-open-${news.id}").performClick()
        onNodeWithText("Article body").assertIsDisplayed()
        onNodeWithText("Back").performClick()
        onNodeWithTag("news-open-${news.id}").assertExists()
        onNodeWithText("Back").performClick()
        onNodeWithTag("archive-destinations").assertExists()
        assertTrue(visited.any { it.destination == ArchiveDestination.ARTICLE })
        onRoot().writeM4Screenshot("m4-owned-navigation-graph.png")
    }

    @Test fun newsEntryFollowsFeedAvailabilityAndUnreadMark() = runComposeUiTest {
        var entry by mutableStateOf(NewsEntry(available = true, unread = true))
        setContent { MaterialTheme {
            ArchiveNavigationHost(ShellNavigation(), { route ->
                when (route.destination) {
                    ArchiveDestination.HOME -> ArchiveGraphPage.Home(HomeSource.YOUTUBE, listOf(HomeSource.YOUTUBE), {},
                        HomeFeedDesign.Classic, emptyList(), PagedContentState(initialized = true), {}, {})
                    else -> error("Unexpected fixture destination")
                }
            }, ArchiveGraphActions({ _, _ -> error("not playing") }, { error("not playing") }, null, {}),
                { _, modifier -> Box(modifier) }, { _, modifier -> Box(modifier) }, { "Date" },
                {}, { _, _ -> error("No host route expected") }, {}, Modifier.size(700.dp, 900.dp), newsEntry = entry)
        } }
        onNodeWithTag("archive-destinations").performClick()
        onNodeWithTag("archive-open-news").assertExists()
        onNodeWithTag("archive-news-unread", useUnmergedTree = true).assertExists()

        entry = NewsEntry(available = true, unread = false)
        waitForIdle()
        onNodeWithTag("archive-open-news").assertExists()
        onNodeWithTag("archive-news-unread", useUnmergedTree = true).assertDoesNotExist()

        entry = NewsEntry(available = false, unread = true)
        waitForIdle()
        onNodeWithTag("archive-open-news").assertDoesNotExist()
        onNodeWithTag("archive-news-unread", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("archive-open-podcasts").assertExists()
    }
}
