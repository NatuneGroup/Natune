package moe.rukamori.archivetune.shared.data.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentSearchFilter

/**
 * The shared section-list parsers, over synthetic renderer payloads: no account, network or InnerTubeX session. Each
 * fixture is the JSON shape the Android renderer models declare, so a provider field renamed or moved shows up here
 * rather than as a shelf that silently loses its rows on the hosts.
 */
class YouTubeShelfWireTest {
    @Test
    fun homeFeedKeepsItsChipsShelvesAndCursor() {
        val page = youTubeHomeShelfPage(obj("""
            {
              "header":{"chipCloudRenderer":{"chips":[
                {"chipCloudChipRenderer":{"text":{"runs":[{"text":"Relax"}]},
                  "navigationEndpoint":{"browseEndpoint":{"params":"chip-params"}},
                  "onDeselectedCommand":{"browseEndpoint":{"params":"chip-deselect"}}}},
                {"chipCloudChipRenderer":{"text":{"runs":[{"text":"Energise"}]},
                  "navigationEndpoint":{"browseEndpoint":{"params":"chip-b"}}}}
              ]}},
              "contents":[{"musicCarouselShelfRenderer":{
                "header":{"musicCarouselShelfBasicHeaderRenderer":{
                  "strapline":{"simpleText":"Mixed for you"},
                  "title":{"runs":[{"text":"Quick picks"}]},
                  "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[
                    {"url":"//example.com/shelf","width":120,"height":120}]}}},
                  "moreContentButton":{"buttonRenderer":{"navigationEndpoint":{
                    "browseEndpoint":{"browseId":"FEmusic_moods_and_genres","params":"more-params"}}}}}},
                "numItemsPerColumn":2,
                "contents":[${song("song_000001")}]
              }}],
              "continuations":[{"nextContinuationData":{"continuation":"home-next"}}]
            }
        """.trimIndent()))
        assertEquals("home-next", page.continuation)
        assertEquals(listOf("Relax", "Energise"), page.chips.map { it.title })
        assertEquals("chip-params", page.chips.first().params)
        assertEquals("chip-deselect", page.chips.first().deselectParams)
        assertNull(page.chips.last().deselectParams)
        val shelf = page.shelves.single()
        assertEquals("Quick picks#0", shelf.key)
        assertEquals("Mixed for you", shelf.label)
        assertEquals("https://example.com/shelf", shelf.thumbnail)
        assertEquals(2, shelf.numItemsPerColumn)
        assertEquals("more-params", shelf.more?.params)
        val row = shelf.entries.single()
        assertEquals(ContentKind.SONG, row.kind)
        assertEquals("song_000001", row.id)
        assertEquals("Song title", row.title)
        assertEquals(225, row.durationSeconds)
        assertEquals(listOf("Artist"), row.artists.map { it.name })
        assertEquals("UC-artist01", row.artists.single().id)
        assertEquals("MPRE-album01", row.albumId)
        assertEquals("Album", row.album)
        assertEquals("https://example.com/large", row.artworkUri)
        assertTrue(row.explicit)
    }

    @Test
    fun chartsNumberTheTopShelfAndGridTheGenreShelves() {
        val page = youTubeChartsShelfPage(
            sectionList = obj("""
                {"contents":[
                  {"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{
                    "title":{"runs":[{"text":"Top songs"}]}}},"contents":[${song("top_song_01")}]}},
                  {"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{
                    "title":{"runs":[{"text":"Trending"}]}}},"contents":[${song("trend_song")}]}},
                  {"gridRenderer":{"header":{"gridHeaderRenderer":{"title":{"runs":[{"text":"New releases"}]}}},
                    "items":[${album("MPRE-chart01")}]}}
                ]}
            """.trimIndent()),
            continuationSectionList = obj("""{"continuations":[{"nextContinuationData":{"continuation":"chart-next"}}]}"""),
        )
        assertEquals("chart-next", page.continuation)
        assertEquals(listOf("Top songs#0", "Trending#1", "New releases#2"), page.shelves.map { it.key })
        assertTrue(page.shelves[0].numbered)
        assertEquals(false, page.shelves[0].grid)
        assertEquals(false, page.shelves[1].numbered)
        assertTrue(page.shelves[1].grid)
        assertEquals(ContentKind.ALBUM, page.shelves[2].entries.single().kind)
        assertEquals("MPRE-chart01", page.shelves[2].entries.single().id)
    }

