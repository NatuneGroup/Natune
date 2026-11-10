package moe.rukamori.archivetune.spotify

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifySessionFailureTest {
    @Test
    fun aRefusedCookieIsACredentialRejection() {
        assertTrue(SpotifySessionFailure.isCredentialRejection(Spotify.SpotifyException(401, "anonymous token")))
        assertTrue(SpotifySessionFailure.isCredentialRejection(Spotify.SpotifyException(403, "forbidden")))
    }

    @Test
    fun networkAndServiceFailuresAreRetryable() {
        assertFalse(SpotifySessionFailure.isCredentialRejection(IOException("offline")))
        assertFalse(SpotifySessionFailure.isCredentialRejection(Spotify.SpotifyException(503, "temporarily unavailable")))
        assertFalse(SpotifySessionFailure.isCredentialRejection(Spotify.SpotifyException(429, "rate limited")))
    }
}
