package moe.rukamori.archivetune.shared.domain.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LyricsProviderPreferencesTest {
    @Test
    fun retiredProviderIdsDropWithoutChangingTheRemainingOrder() {
        val saved = "PAXSENIX_SPOTIFY,LRCLIB,PAXSENIX_NETEASE,KUGOU,PAXSENIX_YOUTUBE," +
            "BETTER_LYRICS,PAXSENIX_MUSIXMATCH,KUGOU,UNKNOWN"
        val order = parseLyricsProviderOrder(saved)

        assertEquals(listOf(LyricsProviderPreference.LRCLIB, LyricsProviderPreference.KUGOU,
            LyricsProviderPreference.BETTER_LYRICS), order.take(3))
        assertEquals(LyricsProviderPreference.entries.toSet(), order.toSet())
        assertEquals(order.size, order.distinct().size)
        assertFalse(order.any { it.name in saved.split(',').filter { name -> name.startsWith("PAXSENIX_") } })
    }

    @Test
    fun retiredOnlyAndBlankOrdersUseTheCurrentDefaults() {
        assertEquals(LyricsProviderPreference.entries, parseLyricsProviderOrder("PAXSENIX_SPOTIFY,PAXSENIX_YOUTUBE"))
        assertEquals(LyricsProviderPreference.entries, parseLyricsProviderOrder(null))
        assertEquals(LyricsProviderPreference.entries, parseLyricsProviderOrder(" "))
    }

    @Test
    fun leftoverRetiredTogglesDoNotChangeSurvivingPreferences() {
        val preferences = PreferenceSnapshot.of(
            BooleanPreferenceKey("enablePaxsenixSpotifyLyrics") to true,
            EnableLrcLibKey to false,
            LyricsProviderOrderKey to "PAXSENIX_SPOTIFY,LRCLIB,KUGOU",
        )
        assertEquals(LyricsProviderPreference.LRCLIB, preferences.lyricsProviderOrder().first())
        assertFalse(preferences.lyricsProviderEnabled("LrcLib"))
    }
}
