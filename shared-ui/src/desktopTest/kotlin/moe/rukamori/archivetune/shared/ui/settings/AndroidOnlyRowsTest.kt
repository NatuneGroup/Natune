package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*
import kotlin.test.*

class AndroidOnlyRowsTest {
    @Test fun emptyPolicyKeepsEveryRow() {
        val rows = lyricsSettingsRows({})
        assertEquals(rows, rows.withoutUnsupportedRows(emptySet()))
    }

    @Test fun appearanceOmitsRowsWithOnlyAndroidReadersButKeepsSharedOnes() {
        val preferences = PreferenceSnapshot.of(
            PlayerDesignStyleKey to PlayerDesignStyle.APPLE_MUSIC.name,
            AppleMusicSettingsMigratedKey to true,
        )
        val rows = appearanceThemeSettingsRows(preferences, false, false, false, false, {},
            {}, {}, {}, {}) +
            playerAppearanceSettingsRows(preferences, true, {}, {}) +
            appearanceExtrasSettingsRows()
        val visible = rows.withoutUnsupportedRows(AndroidOnlySettingsAnchors.all)
        assertTrue(visible.none { it.anchor in AndroidOnlySettingsAnchors.appearance })
        // The shared rows on the same page survive.
        listOf("disable_blur", "dynamic_theme", "interface_style", "player_design_style", "player_menu_style").forEach {
            assertTrue(visible.any { row -> row.anchor == it }, "missing $it")
        }
    }

    @Test fun playbackOmitsAndroidOnlyProcessingAndQueueRows() {
        val preferences = PreferenceSnapshot.of(AutomixEnabledKey to true)
        val rows = playbackSettingsRows(preferences, true, false, true, {}, {}, {})
        val visible = rows.withoutUnsupportedRows(AndroidOnlySettingsAnchors.all)
        assertTrue(visible.none { it.anchor in AndroidOnlySettingsAnchors.playback })
        listOf("audio_processing", "audio_quality", "infinite_queue", "crossfade").forEach {
            assertTrue(visible.any { row -> row.anchor == it }, "missing $it")
        }
    }

    @Test fun lyricsAndDownloadsKeepTheirSharedRows() {
        // Lyrics settings stay exactly as on dev for the separate lyrics-screen work.
        val lyrics = lyricsSettingsRows({}).withoutUnsupportedRows(AndroidOnlySettingsAnchors.all)
        assertTrue(lyrics.any { it.anchor == "lyrics_scroll" })
        assertTrue(lyrics.any { it.anchor == "lyrics_click" })

        val downloads = listOf(
            SettingsRow.Toggle("auto_download_like", Res.string.m6_download_like, AutoDownloadOnLikeKey, false),
            SettingsRow.Action("download_source", Res.string.m6_download_sources, {}),
            SettingsRow.Toggle("external_downloader", Res.string.m6_external_downloader, ExternalDownloaderEnabledKey, false),
        ).withoutUnsupportedRows(AndroidOnlySettingsAnchors.all)
        assertTrue(downloads.none { it.anchor in AndroidOnlySettingsAnchors.downloads })
        assertTrue(downloads.any { it.anchor == "download_source" })
    }

    @Test fun everyAppearancePolicyAnchorIsEitherAPageRowOrAnAndroidOnlyEntry() {
        // A mistyped anchor would silently filter nothing, so keep the inventory honest: these are the entries whose
        // pages only exist on Android, and every other anchor must be a row the appearance builders can emit.
        val entries = setOf("aod", "lyrics_animations", "discord_experimental", "aod_customize")
        val apple = PreferenceSnapshot.of(
            PlayerDesignStyleKey to PlayerDesignStyle.APPLE_MUSIC.name,
            AppleMusicSettingsMigratedKey to true, DynamicThemeKey to false, DarkModeKey to "ON",
        )
        val rows = appearanceThemeSettingsRows(apple, true, false, false, false, {},
            {}, {}, {}, {}) +
            playerAppearanceSettingsRows(apple, true, {}, {}) +
            playerAppearanceSettingsRows(PreferenceSnapshot.of(PlayerDesignStyleKey to PlayerDesignStyle.TIKTOK.name), true, {}, {}) +
            playerAppearanceSettingsRows(PreferenceSnapshot.of(PlayerDesignStyleKey to PlayerDesignStyle.METROLIST_KMP.name), true, {}, {}) +
            appearanceInterfaceSettingsRows({}, {}, {}) +
            appearanceExtrasSettingsRows()
        val anchors = rows.map { it.anchor }.toSet() + entries
        assertEquals(emptySet(), AndroidOnlySettingsAnchors.appearance - anchors)
    }
}
