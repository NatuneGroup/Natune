package moe.rukamori.archivetune.shared.domain.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class StreamUrlExpiryTest {
    @Test
    fun amzWindowIsAbsoluteAndAnElapsedWindowIsNeverExtended() {
        val issued = Instant.parse("2026-01-01T00:00:00Z").toEpochMilliseconds()
        val expiry = StreamUrlExpiry.fromUrl("https://cdn.invalid/audio?X-Amz-Date=20260101T000000Z&X-Amz-Expires=60")
        assertEquals(issued + 60_000L, expiry)
        val now = issued + 120_000L
        assertEquals(issued + 30_000L, StreamUrlExpiry.cacheDeadline(now, 300_000L, expiry))
        assertTrue(StreamUrlExpiry.cacheDeadline(now, 300_000L, expiry) < now)
    }

    @Test
    fun fastCacheUsesTheEarlierOfFiveMinutesAndTheGuardedWindow() {
        val now = 1_000_000L
        assertEquals(now + 300_000L, StreamUrlExpiry.cacheDeadline(now, 300_000L, null))
        assertEquals(now + 300_000L, StreamUrlExpiry.cacheDeadline(now, 300_000L, now + 900_000L))
        assertEquals(now + 45_000L, StreamUrlExpiry.cacheDeadline(now, 300_000L, now + 75_000L))
        assertEquals(now - 20_000L, StreamUrlExpiry.cacheDeadline(now, 300_000L, now + 10_000L))
    }

    @Test
    fun qobuzEtspAndSignedCdnTokensAreUnixSeconds() {
        val expected = 1_800_000_000_000L
        assertEquals(expected, StreamUrlExpiry.fromUrl("https://cdn.invalid/audio?etsp=1800000000"))
        for (name in listOf("hdnts", "hdntl", "__token__")) {
            assertEquals(expected, StreamUrlExpiry.fromUrl("https://cdn.invalid/audio?$name=exp=1800000000~acl=/*~hmac=fixture"))
            assertEquals(expected, StreamUrlExpiry.fromUrl("https://cdn.invalid/audio?$name=exp%3D1800000000%7Eacl%3D%2F*"))
        }
    }

    @Test
    fun cloudfrontExpiryRequiresItsKnownSignatureEnvelope() {
        assertNull(StreamUrlExpiry.fromUrl("https://cdn.invalid/audio?Expires=1800000000"))
        assertEquals(1_800_000_000_000L, StreamUrlExpiry.fromUrl(
            "https://cdn.invalid/audio?Expires=1800000000&Signature=fixture&Key-Pair-Id=fixture",
        ))
    }

    @Test
    fun unknownAndMalformedTtlsRemainUnknownRatherThanGuessed() {
        for (query in listOf(
            "", "ttl=60", "exp=1800000000", "expires=60", "etsp=not-a-number",
            "etsp=1800000000&etsp=1900000000", "etsp=-1", "etsp=9223372036854775807",
            "X-Amz-Date=20260230T000000Z&X-Amz-Expires=60",
            "X-Amz-Date=20260101T000000Z&X-Amz-Expires=-1",
            "X-Amz-Date=20260101T000000Z&X-Amz-Expires=604801",
            "X-Amz-Expires=60", "__token__=exp=invalid", "hdnts=exp=1800000000~exp=1900000000",
        )) {
            assertNull(StreamUrlExpiry.fromUrl("https://cdn.invalid/audio?$query"), query)
        }
    }

    @Test
    fun deadlineArithmeticSaturatesInsteadOfWrapping() {
        assertEquals(Long.MAX_VALUE, StreamUrlExpiry.cacheDeadline(Long.MAX_VALUE - 5, 10, null))
        assertEquals(Long.MIN_VALUE, StreamUrlExpiry.cacheDeadline(0, 10, Long.MIN_VALUE))
    }
}