    @Test
    fun exploreFindsTheReleaseAndMoodCarouselsByTheirMoreTarget() {
        val page = youTubeExplorePage(obj("""
            {"contents":[
              {"musicCarouselShelfRenderer":{
                "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"New releases"}]},
                  "moreContentButton":{"buttonRenderer":{"navigationEndpoint":{
                    "browseEndpoint":{"browseId":"FEmusic_new_releases_albums"}}}}}},
                "contents":[${album("MPRE-explore1")}]}},
              {"musicCarouselShelfRenderer":{
                "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Moods & genres"}]},
                  "moreContentButton":{"buttonRenderer":{"navigationEndpoint":{
                    "browseEndpoint":{"browseId":"FEmusic_moods_and_genres"}}}}}},
                "contents":[{"musicNavigationButtonRenderer":{
                  "buttonText":{"runs":[{"text":"Chill"}]},
                  "solid":{"leftStripeColor":4294901760},
                  "clickCommand":{"browseEndpoint":{"browseId":"FEmusic_moods_and_genres","params":"mood-params"}}}}]}}
            ]}
        """.trimIndent()))
        val release = page.releases.single()
        assertEquals(YouTubeReleaseType.ALBUM, release.type)
        assertEquals(2024, release.year)
        assertEquals("MPRE-explore1", release.entry.id)
        assertEquals("Album title", release.entry.title)
        assertEquals(listOf("Artist"), release.entry.artists.map { it.name })
        val mood = page.moods.single()
        assertEquals("Chill", mood.title)
        assertEquals(4294901760L, mood.stripeArgb)
        assertEquals("FEmusic_moods_and_genres", mood.section.id)
        assertEquals("mood-params", mood.section.params)
    }

    @Test
    fun aCarouselWithoutRowsIsNoShelfRatherThanAnEmptyOne() {
        val page = youTubeHomeShelfPage(obj("""
            {"contents":[{"musicCarouselShelfRenderer":{
              "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Empty"}]}}},
              "contents":[]}}]}
        """.trimIndent()))
        assertTrue(page.shelves.isEmpty())
        assertNull(page.continuation)
    }

    @Test
    fun newReleasesReadGridAndCarouselTiles() {
        val releases = youTubeNewReleasesPage(obj("""
            {"contents":[
              {"gridRenderer":{"header":{"gridHeaderRenderer":{"title":{"runs":[{"text":"Albums"}]}}},
                "items":[${album("MPRE-grid01")}]}},
              {"musicCarouselShelfRenderer":{
                "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Singles"}]}}},
                "contents":[${album("MPRE-carousel2")}]}}
            ]}
        """.trimIndent()))
        assertEquals(listOf("MPRE-grid01", "MPRE-carousel2"), releases.map { it.entry.id })
    }

    @Test
    fun searchSummaryReadsTheTopCardInlineRowsAndShelves() {
        val summary = youTubeSearchSummaryPage(
            tabbedSearchResultsRenderer = obj("""
                {"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
                  {"musicCardShelfRenderer":{
                    "title":{"runs":[{"text":"Card title"}]},
                    "subtitle":{"runs":[{"text":"Song • Artist"}]},
                    "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[
                      {"url":"https://example.com/card","width":200,"height":200}]}}},
                    "onTap":{"watchEndpoint":{"videoId":"card_song_1"}},
                    "contents":[{"musicResponsiveListItemRenderer":${songFields("inline_song2")}}]}},
                  {"itemSectionRenderer":{"contents":[
                    {"musicResponsiveListItemRenderer":${songFields("row_song_03")}},
                    {"musicShelfRenderer":{"title":{"runs":[{"text":"Songs"}]},
                      "contents":[{"musicResponsiveListItemRenderer":${songFields("shelf_song4")}}]}}]}}
                ]}}}}]}
            """.trimIndent()),
            sectionList = null,
        )
        assertEquals(listOf("card_song_1", "inline_song2", "row_song_03", "shelf_song4"), summary.map { it.id })
        assertEquals(ContentKind.SONG, summary.first().kind)
        assertEquals("Card title", summary.first().title)
        assertEquals("https://example.com/card", summary.first().artworkUri)
    }

