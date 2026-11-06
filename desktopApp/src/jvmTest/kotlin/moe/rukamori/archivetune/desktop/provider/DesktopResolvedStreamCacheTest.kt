package moe.rukamori.archivetune.desktop.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.domain.source.DirectStream

class DesktopResolvedStreamCacheTest {
    private var now = 1_000_000L

    private fun cache(capacity: Int = 8, expiryMarginMs: Long = 60_000L, unknownTtlMs: Long = 600_000L) =
        DesktopResolvedStreamCache(
            capacity = capacity,
            nowMs = { now },
            expiryMarginMs = expiryMarginMs,
            unknownExpiryTtlMs = unknownTtlMs,
        )

    private fun stream(expiresAtEpochMs: Long?, uri: String = "https://cdn.example/audio"): PreparedAudio.Progressive =
        PreparedAudio.Progressive(
            DirectStream(
                uri = uri,
                mimeType = "audio/webm",
                codecs = "opus",
                contentLength = 1_000L,
                label = "YouTube test",
                source = AudioSourceType.YOUTUBE,
                expiresAtEpochMs = expiresAtEpochMs,
            ),
        )

    private fun key(videoId: String = "dQw4w9WgXcQ", quality: String = "AUTO", session: String = "anonymous", network: String = "direct") =
        DesktopResolvedStreamCache.Key(videoId, quality, session, network)

    @Test
    fun aStreamIsReusedUntilItsWindowClosesWithTheEngineMargin() {
        val cache = cache(expiryMarginMs = 60_000L)
        val audio = stream(expiresAtEpochMs = now + 600_000L)
        cache.put(key(), audio)

        assertSame(audio, cache.get(key()))

        // Still inside the window, but no longer a minute of headroom: the entry must not be handed out again.
        now += 600_000L - 60_000L
        assertNull(cache.get(key()))
        // And the expired entry is dropped rather than left to be re-checked.
        assertEquals(0, cache.size())
    }

    @Test
    fun aStreamWithoutAReportedWindowExpiresOnItsOwnClock() {
        val cache = cache(unknownTtlMs = 600_000L)
        val audio = stream(expiresAtEpochMs = null)
        cache.put(key(), audio)

        assertNotNull(cache.get(key()))
        now += 599_999L
        assertNotNull(cache.get(key()))
        now += 1L
        assertNull(cache.get(key()))
    }

    @Test
    fun anAlreadyExpiredStreamIsNeverStored() {
        val cache = cache(expiryMarginMs = 60_000L)
        cache.put(key(), stream(expiresAtEpochMs = now + 1_000L))
        assertEquals(0, cache.size())
        assertNull(cache.get(key()))
    }

    @Test
    fun everyKeyComponentSeparatesEntries() {
        val cache = cache(expiryMarginMs = 0L)
        val audio = stream(expiresAtEpochMs = now + 600_000L)
        cache.put(key(), audio)

        assertSame(audio, cache.get(key()))
        assertNull(cache.get(key(quality = "HIGH")))
        assertNull(cache.get(key(session = "session:abc")))
        assertNull(cache.get(key(network = "HTTP:proxy.example:8080")))
        assertNull(cache.get(key(videoId = "aaaaaaaaaaa")))
    }

    @Test
    fun invalidateDropsEveryQualityOfTheVideo() {
        val cache = cache()
        cache.put(key(quality = "AUTO"), stream(expiresAtEpochMs = now + 600_000L))
        cache.put(key(quality = "HIGH"), stream(expiresAtEpochMs = now + 600_000L))
        cache.put(key(videoId = "aaaaaaaaaaa"), stream(expiresAtEpochMs = now + 600_000L))

        cache.invalidate("dQw4w9WgXcQ")

        assertNull(cache.get(key(quality = "AUTO")))
        assertNull(cache.get(key(quality = "HIGH")))
        assertNotNull(cache.get(key(videoId = "aaaaaaaaaaa")))
    }

    @Test
    fun theLeastRecentlyUsedEntryIsTheOneDropped() {
        val cache = cache(capacity = 2)
        cache.put(key(videoId = "aaaaaaaaaaa"), stream(expiresAtEpochMs = now + 600_000L))
        cache.put(key(videoId = "bbbbbbbbbbb"), stream(expiresAtEpochMs = now + 600_000L))
        // Touch the first so the second becomes the least recently used.
        assertNotNull(cache.get(key(videoId = "aaaaaaaaaaa")))

        cache.put(key(videoId = "ccccccccccc"), stream(expiresAtEpochMs = now + 600_000L))

        assertNotNull(cache.get(key(videoId = "aaaaaaaaaaa")))
        assertNull(cache.get(key(videoId = "bbbbbbbbbbb")))
        assertNotNull(cache.get(key(videoId = "ccccccccccc")))
    }

    @Test
    fun concurrentCallersOfOneKeyShareASingleResolve() = runTest {
        val cache = cache()
        var loads = 0
        val audio = stream(expiresAtEpochMs = now + 600_000L)

        val results = (1..8).map {
            async {
                cache.resolve(key()) {
                    loads++
                    // A real resolution suspends; yielding keeps every caller inside the flight.
                    kotlinx.coroutines.yield()
                    audio
                }
            }
        }.awaitAll()

        assertEquals(1, loads)
        assertTrue(results.all { it === audio })
    }

    @Test
    fun aFailedResolveCachesNothing() = runTest {
        val cache = cache()
        var loads = 0
        val answer = cache.resolve(key()) {
            loads++
            null
        }
        assertNull(answer)
        assertEquals(1, loads)

        // The next call resolves again: a null answer is never remembered.
        assertNull(cache.resolve(key()) { loads++; null })
        assertEquals(2, loads)
    }
}
