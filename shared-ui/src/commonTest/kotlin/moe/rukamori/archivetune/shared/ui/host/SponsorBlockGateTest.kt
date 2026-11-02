package moe.rukamori.archivetune.shared.ui.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import moe.rukamori.archivetune.shared.data.provider.SkipSegment
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.SponsorBlockApiUrlKey
import moe.rukamori.archivetune.shared.domain.settings.SponsorBlockCategoriesKey
import moe.rukamori.archivetune.shared.domain.settings.SponsorBlockEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.SponsorBlockPodcastsEnabledKey

class SponsorBlockGateTest {
    private val episode = ContentRef(ContentProvider.YOUTUBE.id, ContentKind.EPISODE, "abcdefghijk")
    private val song = ContentRef(ContentProvider.YOUTUBE.id, ContentKind.SONG, "abcdefghijk")

    @Test fun songsNeedOnlyTheMainOptIn() {
        assertNotNull(sponsorBlockConfig(song, preferences()))
        assertNotNull(sponsorBlockConfig(song, preferences(podcasts = false)))
        assertNull(sponsorBlockConfig(song, preferences(enabled = false)))
        assertNull(sponsorBlockConfig(song, preferences(enabled = false, podcasts = true)))
        assertNull(sponsorBlockConfig(song, null))
        assertNull(sponsorBlockConfig(song, PreferenceSnapshot.EMPTY))
    }

    @Test fun episodesKeepBothOptIns() {
        assertNull(sponsorBlockConfig(episode, null))
        assertNull(sponsorBlockConfig(episode, PreferenceSnapshot.EMPTY))
        for (enabled in listOf(false, true)) for (podcasts in listOf(false, true)) {
            val config = sponsorBlockConfig(episode, preferences(enabled, podcasts))
            assertEquals(enabled && podcasts, config != null)
        }
        assertNotNull(sponsorBlockConfig(episode, preferences()))
        assertNull(sponsorBlockConfig(episode, preferences(podcasts = false)))
        assertNull(sponsorBlockConfig(episode, preferences(enabled = false)))
    }

    @Test fun localOtherProviderAndNonPlayableKindsNeverEnterTheLookup() {
        assertNull(sponsorBlockConfig(song.copy(provider = ContentProvider.LOCAL.id), preferences()))
        assertNull(sponsorBlockConfig(song.copy(provider = ContentProvider.SPOTIFY_CATALOG.id), preferences()))
        assertNull(sponsorBlockConfig(song.copy(provider = ContentProvider.TIDAL.id), preferences()))
        assertNull(sponsorBlockConfig(song.copy(id = "not-a-video-id"), preferences()))
        for (kind in listOf(ContentKind.ALBUM, ContentKind.ARTIST, ContentKind.PLAYLIST, ContentKind.PODCAST)) {
            assertNull(sponsorBlockConfig(ContentRef(ContentProvider.YOUTUBE.id, kind, "abcdefghijk"), preferences()), kind.name)
        }
    }

    @Test fun categoriesAndOriginChangesProduceTheCurrentRequestConfiguration() {
        val defaults = assertNotNull(sponsorBlockConfig(song, preferences()))
        assertEquals(setOf("music_offtopic"), defaults.categories)
        val edited = preferences().toEditor().apply {
            this[SponsorBlockCategoriesKey] = setOf("sponsor")
            this[SponsorBlockApiUrlKey] = "https://segments.example.test/"
        }.snapshot()
        val config = assertNotNull(sponsorBlockConfig(song, edited))
        assertEquals(setOf("sponsor"), config.categories)
        assertEquals("https://segments.example.test", config.origin)
        val off = edited.toEditor().apply { this[SponsorBlockEnabledKey] = false }.snapshot()
        assertNull(sponsorBlockConfig(song, off))
    }

    @Test fun emptyCategoriesAndInvalidPersistedOriginsRefuseLookup() {
        val noCategories = preferences().toEditor().apply { this[SponsorBlockCategoriesKey] = emptySet() }.snapshot()
        assertNull(sponsorBlockConfig(song, noCategories))
        for (origin in listOf(
            "http://segments.example.test", "https:", "https:/", "https://", "https:///", "https://:443",
            "/", "//", "https://segments.example.test/api", "https://segments.example.test//",
            "https://segments.example.test?q=x", "https://segments.example.test#fragment",
        )) {
            val invalid = preferences().toEditor().apply { this[SponsorBlockApiUrlKey] = origin }.snapshot()
            assertNull(sponsorBlockConfig(song, invalid), origin)
        }
    }

    @Test fun blankOriginsKeepThePublicDefaultAndValidCustomOriginsNormalizeOnlyTheRootSlash() {
        val defaults = assertNotNull(sponsorBlockConfig(song, preferences()))
        for (origin in listOf("", " ", "\t")) {
            val blank = preferences().toEditor().apply { this[SponsorBlockApiUrlKey] = origin }.snapshot()
            assertEquals(defaults, sponsorBlockConfig(song, blank))
        }
        for (origin in listOf("https://segments.example.test:8443", "https://[2001:db8::1]:8443")) {
            val custom = preferences().toEditor().apply { this[SponsorBlockApiUrlKey] = " $origin/ " }.snapshot()
            assertEquals(origin, assertNotNull(sponsorBlockConfig(song, custom)).origin)
        }
    }

    @Test fun aPositionInsideASegmentSeeksToItsEnd() {
        val segments = listOf(SkipSegment("sponsor", 30_000L, 45_000L))
        assertEquals(segments[0], skippableSegment(segments, 30_000L, 180_000L))
        assertEquals(segments[0], skippableSegment(segments, 44_000L, 180_000L))
    }

    @Test fun positionsOutsideOrInsideTheTailDoNotSkip() {
        val segments = listOf(SkipSegment("sponsor", 30_000L, 45_000L))
        assertNull(skippableSegment(segments, 29_999L, 180_000L))
        assertNull(skippableSegment(segments, 45_000L, 180_000L))
        // Inside the last SKIP_TAIL_MS the seek is not worth it and may overshoot.
        assertNull(skippableSegment(segments, 44_800L, 180_000L))
        assertNull(skippableSegment(segments, 44_701L, 180_000L))
    }

    @Test fun aSegmentRunningToTheEndPlaysOut() {
        val segments = listOf(SkipSegment("outro", 170_000L, 180_000L))
        assertNull(skippableSegment(segments, 172_000L, 180_000L))
        assertNull(skippableSegment(segments, 170_000L, 180_000L))
        // An unknown duration cannot prove the tail, so the same segment still skips.
        assertEquals(segments[0], skippableSegment(segments, 172_000L, null))
    }

    @Test fun theFirstInsideSegmentWins() {
        val segments = listOf(
            SkipSegment("sponsor", 30_000L, 45_000L),
            SkipSegment("selfpromo", 40_000L, 60_000L),
            SkipSegment("intro", 5_000L, 10_000L),
        )
        assertEquals(segments[0], skippableSegment(segments, 41_000L, 180_000L))
        assertEquals(segments[2], skippableSegment(segments, 7_000L, 180_000L))
        assertNull(skippableSegment(emptyList(), 7_000L, 180_000L))
    }

    private fun preferences(enabled: Boolean = true, podcasts: Boolean = true) = PreferenceSnapshot.of(
        SponsorBlockEnabledKey to enabled,
        SponsorBlockPodcastsEnabledKey to podcasts,
    )
}
