package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.domain.settings.*
import kotlin.test.*

class SettingsRoutingTest {
    @Test fun ytdlpResultOwnsARealRuntimePageNotExternalDownloaderControls() {
        assertEquals("settings/player/ytdlp", settingsSearchRoute("playback", "ytdlp"))
        assertEquals("settings/player/ytdlp", SettingsRoutes.YTDLP)
        assertEquals("settings/downloads?scrollTo=external_downloader", settingsSearchRoute("downloads", "external_downloader"))
        assertEquals("settings/misc?scrollTo=acoustic_quality", settingsSearchRoute("developer_options", "acoustic_quality"))
        assertEquals("settings/about", settingsSearchRoute("about", "version"))
        assertNull(settingsSearchRoute("unknown", null))
    }

    @Test fun japaneseCanBeDisabledAfterPackRemovalAndAiOverridesAllBuiltins() {
        fun japanese(stored: Boolean, ai: Boolean = false) = romanizationSettingsRows(
            ai, japanesePackReady = false, PreferenceSnapshot.of(LyricsRomanizeJapaneseKey to stored),
        ).first()
        assertFalse(japanese(false).enabled)
        assertTrue(japanese(true).enabled)
        assertFalse(japanese(true, true).enabled)
    }

    @Test fun wordSyncKeepsFallbackAndManualProviderControlsUsable() {
        val prefs = PreferenceSnapshot.of(PrioritizeWordSyncedLyricsKey to true, EnableLrcLibKey to false)
        // A built-in Lyprio pair keeps every provider toggle usable; without one that row alone greys out.
        // The provider-order row is the deliberate exception: with the sweep on, sync quality picks.
        val rows = lyricsProviderSettingsRows(prefs, {}, {}, {}, lyprioBuiltInServer = true)
        assertTrue(rows.single { it.anchor == "prioritize_word_synced_lyrics" }.enabled)
        assertTrue(rows.all { it.enabled || it.anchor == "lyrics_provider_order" })
        val order = rows.single { it.anchor == "lyrics_provider_order" }
        assertFalse(order.enabled)
        assertNotNull(order.description)
        assertNotNull(rows.single { it.anchor == "prioritize_word_synced_lyrics" }.description)
        assertFalse(prefs[EnableLrcLibKey, true])
        // With the sweep off, the order row stays interactive.
        val off = lyricsProviderSettingsRows(
            PreferenceSnapshot.of(PrioritizeWordSyncedLyricsKey to false), {}, {}, {}, lyprioBuiltInServer = true,
        )
        assertTrue(off.all { it.enabled })
        assertNull(off.single { it.anchor == "lyrics_provider_order" }.description)
    }

    @Test fun legacyV2SearchAnchorOpensTheCanonicalRendererChoice() {
        assertEquals("settings/lyrics?scrollTo=lyrics_mode", settingsSearchRoute("lyrics", "use_lyrics_v2"))
    }
}
