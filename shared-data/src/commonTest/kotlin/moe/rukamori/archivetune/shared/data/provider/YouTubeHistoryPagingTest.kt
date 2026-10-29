package moe.rukamori.archivetune.shared.data.provider

import kotlin.test.*
import kotlinx.serialization.json.*
import moe.rukamori.archivetune.shared.domain.repository.ContentKind

/** Synthetic protocol fixtures; no account, network or playback-engine simulation. */
class YouTubeHistoryPagingTest {
    @Test fun initialDatedShelvesKeepTheListCursorAndIgnoreMenuCursors() {
        val response = obj("""{
            "contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{
                "sectionListRenderer":{"contents":[
                    {"musicShelfRenderer":{"title":{"runs":[{"text":"Today"}]},"contents":[${row("one")},${row("two")}],
                        "continuations":[{"nextContinuationData":{"continuation":"shelf-cursor"}}]}},
                    {"itemSectionRenderer":{"header":{"itemSectionHeaderRenderer":{"title":{"simpleText":"Yesterday"}}},
                        "contents":[${row("three")}]}}
                ],"continuations":[{"nextContinuationData":{"continuation":"list-cursor+/=?"}}]}
            }}}]}},
            "menu":{"continuations":[{"nextContinuationData":{"continuation":"never-use-menu"}}]}
        }""")
        val page = parse(response)
        assertEquals(listOf("Today", "Yesterday"), page.sections.map { it.title })
        assertEquals(listOf("one", "two", "three"), page.sections.flatMap { it.entries }.map { it.id })
        assertEquals("list-cursor+/=?", page.continuation)
    }

    @Test fun twoColumnHistoryDoesNotReadUnrelatedSecondaryRecommendations() {
        val page = parse(obj("""{"contents":{"twoColumnBrowseResultsRenderer":{
            "tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[${row("history")}]}}}}],
            "secondaryContents":{"sectionListRenderer":{"contents":[${row("recommendation")}]}}
        }}}"""))
        assertEquals(listOf("history"), page.sections.single().entries.map { it.id })
    }

    @Test fun allListAndShelfContinuationShapesKeepBareRowsAndTheNextToken() {
        for (name in listOf("sectionListContinuation", "itemSectionContinuation", "musicShelfContinuation", "musicPlaylistShelfContinuation")) {
            val page = parse(obj("""{"continuationContents":{"$name":{
                "contents":[${row("episode")}],"continuations":[{"nextContinuationData":{"continuation":"next-page"}}]
            }}}"""))
            assertEquals("", page.sections.single().title)
            assertEquals("episode", page.sections.single().entries.single().id)
            assertEquals("next-page", page.continuation, name)
        }
    }

    @Test fun appendAndReloadActionShapesReadOnlyTheContinuationItems() {
        for (envelope in listOf("onResponseReceivedActions", "onResponseReceivedEndpoints")) {
            for (action in listOf("appendContinuationItemsAction", "reloadContinuationItemsCommand")) {
                val page = parse(obj("""{"$envelope":[{"$action":{"continuationItems":[
                    ${row("next")},{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"tail"}}}}
                ]}}]}"""))
                assertEquals("next", page.sections.single().entries.single().id)
                assertEquals("tail", page.continuation)
            }
        }
    }

    @Test fun recognizedEmptyListIsTerminalButAnErrorOrMenuOnlyResponseIsNotEmptySuccess() {
        val empty = parse(obj("""{"contents":{"sectionListRenderer":{"contents":[]}}}"""))
        assertTrue(empty.sections.isEmpty())
        assertNull(empty.continuation)
        for (payload in listOf("""{"error":{"code":403}}""",
            """{"menu":{"continuationEndpoint":{"continuationCommand":{"token":"unrelated"}}}}""")) {
            assertFailsWith<IllegalArgumentException> { parse(obj(payload)) }
        }
    }

    @Test fun dateOverlapIsDeduplicatedButTheSameEpisodeOnAnotherDateSurvives() {
        val pages = HistoryPageAccumulator<String> { it }
        pages.replace(HistoryBrowsePage(listOf(HistoryBrowseSection("Today", listOf("one", "one", "two"))), "page-1"))
        pages.append("page-1", HistoryBrowsePage(listOf(
            HistoryBrowseSection("", listOf("two", "three")),
            HistoryBrowseSection("Yesterday", listOf("one")),
        ), "page-2"))
        assertEquals(listOf("Today", "Yesterday"), pages.page.sections.map { it.title })
        assertEquals(listOf("one", "two", "three"), pages.page.sections.first().entries)
        assertEquals(listOf("one"), pages.page.sections.last().entries)
        pages.append("page-2", HistoryBrowsePage(listOf(HistoryBrowseSection("", listOf("four")))))
        assertEquals(listOf("one", "four"), pages.page.sections.last().entries)
        assertNull(pages.page.continuation)
    }

