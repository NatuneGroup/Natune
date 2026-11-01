package moe.rukamori.archivetune.shared.domain.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Characterizes the dual Spotify playback roles: the unofficial engine stays bound to a dedicated alt
 * account, Librespot binds the library account, and the same-account stand-down applies to Unofficial only.
 */
class SpotifyAccountRolesTest {
    @Test
    fun theUnofficialEngineBindsADedicatedStreamingAccount() {
        assertEquals(SpotifyStreamAccountBinding.DEDICATED, SpotifyStreamEngine.UNOFFICIAL.streamAccountBinding())
    }

    @Test
    fun librespotBindsTheLibraryAccount() {
        assertEquals(SpotifyStreamAccountBinding.LIBRARY, SpotifyStreamEngine.LIBRESPOT.streamAccountBinding())
    }

    @Test
    fun anUnknownStoredEngineReadsAsUnofficial() {
        assertEquals(SpotifyStreamEngine.UNOFFICIAL, SpotifyStreamEngine.fromPreference("not-an-engine"))
        assertEquals(SpotifyStreamEngine.UNOFFICIAL, SpotifyStreamEngine.fromPreference(null))
    }

    @Test
    fun storedEngineNamesRoundTrip() {
        SpotifyStreamEngine.entries.forEach { engine ->
            assertEquals(engine, SpotifyStreamEngine.fromPreference(engine.name))
        }
    }

    @Test
    fun theUnofficialEngineStandsDownOnTheSameAccount() {
        assertTrue(
            SpotifyStreamEngine.UNOFFICIAL.streamingStandsDown(
                catalogSpDc = "cookie-a",
                streamingSpDc = "cookie-b",
                streamingUserId = "user-1",
                catalogUserId = { "user-1" },
            ),
        )
    }

    @Test
    fun librespotNeverStandsDownOnTheSameAccount() {
        assertFalse(
            SpotifyStreamEngine.LIBRESPOT.streamingStandsDown(
                catalogSpDc = "cookie-a",
                streamingSpDc = "cookie-a",
                streamingUserId = "user-1",
                catalogUserId = { fail("the catalog account must not be read for a librespot binding") },
            ),
        )
    }

    @Test
    fun theUnofficialEngineKeepsRunningForDifferentAccounts() {
        assertFalse(
            SpotifyStreamEngine.UNOFFICIAL.streamingStandsDown(
                catalogSpDc = "cookie-a",
                streamingSpDc = "cookie-b",
                streamingUserId = "user-1",
                catalogUserId = { "user-2" },
            ),
        )
    }
}