    @Test
    fun theSectionListIsReadFromAnyBrowseWrapper() {
        val contents = """{"contents":[{"musicCarouselShelfRenderer":{
            "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Shelf"}]}}},
            "contents":[${song("wrapped_001")}]}}]}"""
        val direct = youTubeSectionListRenderer(
            singleColumn = null,
            sectionList = obj(contents),
            twoColumn = null,
        )
        assertEquals("wrapped_001", youTubeHomeShelfPage(direct).shelves.single().entries.single().id)
        val singleColumn = youTubeSectionListRenderer(
            singleColumn = obj("""{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":$contents}}}]}"""),
            sectionList = null,
            twoColumn = null,
        )
        assertEquals("wrapped_001", youTubeHomeShelfPage(singleColumn).shelves.single().entries.single().id)
        val twoColumn = youTubeSectionListRenderer(
            singleColumn = null,
            sectionList = null,
            twoColumn = obj("""{"secondaryContents":{"sectionListRenderer":$contents}}"""),
        )
        assertEquals("wrapped_001", youTubeHomeShelfPage(twoColumn).shelves.single().entries.single().id)
    }

    @Test
    fun theSearchFilterParamsAreTheAndroidValuesAndTheUnverifiedFiltersRefuse() {
        assertEquals("EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D", youTubeSearchFilterParam(ContentSearchFilter.SONGS))
        assertEquals("EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D", youTubeSearchFilterParam(ContentSearchFilter.VIDEOS))
        assertEquals("EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D", youTubeSearchFilterParam(ContentSearchFilter.ALBUMS))
        assertEquals("EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D", youTubeSearchFilterParam(ContentSearchFilter.ARTISTS))
        assertEquals("EgeKAQQoADgBagwQDhAKEAMQBRAJEAQ%3D", youTubeSearchFilterParam(ContentSearchFilter.FEATURED_PLAYLISTS))
        assertEquals("EgeKAQQoAEABagoQAxAEEAoQCRAF", youTubeSearchFilterParam(ContentSearchFilter.COMMUNITY_PLAYLISTS))
        // ALL is the unfiltered summary request, and the three filters with no Android param stay refusals.
        for (filter in listOf(
            ContentSearchFilter.ALL,
            ContentSearchFilter.PLAYLISTS,
            ContentSearchFilter.PODCASTS,
            ContentSearchFilter.EPISODES,
        )) {
            assertNull(youTubeSearchFilterParam(filter), filter.name)
        }
    }

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    /** A playable song row: a playlist item id, the linked artist and album credits and a duration in the subtitle. */
    private fun song(videoId: String): String = """{"musicResponsiveListItemRenderer":${songFields(videoId)}}"""

    private fun songFields(videoId: String): String = """
        {
          "playlistItemData":{"videoId":"$videoId"},
          "flexColumns":[
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song title"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
              {"text":"Artist","navigationEndpoint":{"browseEndpoint":{"browseId":"UC-artist01"}}},
              {"text":" • "},
              {"text":"Album","navigationEndpoint":{"browseEndpoint":{"browseId":"MPRE-album01"}}},
              {"text":" • "},
              {"text":"3:45"}]}}}
          ],
          "badges":[{"musicInlineBadgeRenderer":{"iconType":"MUSIC_EXPLICIT_BADGE"}}],
          "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[
            {"url":"//example.com/small","width":60,"height":60},
            {"url":"//example.com/large","width":544,"height":544}]}}}
        }
    """

    /** A new-release album tile: the type, the artist credit and the year in the separator-split subtitle. */
    private fun album(browseId: String): String = """
        {"musicTwoRowItemRenderer":{
          "title":{"runs":[{"text":"Album title"}]},
          "subtitle":{"runs":[
            {"text":"Album"},
            {"text":" • "},
            {"text":"Artist","navigationEndpoint":{"browseEndpoint":{"browseId":"UC-artist01"}}},
            {"text":" • "},
            {"text":"2024"}]},
          "navigationEndpoint":{"browseEndpoint":{"browseId":"$browseId"}},
          "thumbnailRenderer":{"croppedSquareThumbnailRenderer":{"thumbnail":{"thumbnails":[
            {"url":"//example.com/album","width":200,"height":200}]}}}
        }}
    """
}
