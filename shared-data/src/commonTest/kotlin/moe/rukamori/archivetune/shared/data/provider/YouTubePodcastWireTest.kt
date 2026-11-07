package moe.rukamori.archivetune.shared.data.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import moe.rukamori.archivetune.shared.domain.repository.ContentKind

/**
 * The podcast show page parser over a synthetic browse payload: no account, network or InnerTube session. The fixture
 * is the shape YouTube Music answers a `MPSP…` show with - a responsive header and an episode shelf - because the
 * provider files shows as playlist-shaped rows, which the playlist and album parsers cannot read.
 */
class YouTubePodcastWireTest {
    @Test
    fun aShowPageCarriesItsHeaderEpisodesAndCursor() {
        val page = youTubePodcastPage(obj(SHOW_PAGE))

        assertEquals("The Show", page.show.title)
        assertEquals("Host Name", page.show.subtitle)
        assertEquals("https://example.com/show-large", page.show.artworkUri)
        assertEquals("About the show", page.show.description)
        assertEquals("episodes-page-2", page.continuation)
        assertEquals(listOf("episode0001", "episode0002"), page.entries.map { it.id })
    }

    @Test
    fun anEpisodeRowIsAPlayableEntryNamingItsShow() {
        val episode = youTubePodcastPage(obj(SHOW_PAGE)).entries.first()

        assertEquals(ContentKind.EPISODE, episode.kind)
        assertEquals("Episode one", episode.title)
        assertEquals("The Show", episode.subtitle)
        assertEquals("MPSPPLshow01", episode.albumId)
        assertEquals(3723, episode.durationSeconds)
        assertEquals("https://example.com/episode-large", episode.artworkUri)
    }

    @Test
    fun anEpisodeWithoutThumbnailOrCreditStillParsesFromItsPlayOverlay() {
        val episode = youTubePodcastPage(obj(SHOW_PAGE)).entries.last()

        assertEquals("episode0002", episode.id)
        // No linked show credit on its subtitle, so the page's own show name and id fill in.
        assertEquals("Host Name", episode.subtitle)
        assertEquals("MPSPPLshow01", episode.albumId)
        assertEquals(260, episode.durationSeconds)
        assertNull(episode.artworkUri)
    }

    @Test
    fun aRowThatNamesNoVideoIdIsDroppedAndTheCursorSurvives() {
        val page = youTubePodcastPage(obj(rowWithoutVideoIdPage()))

        assertTrue(page.entries.isEmpty())
        assertEquals("episodes-page-2", page.continuation)
    }

    @Test
    fun aShowWithoutASecondaryColumnReadsItsPrimaryShelf() {
        val page = youTubePodcastPage(obj(primaryOnlyPage()))

        assertEquals("The Show", page.show.title)
        assertEquals(listOf("episode0001"), page.entries.map { it.id })
    }

    @Test
    fun anAppendedPageKeepsParsingRowsAndItsNextCursor() {
        val page = youTubePodcastContinuationPage(obj(CONTINUATION_PAGE))

        assertEquals(listOf("episode0003"), page.entries.map { it.id })
        assertEquals("episodes-page-3", page.continuation)
        assertEquals("Episode three", page.entries.single().title)
        assertEquals(1800, page.entries.single().durationSeconds)
    }

    @Test
    fun onlyShowIdsAreShowIds() {
        assertTrue(isYouTubePodcastShowId("MPSPPLshow01"))
        assertTrue(isYouTubePodcastShowId("MPSPOLshow01"))
        listOf("MPREalbum001", "VLPLplaylist", "UCartist001", "MPEDepisode01", "", null).forEach { id ->
            assertEquals(false, isYouTubePodcastShowId(id), "$id")
        }
    }

