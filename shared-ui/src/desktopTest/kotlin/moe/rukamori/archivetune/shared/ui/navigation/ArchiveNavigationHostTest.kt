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
import moe.rukamori.archivetune.shared.ui.screens.artistalbum.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ArchiveNavigationHostTest {
    @Test fun latestReleaseOpensAlbumEvenWhenItsBrowseReferenceHasAPodcastHint() = runComposeUiTest {
        val navigation = ShellNavigation().apply { open("artist/daft-punk?provider=youtube") }
        val artist = ContentRef("youtube", ContentKind.ARTIST, "daft-punk")
        val release = ArtistReleaseUi(ContentRef("youtube", ContentKind.PODCAST, "MPREb_drumless"),
            "Random Access Memories (Drumless Edition)", null, 2023, ArtistReleaseType.ALBUM)
        val page = ArtistPageUi(ContentItem(artist, "Daft Punk"), null, null, null, emptyList(), listOf(release), "")
        val owner = java.lang.reflect.Proxy.newProxyInstance(ArtistAlbumHost::class.java.classLoader,
            arrayOf(ArtistAlbumHost::class.java)) { _, method, _ ->
            check(method.name == "loadArtistPage") { "Unexpected artist host action: ${method.name}" }
            null
        } as ArtistAlbumHost
        setContent { MaterialTheme {
            ArchiveNavigationHost(navigation, { route -> when (route.destination) {
                ArchiveDestination.ARTIST -> ArchiveGraphPage.Artist(artist, page, false, null,
                    ArtistBlockUiState.Known(false), ArtistAlbumNowPlaying(), owner)
                ArchiveDestination.ALBUM -> ArchiveGraphPage.Detail(DetailKind.Album,
                    ContentItem(release.album.copy(kind = ContentKind.ALBUM), release.title), PagedContentState(initialized = true),
                    DetailActions(ContentActions({}, null, {}, {}), {}, null, null, null, null))
                else -> error("Latest release opened ${route.destination}")
            } }, ArchiveGraphActions({ _, _ -> error("not playing") }, { error("not playing") }, null, {}),
                { _, modifier -> Box(modifier) }, { _, modifier -> Box(modifier) }, { "Date" }, {},
                { _, _ -> error("No host route expected") }, {}, Modifier.size(1000.dp, 900.dp))
        } }
        onNodeWithText(release.title).performScrollTo().performClick()
        onNodeWithTag("detail-Album").assertIsDisplayed()
        val opened = requireNotNull(ArchiveRoutes.resolve(navigation.route))
        assertEquals(ArchiveDestination.ALBUM, opened.destination)
        assertEquals(release.album.id, opened.arguments["albumId"])
        assertEquals("youtube", opened.arguments["provider"])
    }

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
