package moe.rukamori.archivetune.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import moe.rukamori.archivetune.shared.domain.settings.ActiveHomeSourcesKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.QqMusicMusickeyKey
import moe.rukamori.archivetune.shared.domain.settings.QqMusicUinKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifySpDcKey

class DesktopGeneralEditorFormatsTest {
    private val formats = DesktopGeneralEditorFormats

    @Test fun artworkOrderDropsUnknownAndAppendsMissingProviders() {
        assertEquals(formats.defaultArtworkProviders, formats.artworkProviderOrder(null))
        assertEquals(
            listOf("TIDAL", "LOCAL_EMBEDDED", "ORIGINAL_METADATA", "SPOTIFY_CANVAS", "ARCHIVETUNE_CANVAS"),
            formats.artworkProviderOrder("TIDAL, BOGUS,TIDAL"),
        )
        assertEquals(
            "SPOTIFY_CANVAS,LOCAL_EMBEDDED,ORIGINAL_METADATA,TIDAL,ARCHIVETUNE_CANVAS",
            formats.artworkProviderPreference(listOf("SPOTIFY_CANVAS")),
        )
    }

    @Test fun playlistTagOrderKeepsSavedIdsFirstAndForgetsDeletedTags() {
        assertEquals(listOf("b", "a", "c"), formats.playlistTagOrder("b,gone,b", listOf("a", "b", "c")))
        assertEquals(listOf("a", "b"), formats.playlistTagOrder("", listOf("a", "b")))
        assertEquals("b,a", formats.playlistTagPreference(listOf("b", "a", "b")))
    }

    @Test fun homeSourcesDefaultToYouTubeAndEachSignedInPage() {
        assertEquals(listOf("YOUTUBE"), formats.homeSourceSelection(PreferenceSnapshot.EMPTY))
        val signedIn = PreferenceSnapshot.of(
            SpotifySpDcKey to "cookie", QqMusicUinKey to "1", QqMusicMusickeyKey to "key",
        )
        assertEquals(listOf("YOUTUBE", "SPOTIFY", "QQ"), formats.homeSourceSelection(signedIn))
        val stored = PreferenceSnapshot.of(ActiveHomeSourcesKey to "qq, youtube,unknown")
        assertEquals(listOf("QQ", "YOUTUBE"), formats.homeSourceSelection(stored))
    }

    @Test fun anUntouchedHomePickerStoresNothing() {
        assertNull(formats.homeSourcesToStore(listOf("YOUTUBE"), setOf("YOUTUBE")))
        assertEquals("YOUTUBE,SPOTIFY", formats.homeSourcesToStore(listOf("YOUTUBE"), setOf("SPOTIFY", "YOUTUBE")))
    }

    @Test fun moveIgnoresOutOfRangeTargets() {
        assertEquals(listOf(2, 1, 3), formats.move(listOf(1, 2, 3), 1, -1))
        assertEquals(listOf(1, 2, 3), formats.move(listOf(1, 2, 3), 0, -1))
        assertEquals(listOf(1, 2, 3), formats.move(listOf(1, 2, 3), 2, 1))
    }
}
