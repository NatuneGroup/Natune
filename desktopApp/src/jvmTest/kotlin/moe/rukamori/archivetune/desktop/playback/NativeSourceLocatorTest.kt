package moe.rukamori.archivetune.desktop.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.playback.PlaybackLocator

class NativeSourceLocatorTest {
    @Test
    fun headerFreeUnknownExpiryKeepsTheExistingEngineOpener() {
        val locator = PlaybackLocator.Remote("https://audio.invalid/song")
        assertEquals(locator.url, assertIs<EngineSource.Direct>(engineSourceOpen(locator, null)).source)
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
