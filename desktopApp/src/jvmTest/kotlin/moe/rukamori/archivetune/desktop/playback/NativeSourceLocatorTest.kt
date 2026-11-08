package moe.rukamori.archivetune.desktop.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.playback.PlaybackHeaders
import moe.rukamori.archivetune.shared.domain.playback.PlaybackLocator

class NativeSourceLocatorTest {
    @Test
    fun everyRemoteLocatorGoesThroughTheHostLease() {
        val locator = PlaybackLocator.Remote("https://audio.invalid/song")
        val source = assertIs<EngineSource.Leased>(engineSourceOpen(locator, null))
        source.discard()
    }

    @Test
    fun aHeaderFreeManifestKeepsTheEngineReaderThatCanFollowItsChildren() {
        for (url in listOf(
            "https://audio.invalid/hls/index.m3u8?token=1",
            "https://audio.invalid/dash/manifest.mpd",
            "https://manifest.googlevideo.com/api/manifest/hls_playlist/id/abc",
        )) {
            val locator = PlaybackLocator.Remote(url)
            assertEquals(url, assertIs<EngineSource.Direct>(engineSourceOpen(locator, null)).source)
        }
    }

    @Test
    fun aHeaderBearingManifestNeverOpensWithoutItsHeaders() {
        val locator = PlaybackLocator.Remote(
            "https://audio.invalid/hls/index.m3u8",
            headers = PlaybackHeaders.of(mapOf("Authorization" to "fixture")),
        )
        val source = assertIs<EngineSource.Leased>(engineSourceOpen(locator, null))
        source.discard()
    }

    @Test
    fun headerFreeKnownExpiryCannotBypassTheLease() {
        val locator = PlaybackLocator.Remote("https://audio.invalid/song", expiresAtEpochMs = 1L)
        val source = assertIs<EngineSource.Leased>(engineSourceOpen(locator, null))
        try {
            // An elapsed window fails the probe before any HTTP request or native-engine call.
            assertTrue(source.lease.open(LongArray(2)) < 0)
        } finally { source.discard() }
    }

    @Test
    fun deezerLeaseAlsoRetainsTheKnownAuthorizationWindow() {
        val locator = PlaybackLocator.DeezerEncrypted("7", "https://audio.invalid/song", expiresAtEpochMs = 1L)
        val source = assertIs<EngineSource.Leased>(engineSourceOpen(locator, null))
        try {
            assertTrue(source.lease.open(LongArray(2)) < 0)
        } finally { source.discard() }
    }
}
