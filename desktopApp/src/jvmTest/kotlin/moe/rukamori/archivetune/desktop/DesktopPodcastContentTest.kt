package moe.rukamori.archivetune.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.innertube.models.Artist
import moe.rukamori.archivetune.innertube.models.EpisodeItem
import moe.rukamori.archivetune.innertube.models.PodcastItem
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.shared.domain.queue.IncompleteFinitePlaylistException
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.ui.screens.PodcastEpisodeUi

class DesktopPodcastContentTest {
    private val header = ContentItem(
        ContentRef(ContentProvider.YOUTUBE.id, ContentKind.PODCAST, "MPSPshow"), "Show", subtitle = "Host",
    )

    @Test
    fun showMapperKeepsTheProviderIdentity() {
        val item = PodcastItem(
            browseId = "MPSPshow", playlistId = null, title = "Show",
            author = Artist("Host", "UChost"), thumbnail = "https://img.test/s.jpg",
        ).desktopPodcastItem()
        assertEquals(ContentRef(ContentProvider.YOUTUBE.id, ContentKind.PODCAST, "MPSPshow"), item.ref)
        assertEquals("Host", item.subtitle)
        assertEquals("https://img.test/s.jpg", item.artworkUri)
    }

    @Test
    fun episodeMapperKeepsMetadataAndRejectsEndpointMismatch() {
        val episode = EpisodeItem(
            id = "ep1", browseId = null, title = "One", podcast = Artist("Show", "MPSPshow"),
            description = "About", dateText = "Jan 1", durationText = "10:00", duration = 600,
            thumbnail = "https://img.test/e.jpg", endpoint = WatchEndpoint(videoId = "ep1"),
        )
        val ui = episode.desktopPodcastEpisode(header)!!
        assertEquals("desktop-podcast:MPSPshow:episode:ep1", ui.row.occurrenceId)
        assertEquals(ContentKind.EPISODE, ui.row.item.ref.kind)
        assertEquals(600_000L, ui.row.item.durationMs)
        assertEquals("MPSPshow", ui.row.item.albumId)
        assertEquals("About", ui.description)
        // An episode whose endpoint points elsewhere or with no identity never becomes a row.
        assertNull(episode.copy(endpoint = WatchEndpoint(videoId = "other")).desktopPodcastEpisode(header))
        assertNull(episode.copy(id = "").desktopPodcastEpisode(header))
    }

    @Test
    fun subscriptionCodecRoundTripsAndRejectsInvalidRows() {
        val shows = listOf(
            header,
            ContentItem(ContentRef(ContentProvider.YOUTUBE.id, ContentKind.PODCAST, "MPSPother"), "Other"),
        )
        assertEquals(shows, decodePodcastSubscriptions(encodePodcastSubscriptions(shows)))
        assertFailsWith<Exception> { decodePodcastSubscriptions("""[{"id":7,"title":"x"}]""") }
        assertFailsWith<Exception> { decodePodcastSubscriptions("""[{"id":"","title":"x"}]""") }
        assertFailsWith<Exception> { decodePodcastSubscriptions("""{"id":"x"}""") }
        val oversized = (1..257).joinToString(",", "[", "]") { """{"id":"s$it","title":"t$it"}""" }
        assertFailsWith<Exception> { decodePodcastSubscriptions(oversized) }
    }

    private class FakeTransport(
        private val first: DesktopPodcastEpisodePage,
        private val pages: Map<String, DesktopPodcastEpisodePage> = emptyMap(),
    ) : DesktopPodcastTransport {
        override suspend fun browse(query: String, continuation: String?): DesktopPodcastBrowsePage =
            throw UnsupportedOperationException()
        override suspend fun detail(browseId: String) =
            DesktopPodcastDetail(HEADER, null, first)
        override suspend fun episodes(header: ContentItem, continuation: String) =
            pages[continuation] ?: error("Unknown token $continuation")
    }

    private fun episode(id: String) = PodcastEpisodeUi(
        ContentOccurrence(
            "desktop-podcast:show:episode:$id",
            ContentItem(ContentRef(ContentProvider.YOUTUBE.id, ContentKind.EPISODE, id), "Ep $id"),
        ),
        null, null, null,
    )

    @Test
    fun completionDrainsEveryPageOnce() = runBlocking {
        val transport = FakeTransport(
            DesktopPodcastEpisodePage(listOf(episode("a")), "t2"),
            mapOf("t2" to DesktopPodcastEpisodePage(listOf(episode("b"), episode("a")), null)),
        )
        val episodes = completeDesktopPodcast(transport, "show")
        assertEquals(listOf("a", "b"), episodes.map { it.row.item.ref.id })
    }

    @Test
    fun completionRejectsBlankRepeatedOrEmptyTails() {
        // A body, not `= runBlocking { }`: JUnit4 only runs void test methods, and the last assertion here
        // returns the exception it matched.
        runBlocking {
            assertFailsWith<IncompleteFinitePlaylistException> {
                completeDesktopPodcast(FakeTransport(DesktopPodcastEpisodePage(listOf(episode("a")), " ")), "show")
            }
            assertFailsWith<IncompleteFinitePlaylistException> {
                completeDesktopPodcast(
                    FakeTransport(
                        DesktopPodcastEpisodePage(listOf(episode("a")), "t2"),
                        mapOf("t2" to DesktopPodcastEpisodePage(listOf(episode("b")), "t2")),
                    ),
                    "show",
                )
            }
            // An unrecognized envelope parses as an empty page with no token; that is incomplete, not done.
            assertFailsWith<IncompleteFinitePlaylistException> {
                completeDesktopPodcast(
                    FakeTransport(
                        DesktopPodcastEpisodePage(listOf(episode("a")), "t2"),
                        mapOf("t2" to DesktopPodcastEpisodePage(emptyList(), null)),
                    ),
                    "show",
                )
            }
        }
    }

    @Test
    fun completionStopsAtTheRequestBound() {
        runBlocking {
            val pages = (1..250).associate { i -> "t$i" to DesktopPodcastEpisodePage(listOf(episode("e$i")), "t${i + 1}") }
            assertFailsWith<IncompleteFinitePlaylistException> {
                completeDesktopPodcast(
                    FakeTransport(DesktopPodcastEpisodePage(listOf(episode("e0")), "t1"), pages),
                    "show",
                )
            }
        }
    }

    private companion object {
        val HEADER = ContentItem(ContentRef(ContentProvider.YOUTUBE.id, ContentKind.PODCAST, "show"), "Show")
    }
}
