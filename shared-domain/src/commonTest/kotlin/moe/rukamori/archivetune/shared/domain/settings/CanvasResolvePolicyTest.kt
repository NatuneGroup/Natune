package moe.rukamori.archivetune.shared.domain.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanvasResolvePolicyTest {
    private val wifi = CanvasConnectivity(online = true, wifi = true, metered = false)
    private val cellular = CanvasConnectivity(online = true, wifi = false, metered = true, cellular = true)

    @Test
    fun providersResolveInTheDeclaredOrderWithoutAll() {
        assertEquals(
            listOf(
                CanvasHealthProvider.BETTER_LYRICS,
                CanvasHealthProvider.APPLE_MUSIC,
                CanvasHealthProvider.TIDAL,
                CanvasHealthProvider.SPOTIFY,
            ),
            canvasResolveProviderOrder(),
        )
    }

    @Test
    fun aSourceNameMapsToItsHealthProvider() {
        assertEquals(CanvasHealthProvider.TIDAL, canvasHealthProviderOf("TIDAL"))
        assertEquals(null, canvasHealthProviderOf("ALL"))
        assertEquals(null, canvasHealthProviderOf(null))
    }

    @Test
    fun theRemoteGateSharesThePlaybackPolicy() {
        val off = PreferenceSnapshot.EMPTY
        assertFalse(off.canvasRemoteAllowed(CanvasHealthProvider.BETTER_LYRICS, wifi))
        val on = PreferenceSnapshot.of(ArchiveTuneCanvasKey to true, NetworkMeteredKey to false)
        assertTrue(on.canvasRemoteAllowed(CanvasHealthProvider.BETTER_LYRICS, wifi))
        // A closed source choice refuses the request even when the provider is reachable.
        val selected = PreferenceSnapshot.of(
            ArchiveTuneCanvasKey to true, CanvasSourceKey to "TIDAL", NetworkMeteredKey to false,
        )
        assertFalse(selected.canvasRemoteAllowed(CanvasHealthProvider.BETTER_LYRICS, wifi))
        assertTrue(selected.canvasRemoteAllowed(CanvasHealthProvider.TIDAL, wifi))
        // Spotify additionally needs its own toggle.
        assertFalse(on.canvasRemoteAllowed(CanvasHealthProvider.SPOTIFY, wifi))
        val both = PreferenceSnapshot.of(
            ArchiveTuneCanvasKey to true, SpotifyCanvasKey to true, NetworkMeteredKey to false,
        )
        assertTrue(both.canvasRemoteAllowed(CanvasHealthProvider.SPOTIFY, wifi))
        // Wi-Fi-only refuses a measured cellular link.
        val wifiOnly = PreferenceSnapshot.of(
            ArchiveTuneCanvasKey to true, CanvasWifiOnlyKey to true, NetworkMeteredKey to false,
        )
        assertFalse(wifiOnly.canvasRemoteAllowed(CanvasHealthProvider.BETTER_LYRICS, cellular))
    }

    @Test
    fun thePreferenceProbeCoversEveryWatchedKey() {
        val probe = canvasPreferenceProbe(
            PreferenceSnapshot.of(
                ArchiveTuneCanvasKey to true,
                CanvasSourceKey to "SPOTIFY",
                SpotifySpDcKey to "dc",
            ),
        )
        assertEquals(true, probe[0])
        assertEquals("SPOTIFY", probe[2])
        assertEquals("dc", probe[7])
        assertEquals(emptyList(), canvasPreferenceProbe(null))
    }

    @Test
    fun resolverEndpointsAreValidatedDeduplicatedAndBounded() {
        assertEquals(
            listOf("https://a.example", "http://127.0.0.1:8080"),
            parseCanvasResolverEndpoints(" https://a.example/\nhttp://127.0.0.1:8080, https://a.example "),
        )
        assertEquals(emptyList(), parseCanvasResolverEndpoints(null))
        assertEquals(
            emptyList(),
            parseCanvasResolverEndpoints("ftp://x.example, https://user@h.example"),
        )
        assertEquals(
            8,
            parseCanvasResolverEndpoints((1..20).joinToString("\n") { "https://h$it.example" }).size,
        )
    }

    @Test
    fun aCanvasLoopUrlMustBePublicHttps() {
        assertTrue(canvasLoopUrlAllowed("https://video.example/canvas.mp4"))
        assertFalse(canvasLoopUrlAllowed("http://video.example/canvas.mp4"))
        assertFalse(canvasLoopUrlAllowed("https://user@video.example/canvas.mp4"))
        assertFalse(canvasLoopUrlAllowed("https:///canvas.mp4"))
        assertFalse(canvasLoopUrlAllowed("not a url"))
    }

    @Test
    fun loopPositionIsElapsedAudioModuloDuration() {
        assertEquals(0L, canvasLoopPositionMs(0, 0, 8_000L))
        assertEquals(3_000L, canvasLoopPositionMs(11_000, 0, 8_000L))
        assertEquals(1_000L, canvasLoopPositionMs(9_000, 2_000, 6_000L))
        assertEquals(0L, canvasLoopPositionMs(9_000, 2_000, 7_000L))
        // An unknown duration plays elapsed time once, from the anchor.
        assertEquals(5_000L, canvasLoopPositionMs(5_000, 0, null))
        assertEquals(5_000L, canvasLoopPositionMs(5_000, 0, 0L))
        // Audio before the anchor clamps to the loop start.
        assertEquals(5_000L, canvasLoopPositionMs(1_000, 2_000, 6_000L))
        assertEquals(0L, canvasLoopPositionMs(-1_000, 500, null))
    }

    @Test
    fun foldedTrackNamesCompareLoosely() {
        assertTrue(comparableSongTitle("Song (Live)", "song"))
        assertTrue(comparableSongTitle("HELLO!", "hello"))
        assertFalse(comparableSongTitle("abc", "abcd"))
        assertFalse(comparableSongTitle("", "x"))
    }
}