    @Test
    fun aPlaylistShapedShowRowIsTypedAsAPodcastInTheSharedWire() {
        val entries = youTubeSearchSummaryPage(
            tabbedSearchResultsRenderer = null,
            sectionList = obj(
                """
                {"contents":[{"musicShelfRenderer":{"contents":[{"musicTwoRowItemRenderer":{
                  "title":{"runs":[{"text":"The Show"}]},
                  "subtitle":{"runs":[{"text":"Podcast"},{"text":" • "},{"text":"Host Name"}]},
                  "navigationEndpoint":{"browseEndpoint":{"browseId":"MPSPPLshow01"}},
                  "thumbnailRenderer":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[
                    {"url":"//example.com/show","width":200,"height":200}]}}}
                }}]}}]}
                """.trimIndent(),
            ),
        )

        val entry = entries.single()
        assertEquals(ContentKind.PODCAST, entry.kind)
        assertEquals("MPSPPLshow01", entry.id)
        assertEquals("The Show", entry.title)
    }

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private companion object {
        /** Episode one carries the show credit, a thumbnail and a fixed duration; episode two omits all three. */
        val EPISODE_ONE = """
            {"musicMultiRowListItemRenderer":{
              "onTap":{"watchEndpoint":{"videoId":"episode0001"}},
              "title":{"runs":[{"text":"Episode one",
                "navigationEndpoint":{"browseEndpoint":{"browseId":"MPEDepisode1"}}}]},
              "subtitle":{"runs":[
                {"text":"The Show","navigationEndpoint":{"browseEndpoint":{"browseId":"MPSPPLshow01"}}},
                {"text":" • "},{"text":"1:02:03"}]},
              "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[
                {"url":"//example.com/episode-small","width":60,"height":60},
                {"url":"//example.com/episode-large","width":240,"height":240}]}}},
              "playbackProgress":{"musicPlaybackProgressRenderer":{"durationText":{"runs":[{"text":"1:02:03"}]}}}
            }}
        """.trimIndent()

        val EPISODE_TWO = """
            {"musicMultiRowListItemRenderer":{
              "overlay":{"musicItemThumbnailOverlayRenderer":{"content":{"musicPlayButtonRenderer":{
                "playNavigationEndpoint":{"watchEndpoint":{"videoId":"episode0002"}}}}}},
              "title":{"runs":[{"text":"Episode two"}]},
              "subtitle":{"runs":[{"text":"Trailer"}]},
              "playbackProgress":{"musicPlaybackProgressRenderer":{"durationText":{"runs":[{"text":"4:20"}]}}}
            }}
        """.trimIndent()

        val EPISODE_WITHOUT_VIDEO = """
            {"musicMultiRowListItemRenderer":{"title":{"runs":[{"text":"Unplayable"}]},
              "subtitle":{"runs":[{"text":"Trailer"}]}}}
        """.trimIndent()

        val HEADER = """
            {"musicResponsiveHeaderRenderer":{
              "title":{"runs":[{"text":"The Show"}]},
              "straplineTextOne":{"runs":[
                {"text":"Host Name","navigationEndpoint":{"browseEndpoint":{"browseId":"UC-host0001"}}},
                {"text":" • "},{"text":"42 episodes"}]},
              "subtitle":{"runs":[{"text":"Host Name"}]},
              "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[
                {"url":"//example.com/show-small","width":60,"height":60},
                {"url":"//example.com/show-large","width":544,"height":544}]}}},
              "description":{"musicDescriptionShelfRenderer":{"description":{"runs":[{"text":"About the show"}]}}}
            }}
        """.trimIndent()

        val SHELF = """
            {"musicShelfRenderer":{
              "title":{"runs":[{"text":"Episodes"}]},
              "contents":[$EPISODE_ONE,$EPISODE_TWO],
              "continuations":[{"nextContinuationData":{"continuation":"episodes-page-2"}}]
            }}
        """.trimIndent()

        val SHOW_PAGE = """
            {"contents":{"twoColumnBrowseResultsRenderer":{
              "tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[$HEADER]}}}}],
              "secondaryContents":{"sectionListRenderer":{"contents":[$SHELF]}}
            }}}
        """.trimIndent()

        fun rowWithoutVideoIdPage(): String = """
            {"contents":{"twoColumnBrowseResultsRenderer":{
              "tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[$HEADER]}}}}],
              "secondaryContents":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{
                "contents":[$EPISODE_WITHOUT_VIDEO],
                "continuations":[{"nextContinuationData":{"continuation":"episodes-page-2"}}]}}]}}
            }}}
        """.trimIndent()

        fun primaryOnlyPage(): String = """
            {"contents":{"twoColumnBrowseResultsRenderer":{
              "tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[$HEADER,
                {"musicShelfRenderer":{"contents":[$EPISODE_ONE]}}]}}}}]
            }}}
        """.trimIndent()

        /** The same shelf appended by a continuation browse, whose last item is the following cursor. */
        val CONTINUATION_PAGE = """
            {"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[
              {"musicMultiRowListItemRenderer":{
                "onTap":{"watchEndpoint":{"videoId":"episode0003"}},
                "title":{"runs":[{"text":"Episode three"}]},
                "subtitle":{"runs":[{"text":"Trailer"}]},
                "playbackProgress":{"musicPlaybackProgressRenderer":{"durationText":{"runs":[{"text":"30:00"}]}}}
              }},
              {"continuationItemRenderer":{"continuationEndpoint":{
                "continuationCommand":{"token":"episodes-page-3"}}}}
            ]}}]}
        """.trimIndent()
    }
}
