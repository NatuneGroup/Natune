package app.natune.shared.data.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Android and KMP both poll hifi-api queued playback through these rules, so the HTTP-date path is
// covered here rather than through java.time.
class TidalQueuedPlaybackTest {
    private val now = 1_795_219_200_000L // Sat, 21 Nov 2026 00:00:00 GMT

    @Test
    fun retryAfterAcceptsDeltaSecondsAndImfFixdate() {
        assertEquals(5_000L, TidalQueuedPlayback.retryAfterDelayMs("5", now))
        assertEquals(10_000L, TidalQueuedPlayback.retryAfterDelayMs("Sat, 21 Nov 2026 00:00:10 GMT", now))
        assertEquals(0L, TidalQueuedPlayback.retryAfterDelayMs("Fri, 20 Nov 2026 23:59:00 GMT", now))
        assertEquals(12 * 3_600_000L, TidalQueuedPlayback.retryAfterDelayMs("Tue, 29 Feb 2000 12:00:00 GMT", 951_782_400_000L))
    }

    @Test
    fun retryAfterTreatsOtherFormsAsAbsent() {
        assertNull(TidalQueuedPlayback.retryAfterDelayMs(null, now))
        assertNull(TidalQueuedPlayback.retryAfterDelayMs("  ", now))
        assertNull(TidalQueuedPlayback.retryAfterDelayMs("soon", now))
        assertNull(TidalQueuedPlayback.retryAfterDelayMs("Sat, 21 Nov 2026 00:00:10 +0000", now))
        assertNull(TidalQueuedPlayback.retryAfterDelayMs("Sat, 21 Foo 2026 00:00:10 GMT", now))
    }

    @Test
    fun pollDelayFallsBackAndStaysInBounds() {
        assertEquals(TidalQueuedPlayback.DEFAULT_POLL_INTERVAL_MS, TidalQueuedPlayback.pollDelayMs(null, now))
        assertEquals(TidalQueuedPlayback.MIN_POLL_INTERVAL_MS, TidalQueuedPlayback.pollDelayMs("0", now))
        assertEquals(TidalQueuedPlayback.MAX_POLL_INTERVAL_MS, TidalQueuedPlayback.pollDelayMs("3600", now))
    }

    @Test
    fun requestUrlStaysOnTheInstance() {
        val base = "https://hifi.example"
        assertEquals("$base/playback/requests/abc_1", TidalQueuedPlayback.requestUrl(base, "/playback/requests/abc_1"))
        assertEquals("$base/playback/requests/abc", TidalQueuedPlayback.requestUrl(base, "$base/playback/requests/abc"))
        assertNull(TidalQueuedPlayback.requestUrl(base, "https://other.example/playback/requests/abc"))
        assertNull(TidalQueuedPlayback.requestUrl(base, "/playback/requests/../admin"))
        assertNull(TidalQueuedPlayback.requestUrl(base, "/other/abc"))
    }

    @Test
    fun failedStatesStopPolling() {
        assertTrue(TidalQueuedPlayback.isFailedState("Cancelled"))
        assertFalse(TidalQueuedPlayback.isFailedState("queued"))
        assertFalse(TidalQueuedPlayback.isFailedState(null))
    }
}
