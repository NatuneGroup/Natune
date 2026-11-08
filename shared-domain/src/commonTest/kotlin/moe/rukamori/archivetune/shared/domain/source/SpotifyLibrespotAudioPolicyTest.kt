package moe.rukamori.archivetune.shared.domain.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpotifyLibrespotAudioPolicyTest {
    private val id = "3BRfgsKjcakny2wCYfZrvz"

    @Test
    fun directIdsUrisAndCatalogLinksNameTheSameTrack() {
        listOf(id, "spotify:track:$id", "sp:$id", "https://open.spotify.com/track/$id?si=share",
            "https://open.spotify.com/intl-en/track/$id").forEach {
            assertEquals("spotify:track:$id", SpotifyLibrespotAudioPolicy.trackUri(it))
        }
    }

    @Test
    fun nonTrackAndUntrustedInputsCannotBePlayedAsTrackIds() {
        listOf(null, "", "spotify:album:$id", "spotify:episode:$id", "https://evil.example/track/$id",
            "https://open.spotify.com.evil/track/$id", "https://user@open.spotify.com/track/$id",
            "https://open.spotify.com/track/$id/extra", "$id/", "é".repeat(22)).forEach {
            assertNull(SpotifyLibrespotAudioPolicy.trackId(it))
        }
    }

    @Test
    fun isrcSearchCannotAcceptAnInjectedQuery() {
        assertEquals("USUM71703861", SpotifyLibrespotAudioPolicy.isrc("us-um7-17-03861"))
        assertNull(SpotifyLibrespotAudioPolicy.isrc("USUM71703861 OR artist:other"))
        assertNull(SpotifyLibrespotAudioPolicy.isrc("USUM7170386"))
    }

    @Test
    fun qualityCapsAndMissingTiersChooseOnlyAnAvailableVorbisFile() {
        val files = setOf(0, 2, 16, 11)
        assertEquals(2, SpotifyLibrespotAudioPolicy.selectFormat(files, "AUTO", false))
        assertEquals(2, SpotifyLibrespotAudioPolicy.selectFormat(files, "HIGHEST", false))
        assertEquals(0, SpotifyLibrespotAudioPolicy.selectFormat(files, "HIGH", false))
        assertEquals(0, SpotifyLibrespotAudioPolicy.selectFormat(files, "HIGHEST", true))
        assertNull(SpotifyLibrespotAudioPolicy.selectFormat(setOf(2), "LOW", false))
        assertNull(SpotifyLibrespotAudioPolicy.selectFormat(setOf(11, 16), "AUTO", false))
        assertEquals(160_000, SpotifyLibrespotAudioPolicy.bitrate(1))
        assertNull(SpotifyLibrespotAudioPolicy.bitrate(16))
    }

    @Test
    fun sourceAdmissionRequiresTheChosenEnginePremiumToggleAndGrant() {
        assertTrue(SpotifyLibrespotAudioPolicy.admitted(SpotifyStreamEngine.LIBRESPOT, true, SpotifyProduct.PREMIUM, true))
        assertFalse(SpotifyLibrespotAudioPolicy.admitted(SpotifyStreamEngine.UNOFFICIAL, true, SpotifyProduct.PREMIUM, true))
        assertFalse(SpotifyLibrespotAudioPolicy.admitted(SpotifyStreamEngine.LIBRESPOT, false, SpotifyProduct.PREMIUM, true))
        assertFalse(SpotifyLibrespotAudioPolicy.admitted(SpotifyStreamEngine.LIBRESPOT, true, SpotifyProduct.UNKNOWN, true))
        assertFalse(SpotifyLibrespotAudioPolicy.admitted(SpotifyStreamEngine.LIBRESPOT, true, SpotifyProduct.FREE, true))
        assertFalse(SpotifyLibrespotAudioPolicy.admitted(SpotifyStreamEngine.LIBRESPOT, true, SpotifyProduct.PREMIUM, false))
    }

    @Test
    fun deniedKeysAndThrottlingFallThroughWithoutAReconnectStorm() {
        assertEquals(SpotifyLibrespotFailure.AUDIO_KEY_DENIED, SpotifyLibrespotAudioPolicy.failure("AUDIO_KEY"))
        assertFalse(SpotifyLibrespotAudioPolicy.failure("AUDIO_KEY").reconnectOnNextRequest)
        assertFalse(SpotifyLibrespotAudioPolicy.failure("RATE_LIMITED").reconnectOnNextRequest)
        assertFalse(SpotifyLibrespotAudioPolicy.failure("ACCESS_DENIED").reconnectOnNextRequest)
        assertTrue(SpotifyLibrespotAudioPolicy.failure("NETWORK").reconnectOnNextRequest)
        assertTrue(SpotifyLibrespotAudioPolicy.failure("TIMEOUT").reconnectOnNextRequest)
        assertEquals(SpotifyLibrespotFailure.INVALID_RESPONSE, SpotifyLibrespotAudioPolicy.failure("unexpected server body"))
    }
}
