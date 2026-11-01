package moe.rukamori.archivetune.shared.domain.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.source.SpotifyProduct
import moe.rukamori.archivetune.shared.domain.source.SpotifyStreamEngine

/**
 * Pins the engine-aware streaming settings: the stream role needs a session for the stored engine's
 * account binding, product observations live per account, and a stored Librespot choice degrades to
 * Unofficial while the library account is not observed Premium.
 */
class SpotifyStreamingSettingsPolicyTest {
    @Test
    fun theStreamSessionFollowsTheEngineBinding() {
        val bothSignedIn = PreferenceSnapshot.of(SpotifyStreamSpDcKey to "s", SpotifySpDcKey to "c")
        assertTrue(bothSignedIn.spotifyStreamSessionPresent(SpotifyStreamEngine.UNOFFICIAL))
        assertTrue(bothSignedIn.spotifyStreamSessionPresent(SpotifyStreamEngine.LIBRESPOT))

        val dedicatedOnly = PreferenceSnapshot.of(SpotifyStreamSpDcKey to "s")
        assertTrue(dedicatedOnly.spotifyStreamSessionPresent(SpotifyStreamEngine.UNOFFICIAL))
        assertFalse(dedicatedOnly.spotifyStreamSessionPresent(SpotifyStreamEngine.LIBRESPOT))

        val libraryOnly = PreferenceSnapshot.of(SpotifySpDcKey to "c")
        assertFalse(libraryOnly.spotifyStreamSessionPresent(SpotifyStreamEngine.UNOFFICIAL))
        assertTrue(libraryOnly.spotifyStreamSessionPresent(SpotifyStreamEngine.LIBRESPOT))
    }

    @Test
    fun enablingStreamingRequiresTheBoundSession() {
        val noSession = PreferenceSnapshot.EMPTY.toEditor()
        noSession.setSpotifyStreamingEnabled(true)
        assertEquals(null, noSession.snapshot()[SpotifyStreamEnabledKey])

        val dedicated = PreferenceSnapshot.of(SpotifyStreamSpDcKey to "s").toEditor()
        dedicated.setSpotifyStreamingEnabled(true)
        assertEquals(true, dedicated.snapshot()[SpotifyStreamEnabledKey])

        val librespotSignedOut = PreferenceSnapshot.of(SpotifyStreamEngineKey to SpotifyStreamEngine.LIBRESPOT.name).toEditor()
        librespotSignedOut.setSpotifyStreamingEnabled(true)
        assertEquals(null, librespotSignedOut.snapshot()[SpotifyStreamEnabledKey])

        val librespotSignedIn =
            PreferenceSnapshot.of(
                SpotifyStreamEngineKey to SpotifyStreamEngine.LIBRESPOT.name,
                SpotifySpDcKey to "c",
            ).toEditor()
        librespotSignedIn.setSpotifyStreamingEnabled(true)
        assertEquals(true, librespotSignedIn.snapshot()[SpotifyStreamEnabledKey])
    }

    @Test
    fun theEnabledFlagReadsThroughTheStoredEngineSession() {
        val librespotEnabled = PreferenceSnapshot.of(
            SpotifyStreamEngineKey to SpotifyStreamEngine.LIBRESPOT.name,
            SpotifyStreamEnabledKey to true,
            SpotifySpDcKey to "c",
        )
        assertTrue(librespotEnabled.spotifyStreamingEnabledForSettings())

        val dedicatedEnabledSignedOut = PreferenceSnapshot.of(
            SpotifyStreamEnabledKey to true,
        )
        assertFalse(dedicatedEnabledSignedOut.spotifyStreamingEnabledForSettings())
    }

    @Test
    fun theEngineChoicePersistsByName() {
        val editor = PreferenceSnapshot.EMPTY.toEditor()
        editor.setSpotifyStreamEngine(SpotifyStreamEngine.LIBRESPOT)
        val snapshot = editor.snapshot()
        assertEquals(SpotifyStreamEngine.LIBRESPOT.name, snapshot[SpotifyStreamEngineKey])
        assertEquals(SpotifyStreamEngine.LIBRESPOT, snapshot.spotifyStreamEngine())

        assertEquals(SpotifyStreamEngine.UNOFFICIAL, PreferenceSnapshot.EMPTY.spotifyStreamEngine())
    }

    @Test
    fun theStreamAccountProductReadsTheBoundAccount() {
        val prefs = PreferenceSnapshot.of(
            SpotifyProductKey to "premium",
            SpotifyStreamProductKey to "free",
        )
        assertEquals(SpotifyProduct.PREMIUM, prefs.spotifyStreamAccountProduct(SpotifyStreamEngine.LIBRESPOT))
        assertEquals(SpotifyProduct.FREE, prefs.spotifyStreamAccountProduct(SpotifyStreamEngine.UNOFFICIAL))
    }

    @Test
    fun theEffectiveEngineDegradesStoredLibrespotWithoutLibraryPremium() {
        val premium = PreferenceSnapshot.of(
            SpotifyStreamEngineKey to SpotifyStreamEngine.LIBRESPOT.name,
            SpotifyProductKey to "premium",
        )
        assertEquals(SpotifyStreamEngine.LIBRESPOT, premium.spotifyStreamEngineEffective())

        val free = PreferenceSnapshot.of(
            SpotifyStreamEngineKey to SpotifyStreamEngine.LIBRESPOT.name,
            SpotifyProductKey to "free",
        )
        assertEquals(SpotifyStreamEngine.UNOFFICIAL, free.spotifyStreamEngineEffective())

        val unknown = PreferenceSnapshot.of(
            SpotifyStreamEngineKey to SpotifyStreamEngine.LIBRESPOT.name,
        )
        assertEquals(SpotifyStreamEngine.UNOFFICIAL, unknown.spotifyStreamEngineEffective())
    }

    @Test
    fun productObservationsPersistAndClearWithTheSession() {
        val editor = PreferenceSnapshot.EMPTY.toEditor()
        editor.recordSpotifyLibraryProduct("premium", 1000L)
        editor.recordSpotifyStreamProduct("free", 2000L)
        val recorded = editor.snapshot()
        assertEquals(SpotifyProduct.PREMIUM, recorded.spotifyLibraryProduct())
        assertEquals(SpotifyProduct.FREE, recorded.spotifyStreamProduct())
        assertEquals(2000L, recorded[SpotifyStreamProductCheckedAtKey])

        val sessionEditor = PreferenceSnapshot.of(
            SpotifyStreamSpDcKey to "s",
            SpotifyStreamProductKey to "premium",
            SpotifyStreamProductCheckedAtKey to 1000L,
            SpotifyStreamEngineKey to SpotifyStreamEngine.LIBRESPOT.name,
        ).toEditor()
        sessionEditor.clearSpotifyStreamingSession()
        val cleared = sessionEditor.snapshot()
        assertEquals(null, cleared[SpotifyStreamProductKey])
        assertEquals(null, cleared[SpotifyStreamProductCheckedAtKey])
        assertEquals(SpotifyProduct.UNKNOWN, cleared.spotifyStreamProduct())
        // The engine is a user preference, not session data, so signing out keeps it.
        assertEquals(SpotifyStreamEngine.LIBRESPOT, cleared.spotifyStreamEngine())
    }
}
