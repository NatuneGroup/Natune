package moe.rukamori.archivetune.playback

import moe.rukamori.archivetune.constants.AudioSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalStreamRecoveryTest {
    @Test
    fun rejectedUrlInvalidatesBothCacheLayersBeforeOneFreshAttemptThenFallsThrough() {
        val recovery = ExternalStreamRecovery()
        val invalidations = mutableListOf<String>()
        val invalidate = { invalidations += "fast"; invalidations += "provider" }
        assertTrue(recovery.onHttpFailure("song", AudioSourceType.TIDAL, 403, invalidate))
        assertEquals(listOf("fast", "provider"), invalidations)
        assertEquals(AudioSourceType.TIDAL, recovery.refreshSource("song"))
        assertTrue(recovery.rejectedSources("song").isEmpty())
        recovery.resolved("song")
        assertTrue(recovery.onHttpFailure("song", AudioSourceType.TIDAL, 403, invalidate))
        assertEquals(listOf("fast", "provider", "fast", "provider"), invalidations)
        assertNull(recovery.refreshSource("song"))
        assertEquals(setOf(AudioSourceType.TIDAL), recovery.rejectedSources("song"))
        assertFalse(recovery.onHttpFailure("song", AudioSourceType.TIDAL, 403, invalidate))
        assertEquals(6, invalidations.size)
    }

    @Test
    fun allAuthorizationAndRangeRejectionsShareTheSameBoundedPolicy() {
        for (status in listOf(401, 403, 404, 410, 416)) {
            val recovery = ExternalStreamRecovery()
            var invalidated = false
            assertTrue(recovery.onHttpFailure("song", AudioSourceType.QOBUZ, status) { invalidated = true })
            assertTrue(invalidated)
            assertEquals(AudioSourceType.QOBUZ, recovery.refreshSource("song"))
        }
    }

    @Test
    fun youtubeUnknownSourcesAndOtherStatusesDoNotEnterExternalRecovery() {
        val recovery = ExternalStreamRecovery()
        val unexpected = { error("must retain its existing recovery policy") }
        assertFalse(recovery.onHttpFailure("song", AudioSourceType.YOUTUBE, 403, unexpected))
        assertFalse(recovery.onHttpFailure("song", null, 403, unexpected))
        assertFalse(recovery.onHttpFailure("song", AudioSourceType.DEEZER, 500, unexpected))
        assertFalse(recovery.onHttpFailure("song", AudioSourceType.DEEZER, 429, unexpected))
        assertNull(recovery.refreshSource("song"))
    }

    @Test
    fun failedRefreshRejectsTheSourceAndManualRetryGetsANewBudget() {
        val recovery = ExternalStreamRecovery()
        recovery.onHttpFailure("song", AudioSourceType.SPOTIFY, 410) {}
        recovery.reject("song", AudioSourceType.SPOTIFY)
        assertNull(recovery.refreshSource("song"))
        assertEquals(setOf(AudioSourceType.SPOTIFY), recovery.rejectedSources("song"))
        recovery.clear("song")
        recovery.onHttpFailure("song", AudioSourceType.SPOTIFY, 410) {}
        assertEquals(AudioSourceType.SPOTIFY, recovery.refreshSource("song"))
    }
}
