package moe.rukamori.archivetune.shared.domain.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanvasPlaybackPolicyTest {
    private val wifi = CanvasConnectivity(online = true, wifi = true, metered = false)
    private val meteredWifi = CanvasConnectivity(online = true, wifi = true, metered = true)
    private val cell = CanvasConnectivity(online = true, wifi = false, metered = true)

    @Test
    fun eitherToggleTurnsTheSurfaceOn() {
        assertFalse(PreferenceSnapshot.EMPTY.canvasPlaybackEnabled())
        assertTrue(PreferenceSnapshot.of(ArchiveTuneCanvasKey to true).canvasPlaybackEnabled())
        assertTrue(PreferenceSnapshot.of(SpotifyCanvasKey to true).canvasPlaybackEnabled())
    }

    @Test
    fun reducedMotionAlwaysWinsOverTheSurface() {
        val both = PreferenceSnapshot.of(
            ArchiveTuneCanvasKey to true, SpotifyCanvasKey to true, DisableAnimationsKey to true,
        )
        assertFalse(both.canvasPlaybackEnabled())
        // The baseline network policy has no motion term; motion gates the surface only.
        val moving = PreferenceSnapshot.of(
            ArchiveTuneCanvasKey to true, DisableAnimationsKey to true, NetworkMeteredKey to false,
        )
        assertTrue(moving.canvasPlaybackAllowed(CanvasHealthProvider.BETTER_LYRICS, wifi))
    }

    @Test
    fun theMasterSwitchGatesEveryProvider() {
        val spotifyOnly = PreferenceSnapshot.of(SpotifyCanvasKey to true, NetworkMeteredKey to false)
        assertFalse(spotifyOnly.canvasPlaybackAllowed(CanvasHealthProvider.SPOTIFY, wifi))
        assertFalse(spotifyOnly.canvasPlaybackAllowed(CanvasHealthProvider.BETTER_LYRICS, wifi))
        // The Spotify resolver additionally needs its own toggle, like the baseline's trySpotifyCanvas.
        val masterOnly = PreferenceSnapshot.of(ArchiveTuneCanvasKey to true, NetworkMeteredKey to false)
        assertTrue(masterOnly.canvasPlaybackAllowed(CanvasHealthProvider.BETTER_LYRICS, wifi))
        assertFalse(masterOnly.canvasPlaybackAllowed(CanvasHealthProvider.SPOTIFY, wifi))
        val both = PreferenceSnapshot.of(
            ArchiveTuneCanvasKey to true, SpotifyCanvasKey to true, NetworkMeteredKey to false,
        )
        assertTrue(both.canvasPlaybackAllowed(CanvasHealthProvider.SPOTIFY, wifi))
    }

    @Test
    fun theStoredSourceChoiceIsHonored() {
        val selected = PreferenceSnapshot.of(
            ArchiveTuneCanvasKey to true, CanvasSourceKey to "BETTER_LYRICS", NetworkMeteredKey to false,
        )
        assertTrue(selected.canvasPlaybackAllowed(CanvasHealthProvider.BETTER_LYRICS, wifi))
        assertFalse(selected.canvasPlaybackAllowed(CanvasHealthProvider.APPLE_MUSIC, wifi))
        // A missing, unknown or legacy value reads as ALL, like the baseline fromPreference.
        val legacy = PreferenceSnapshot.of(
            ArchiveTuneCanvasKey to true, CanvasSourceKey to "BOTH", NetworkMeteredKey to false,
        )
        assertTrue(legacy.canvasPlaybackAllowed(CanvasHealthProvider.APPLE_MUSIC, wifi))
    }

    @Test
    fun networkGatesFailClosed() {
        val snapshot = PreferenceSnapshot.of(ArchiveTuneCanvasKey to true, NetworkMeteredKey to false)
        assertFalse(snapshot.canvasPlaybackAllowed(CanvasHealthProvider.BETTER_LYRICS, null))
        assertFalse(snapshot.canvasPlaybackAllowed(CanvasHealthProvider.BETTER_LYRICS, wifi.copy(online = false)))
        // Low-data mode defaults on and blocks metered connectivity until the user clears it.
        assertFalse(
            PreferenceSnapshot.of(ArchiveTuneCanvasKey to true)
                .canvasPlaybackAllowed(CanvasHealthProvider.BETTER_LYRICS, meteredWifi),
        )
        assertTrue(snapshot.canvasPlaybackAllowed(CanvasHealthProvider.BETTER_LYRICS, meteredWifi))
        // Wifi-only blocks non-wifi even with low-data off.
        val wifiOnly = PreferenceSnapshot.of(
            ArchiveTuneCanvasKey to true, CanvasWifiOnlyKey to true, NetworkMeteredKey to false,
        )
        assertFalse(wifiOnly.canvasPlaybackAllowed(CanvasHealthProvider.BETTER_LYRICS, cell))
        assertTrue(wifiOnly.canvasPlaybackAllowed(CanvasHealthProvider.BETTER_LYRICS, wifi))
    }
}
