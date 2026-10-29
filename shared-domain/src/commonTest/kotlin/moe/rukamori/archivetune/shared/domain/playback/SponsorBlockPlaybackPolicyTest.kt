package moe.rukamori.archivetune.shared.domain.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SponsorBlockPlaybackPolicyTest {
    @Test fun podcastsRequireBothExplicitOptIns() {
        for (global in listOf(false, true)) for (podcasts in listOf(false, true)) {
            val allowed = allows(global, podcasts)
            if (global && podcasts) assertTrue(allowed) else assertFalse(allowed)
        }
    }

    @Test fun localEpisodesAndOtherProvidersAreNeverLookedUp() {
        assertFalse(allows(enabled = true, podcasts = true, local = true))
        assertFalse(allows(enabled = true, podcasts = true, youtube = false))
    }

    @Test fun songSettingMeaningDoesNotDependOnPodcastOptIn() {
        assertTrue(allows(enabled = true, podcasts = false, podcast = false))
        assertFalse(allows(enabled = false, podcasts = true, podcast = false))
    }

    @Test fun localUrisAndMalformedIdsAreRejected() {
        for (id in listOf("", "local:episode", "content://episodes/1", "file:///episode.mp3", "short")) {
            assertFalse(SponsorBlockPlaybackPolicy.allowsLookup(id, true, true, true, false))
        }
    }

    private fun allows(enabled: Boolean, podcasts: Boolean, local: Boolean = false, youtube: Boolean = true, podcast: Boolean = true) =
        SponsorBlockPlaybackPolicy.allowsLookup("episode_001", enabled, podcasts, podcast, local, youtube)
}
