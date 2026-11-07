package moe.rukamori.archivetune.shared.data.provider

import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A YouTube Music mix is a playlist id that never ends. The repository used to refuse its page outright, so the desktop
 * detail route fell into the catalogue's generic load failure ("Could not load content. Your existing items are
 * unchanged.") while the queue kept the track: the page had no way to ask for an endless list.
 */
class YouTubeEndlessPlaylistPageTest {
    @Test fun aMixPlaylistPageReadsThroughWatchNext() = runTest {
        val browsed = ArrayList<YouTubeBrowse>()
        val repository = YouTubeContentRepository(
            transport = object : YouTubeBrowseTransport {
                override suspend fun page(browse: YouTubeBrowse, continuation: String?): YouTubeEntryPage {
                    browsed += browse
                    return YouTubeEntryPage(listOf(YouTubeEntry(ContentKind.SONG, "video", "Technologic")), "next-page")
                }
            },
        )

        val page = repository.page(
            ContentRequest(
                ContentProvider.YOUTUBE,
                ContentSurface.PLAYLIST,
                parent = ContentRef("youtube", ContentKind.PLAYLIST, MIX_ID),
            ),
        )

        assertEquals(1, page.items.size)
        val browse = assertIs<YouTubeBrowse.WatchNext>(browsed.single())
        assertEquals(MIX_ID, browse.playlistId)
    }

    @Test fun aFinitePlaylistKeepsItsPlaylistBrowse() = runTest {
        val browsed = ArrayList<YouTubeBrowse>()
        val repository = YouTubeContentRepository(
            transport = object : YouTubeBrowseTransport {
                override suspend fun page(browse: YouTubeBrowse, continuation: String?): YouTubeEntryPage {
                    browsed += browse
                    return YouTubeEntryPage(listOf(YouTubeEntry(ContentKind.SONG, "video", "One More Time")), null)
                }
            },
        )

        repository.page(
            ContentRequest(
                ContentProvider.YOUTUBE,
                ContentSurface.PLAYLIST,
                parent = ContentRef("youtube", ContentKind.PLAYLIST, "PLKnoch_ESAUI"),
            ),
        )

        assertEquals("PLKnoch_ESAUI", assertIs<YouTubeBrowse.Playlist>(browsed.single()).playlistId)
    }

    private companion object {
        /** A YouTube Music "Presenting …" mix, as the search page returns it. */
        const val MIX_ID = "RDCLAK5uy_n20FRYQXNt1p1wS55Nj2r14IouO5weaYU"
    }
}