    @Test fun foreignAndCyclicTokensFailBeforeMutatingTheDisplayedPage() {
        val pages = HistoryPageAccumulator<String> { it }
        pages.replace(HistoryBrowsePage(listOf(HistoryBrowseSection("Today", listOf("one"))), "page-1"))
        val first = pages.page
        assertFailsWith<IllegalArgumentException> { pages.append("foreign", HistoryBrowsePage(emptyList())) }
        assertFailsWith<IllegalArgumentException> { pages.append("page-1", HistoryBrowsePage(emptyList(), "page-1")) }
        assertEquals(first, pages.page)
        pages.append("page-1", HistoryBrowsePage(emptyList(), "page-2"))
        val second = pages.page
        assertFailsWith<IllegalArgumentException> { pages.append("page-2", HistoryBrowsePage(emptyList(), "page-1")) }
        assertEquals(second, pages.page)
        pages.append("page-2", HistoryBrowsePage(emptyList()))
        assertNull(pages.page.continuation)
    }

    @Test fun aRouteLimitedFirstPageCannotBeReportedAsACompleteRepositoryPage() {
        val page: YouTubeHistoryPage = HistoryBrowsePage(
            listOf(HistoryBrowseSection("Today", listOf(YouTubeEntry(ContentKind.EPISODE, "one", "Episode")))),
            paginationUnavailable = true,
        )
        assertFailsWith<YouTubeHistoryRoutingUnavailable> { page.entriesPage() }
    }

    @Test fun responsiveEpisodeKeepsMetadataSetIdentityAndPodcastMarker() {
        val episode = assertNotNull(historyEntry(obj("""{"musicResponsiveListItemRenderer":{
            "playlistItemData":{"videoId":"episode_001","playlistSetVideoId":"set-episode"},
            "navigationEndpoint":{"watchEndpoint":{"videoId":"episode_001",
                "watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{"musicVideoType":"MUSIC_VIDEO_TYPE_PODCAST_EPISODE"}}}},
            "flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Episode title"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Show","navigationEndpoint":{"browseEndpoint":{"browseId":"MPSP-show"}}}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"10 views"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Album","navigationEndpoint":{"browseEndpoint":{"browseId":"MPRE-album"}}}]}}}
            ],
            "fixedColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"1:02:03"}]}}}],
            "badges":[{"musicInlineBadgeRenderer":{"icon":{"iconType":"MUSIC_EXPLICIT_BADGE"}}}],
            "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://example.com/art"}]}}}
        }}""")))
        assertEquals(ContentKind.EPISODE, episode.kind)
        assertEquals("set-episode", episode.setVideoId)
        assertEquals(3_723, episode.durationSeconds)
        assertEquals("MPSP-show", episode.artists.single().id)
        assertEquals("MPRE-album", episode.albumId)
        assertEquals("https://example.com/art", episode.artworkUri)
        assertTrue(episode.explicit)
    }

    @Test fun multirowEpisodeUsesTheDirectOnTapEndpointAndPlaybackDuration() {
        val episode = assertNotNull(historyEntry(obj("""{"musicMultiRowListItemRenderer":{
            "title":{"runs":[{"text":"Episode","navigationEndpoint":{"browseEndpoint":{"browseId":"MPED-episode"}}}]},
            "subtitle":{"runs":[{"text":"Show","navigationEndpoint":{"browseEndpoint":{"browseId":"MPSP-show"}}}]},
            "onTap":{"watchEndpoint":{"videoId":"episode_001","playlistSetVideoId":"set-1"}},
            "playbackProgress":{"musicPlaybackProgressRenderer":{"durationText":{"runs":[{"text":"96:12"}]}}}
        }}""")))
        assertEquals(ContentKind.EPISODE, episode.kind)
        assertEquals("episode_001", episode.id)
        assertEquals("set-1", episode.setVideoId)
        assertEquals(5_772, episode.durationSeconds)
        assertEquals("Show", episode.subtitle)
    }

    @Test fun unknownAndMalformedDurationsStayUnknownAndClockLikeTitlesAreNotDurations() {
        for (duration in listOf("1:90", "2147483648:00", "-1:00", "2h", "1:02:03:04")) {
            val cell = obj("""{"musicResponsiveListItemRenderer":{
                "playlistItemData":{"videoId":"song"},
                "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"12:30"}]}}}],
                "fixedColumns":[{"musicResponsiveListItemFixedColumnRenderer":{"text":{"simpleText":"$duration"}}}]
            }}""")
            assertNull(assertNotNull(historyEntry(cell)).durationSeconds, duration)
        }
        assertNull(assertNotNull(historyEntry(row("12:30"))).durationSeconds)
    }

    private fun parse(response: JsonObject) = parseYouTubeHistoryPage(response, ::historyEntry)
    private fun obj(json: String) = Json.parseToJsonElement(json).jsonObject
    private fun row(id: String) = obj("""{"musicResponsiveListItemRenderer":{
        "playlistItemData":{"videoId":"$id"},
        "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$id"}]}}}]
    }}""")
}
