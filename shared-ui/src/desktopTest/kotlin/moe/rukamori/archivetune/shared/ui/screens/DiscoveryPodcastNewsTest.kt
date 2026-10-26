/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
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

@OptIn(ExperimentalTestApi::class)
class DiscoveryPodcastNewsTest {
    private val art: ContentArtwork = { _, modifier -> Box(modifier.background(Color.Blue)) }
    private val image: NewsImage = { _, modifier -> Box(modifier.background(Color.Magenta)) }
    private val album = ContentItem(ContentRef("youtube", ContentKind.ALBUM, "MPRE-1"), "First", "Artist")

    @Test fun releasesFilterByCategoryAndArtistWithoutReorderingOwnerData() = runComposeUiTest {
        val rows = listOf(ReleaseUi(album, ReleaseCategory.ALBUMS),
            ReleaseUi(album.copy(ref = album.ref.copy(id = "MPRE-2"), title = "Second"), ReleaseCategory.SINGLES))
        var category by mutableStateOf(ReleaseCategory.ALL)
        var query by mutableStateOf("")
        var opened: ContentRef? = null
        setContent { MaterialTheme {
            NewReleasesScreen(ReleasesUi(rows, category, query),
                ReleasesActions({}, { category = it }, { query = it }, { opened = it }, null, {}),
                art, Modifier.size(700.dp, 850.dp))
        } }
        onNodeWithTag("release-category-SINGLES").performClick()
        onNodeWithTag("release-MPRE-1").assertDoesNotExist()
        onNodeWithTag("release-MPRE-2").performClick()
        assertEquals("MPRE-2", opened?.id)
        onNodeWithTag("release-query").performTextInput("ARTIST")
        assertEquals(listOf("MPRE-2"), visibleReleases(rows, category, query).map { it.item.ref.id })
        onRoot().writeM4Screenshot("m4-new-releases.png")
    }

    @Test fun moodsCarryOpaqueBrowseParamsAndChartsKeepOccurrenceRank() = runComposeUiTest {
        val endpoint = ContentRequest(ContentProvider.YOUTUBE, ContentSurface.EXPLORE,
            section = ContentSection("FEmoods", "a+/=?&"))
        var opened: ContentRequest? = null
        setContent { MaterialTheme {
            MoodGenresScreen(listOf(MoodGenreUi("mood-1", "Focus", 0xff225599, endpoint)),
                false, false, {}, { opened = it }, {}, Modifier.size(650.dp, 700.dp))
        } }
        onNodeWithTag("mood-mood-1").performClick()
        assertEquals(endpoint, opened)
        onRoot().writeM4Screenshot("m4-moods.png")
    }

    @Test fun podcastStartsCollectionNotLoadedPrefixAndSubscriptionWaitsForOwner() = runComposeUiTest {
        val header = album.copy(ref = ContentRef("youtube", ContentKind.PODCAST, "MPSP-show"), title = "A podcast")
        val one = ContentOccurrence("occ-1", header.copy(ref = ContentRef("youtube", ContentKind.EPISODE, "same"), title = "Episode one"), "set-1")
        val two = one.copy(occurrenceId = "occ-2", setVideoId = "set-2")
        val request = ContentRequest(ContentProvider.YOUTUBE, ContentSurface.PODCAST, parent = header.ref)
        var plays = emptyList<QueueLoadRequest>()
        var subscribed: Boolean? = null
        var busy by mutableStateOf(false)
        setContent { MaterialTheme {
            PodcastScreen(PodcastUi(request, header, "A description",
                listOf(PodcastEpisodeUi(one, "Yesterday", "24 min", "First description"), PodcastEpisodeUi(two, "Today", "12 min", null)),
                PagedContentState(items = listOf(one, two), initialized = true, continuation = "next"),
                PodcastSubscriptionUi(false, busy, false)),
                PodcastScreenActions({}, { plays = plays + it }, { subscribed = it }, {}, {}),
                art, Modifier.size(700.dp, 950.dp))
        } }
        onNodeWithTag("podcast-play-all").performClick()
        assertNull(plays.last().start)
        onNodeWithTag("podcast-episode-occ-2").performClick()
        assertEquals(QueueStart(two.item.ref, "occ-2", "set-2", 1), plays.last().start)
        assertEquals(request, plays.last().collection)
        assertEquals(QueueCollectionKind.FINITE, plays.last().kind)
        onNodeWithTag("podcast-subscribe").performClick()
        assertEquals(true, subscribed)
        onNodeWithText("Subscribe").assertExists()
        runOnIdle { busy = true }
        onNodeWithTag("podcast-subscribe").assertIsNotEnabled()
        onNodeWithText("Shuffle").assertDoesNotExist()
        onNodeWithText("More").assertDoesNotExist()
        onNodeWithTag("podcast-episodes").performScrollToNode(hasTestTag("content-load-more"))
        onNodeWithTag("content-load-more").assertIsDisplayed()
        onRoot().writeM4Screenshot("m4-podcast-detail.png")
    }

    @Test fun newsUnavailableIsNotSuccessfulEmptyAndReadMarkerUsesLatestActualItem() = runComposeUiTest {
        val news = ContentNews("article/one", "Headline", "Summary", listOf("one", "two"), true, "Author", 20)
        var available by mutableStateOf(false)
        var marks = emptyList<Long>()
        var opened: String? = null
        setContent { MaterialTheme {
            NewsScreen(NewsUi(listOf(news), available = available),
                NewsActions({}, {}, {}, { opened = it }, { marks = marks + it }), image,
                { it.toString() }, Modifier.size(750.dp, 900.dp))
        } }
        onNodeWithTag("news-unavailable").assertIsDisplayed()
        assertTrue(marks.isEmpty())
        runOnIdle { available = true }
        waitForIdle()
        assertEquals(listOf(20L), marks)
        onNodeWithTag("news-open-article/one").performClick()
        assertEquals("article/one", opened)
        onNodeWithTag("news-image-next-article/one").performClick()
        onNodeWithTag("news-image-article/one-1").performClick()
        onNodeWithTag("news-image-dialog").assertExists()
        onNodeWithTag("news-image-close").performClick()
        onRoot().writeM4Screenshot("m4-news.png")
    }

    @Test fun articleRetainsMetadataOnContentFailureAndRendersLoadedMarkdown() = runComposeUiTest {
        val metadata = ContentNews("one", "Article title", "Summary", listOf("photo"), false, "Author", 10)
        var failed by mutableStateOf(true)
        var retries = 0
        setContent { MaterialTheme {
            NewsArticleScreen(NewsArticleUi(metadata, "# Heading\n\n**Bold** and [link](https://example.com).", failed = failed),
                {}, { retries++ }, {}, image, { "Date" }, Modifier.size(750.dp, 950.dp))
        } }
        onNodeWithText("Article title").assertExists()
        onNodeWithTag("article-retry").performClick()
        assertEquals(1, retries)
        runOnIdle { failed = false }
        onNodeWithText("Heading").assertExists()
        onNodeWithText("Bold and link.").assertExists()
        onRoot().writeM4Screenshot("m4-news-article.png")
    }
}
