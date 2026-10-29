package moe.rukamori.archivetune.shared.ui.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.SponsorBlockApiUrlKey
import moe.rukamori.archivetune.shared.domain.settings.SponsorBlockCategoriesKey
import moe.rukamori.archivetune.shared.domain.settings.SponsorBlockEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.SponsorBlockPodcastsEnabledKey

class PodcastSponsorBlockPolicyTest {
    private val episode = ContentRef(ContentProvider.YOUTUBE.id, ContentKind.EPISODE, "abcdefghijk")

    @Test fun currentSnapshotMustKeepBothOptInsOn() {
        assertNull(podcastSponsorBlockConfig(episode, null))
        assertNull(podcastSponsorBlockConfig(episode, PreferenceSnapshot.EMPTY))
        for (enabled in listOf(false, true)) for (podcasts in listOf(false, true)) {
            val config = podcastSponsorBlockConfig(episode, preferences(enabled, podcasts))
            assertEquals(enabled && podcasts, config != null)
        }
        assertNotNull(podcastSponsorBlockConfig(episode, preferences()))
        assertNull(podcastSponsorBlockConfig(episode, preferences(podcasts = false)))
        assertNull(podcastSponsorBlockConfig(episode, preferences(enabled = false)))
    }

    @Test fun categoriesAndOriginChangesProduceTheCurrentRequestConfiguration() {
        val defaults = assertNotNull(podcastSponsorBlockConfig(episode, preferences()))
        assertEquals(setOf("music_offtopic"), defaults.categories)
        val edited = preferences().toEditor().apply {
            this[SponsorBlockCategoriesKey] = setOf("sponsor")
            this[SponsorBlockApiUrlKey] = "https://segments.example.test/"
        }.snapshot()
        val config = assertNotNull(podcastSponsorBlockConfig(episode, edited))
        assertEquals(setOf("sponsor"), config.categories)
        assertEquals("https://segments.example.test", config.origin)
        val off = edited.toEditor().apply { this[SponsorBlockPodcastsEnabledKey] = false }.snapshot()
        assertNull(podcastSponsorBlockConfig(episode, off))
    }

    @Test fun localOtherProviderAndSongRowsNeverEnterThePortablePodcastLookup() {
        assertNull(podcastSponsorBlockConfig(episode.copy(provider = ContentProvider.LOCAL.id), preferences()))
        assertNull(podcastSponsorBlockConfig(episode.copy(provider = ContentProvider.SPOTIFY_CATALOG.id), preferences()))
        assertNull(podcastSponsorBlockConfig(episode.copy(kind = ContentKind.SONG), preferences()))
        assertNull(podcastSponsorBlockConfig(episode.copy(id = "not-a-video-id"), preferences()))
    }

    @Test fun emptyCategoriesAndInvalidPersistedOriginsRefuseLookup() {
        val noCategories = preferences().toEditor().apply { this[SponsorBlockCategoriesKey] = emptySet() }.snapshot()
        assertNull(podcastSponsorBlockConfig(episode, noCategories))
        for (origin in listOf(
            "http://segments.example.test", "https:", "https:/", "https://", "https:///", "https://:443",
            "/", "//", "https://segments.example.test/api", "https://segments.example.test//",
            "https://segments.example.test?q=x", "https://segments.example.test#fragment",
        )) {
            val invalid = preferences().toEditor().apply { this[SponsorBlockApiUrlKey] = origin }.snapshot()
            assertNull(podcastSponsorBlockConfig(episode, invalid), origin)
        }
    }

    @Test fun blankOriginsKeepThePublicDefaultAndValidCustomOriginsNormalizeOnlyTheRootSlash() {
        val defaults = assertNotNull(podcastSponsorBlockConfig(episode, preferences()))
        for (origin in listOf("", " ", "\t")) {
            val blank = preferences().toEditor().apply { this[SponsorBlockApiUrlKey] = origin }.snapshot()
            assertEquals(defaults, podcastSponsorBlockConfig(episode, blank))
        }
        for (origin in listOf("https://segments.example.test:8443", "https://[2001:db8::1]:8443")) {
            val custom = preferences().toEditor().apply { this[SponsorBlockApiUrlKey] = " $origin/ " }.snapshot()
            assertEquals(origin, assertNotNull(podcastSponsorBlockConfig(episode, custom)).origin)
        }
    }

    private fun preferences(enabled: Boolean = true, podcasts: Boolean = true) = PreferenceSnapshot.of(
        SponsorBlockEnabledKey to enabled,
        SponsorBlockPodcastsEnabledKey to podcasts,
    )
}
