package moe.rukamori.archivetune.shared.data.provider

import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSection
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A podcast show id (`MPSP…`) names a show page, never a playlist or an album. Search files shows as playlist-shaped
 * rows and the library as album-shaped cards, so a tap on one used to reach the playlist or album parser with a page
 * that has neither shape: the request failed and the show never loaded its episodes.
 */
class YouTubePodcastShowRouteTest {
    private class RecordingTransport(
        private val page: YouTubeEntryPage = YouTubeEntryPage(emptyList()),
    ) : YouTubeBrowseTransport {
        val browsed = ArrayList<Pair<YouTubeBrowse, String?>>()

        override suspend fun page(
            browse: YouTubeBrowse,
            continuation: String?,
        ): YouTubeEntryPage {
            browsed += browse to continuation
            return page
        }
    }

    private fun episode(id: String) = YouTubeEntry(ContentKind.EPISODE, id, "Episode $id")

    @Test
    fun aShowFiledAsAPlaylistRowReadsTheShowPage() =
        runTest {
            val transport = RecordingTransport(YouTubeEntryPage(listOf(episode("ep1")), "episodes-2"))
            val page = YouTubeContentRepository(transport).page(
                ContentRequest(
                    ContentProvider.YOUTUBE,
                    ContentSurface.PLAYLIST,
                    parent = ContentRef("youtube", ContentKind.PLAYLIST, SHOW_ID),
                ),
            )

            assertEquals(SHOW_ID, assertIs<YouTubeBrowse.Podcast>(transport.browsed.single().first).browseId)
            val occurrence = page.items.single()
            assertEquals("podcast:$SHOW_ID:ordinal:0", occurrence.occurrenceId)
            assertEquals(ContentKind.EPISODE, occurrence.item.ref.kind)
            assertEquals("ep1", occurrence.item.ref.id)
            // The provider's cursor leaves wrapped with the request that issued it, never raw.
            assertTrue(assertNotNull(page.continuation).endsWith("|episodes-2"))
        }

    @Test
    fun anAlbumShapedShowCardReadsTheShowPage() =
        runTest {
            val transport = RecordingTransport()
            YouTubeContentRepository(transport).page(
                ContentRequest(
                    ContentProvider.YOUTUBE,
                    ContentSurface.ALBUM,
                    parent = ContentRef("youtube", ContentKind.ALBUM, SHOW_ID),
                ),
            )

            assertEquals(SHOW_ID, assertIs<YouTubeBrowse.Podcast>(transport.browsed.single().first).browseId)
        }

    @Test
    fun thePodcastSurfaceReadsTheShowPage() =
        runTest {
            val transport = RecordingTransport()
            YouTubeContentRepository(transport).page(
                ContentRequest(
                    ContentProvider.YOUTUBE,
                    ContentSurface.PODCAST,
                    parent = ContentRef("youtube", ContentKind.PODCAST, SHOW_ID),
                ),
            )

            assertEquals(SHOW_ID, assertIs<YouTubeBrowse.Podcast>(transport.browsed.single().first).browseId)
        }

    @Test
    fun aBrowseSectionThatNamesAShowReadsTheShowPage() =
        runTest {
            val transport = RecordingTransport()
            YouTubeContentRepository(transport).page(
                ContentRequest(
                    ContentProvider.YOUTUBE,
                    ContentSurface.EXPLORE,
                    section = ContentSection(SHOW_ID, "section-params"),
                ),
            )

            assertEquals(SHOW_ID, assertIs<YouTubeBrowse.Podcast>(transport.browsed.single().first).browseId)
        }

    @Test
    fun theEpisodeCursorStaysOnTheShowRequest() =
        runTest {
            val transport = RecordingTransport(YouTubeEntryPage(listOf(episode("ep1")), "episodes-2"))
            val repository = YouTubeContentRepository(transport)
            val request = ContentRequest(
                ContentProvider.YOUTUBE,
                ContentSurface.PLAYLIST,
                parent = ContentRef("youtube", ContentKind.PLAYLIST, SHOW_ID),
            )

            val first = repository.page(request)
            val second = repository.page(request, first.continuation)

            assertEquals(listOf(null, "episodes-2"), transport.browsed.map { it.second })
            assertEquals(SHOW_ID, assertIs<YouTubeBrowse.Podcast>(transport.browsed.last().first).browseId)
            assertEquals("podcast:$SHOW_ID:ordinal:1", second.items.single().occurrenceId)
        }

    @Test
    fun aCursorFromAnotherRequestIsRefused() =
        runTest {
            val repository = YouTubeContentRepository(RecordingTransport())

            assertFailsWith<IllegalArgumentException> {
                repository.page(
                    ContentRequest(
                        ContentProvider.YOUTUBE,
                        ContentSurface.PLAYLIST,
                        parent = ContentRef("youtube", ContentKind.PLAYLIST, SHOW_ID),
                    ),
                    "playlist:PLsomewhereelse|0|token",
                )
            }
        }

    @Test
    fun anOrdinaryFinitePlaylistKeepsItsPlaylistBrowse() =
        runTest {
            val transport = RecordingTransport()
            YouTubeContentRepository(transport).page(
                ContentRequest(
                    ContentProvider.YOUTUBE,
                    ContentSurface.PLAYLIST,
                    parent = ContentRef("youtube", ContentKind.PLAYLIST, "PLKnoch_ESAUI"),
                ),
            )

            assertEquals("PLKnoch_ESAUI", assertIs<YouTubeBrowse.Playlist>(transport.browsed.single().first).playlistId)
        }

    private companion object {
        /** A podcast show browse id: `MPSP` and the playlist-shaped tail YouTube answers with. */
        const val SHOW_ID = "MPSPPLshow01"
    }
}
