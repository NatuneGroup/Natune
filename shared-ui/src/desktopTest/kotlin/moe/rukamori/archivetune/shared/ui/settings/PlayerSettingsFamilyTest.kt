package moe.rukamori.archivetune.shared.ui.settings

import java.nio.file.Files
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*
import kotlin.test.*
import androidx.compose.runtime.setValue

@OptIn(ExperimentalTestApi::class)
class PlayerSettingsFamilyTest {
    @Test fun publishedStyleOrderDefaultAndActualSelectorStayExactlyAligned() {
        val frozen = listOf("V1", "V2", "V3", "V4", "V5", "V6", "V7", "V8", "V9", "APPLE_MUSIC",
            "V10", "BITCHORD", "TIKTOK", "SIMPMUSIC", "SPATIALFLOW", "LOOPER", "FLAMINGO", "METROLIST_KMP")
        assertEquals(frozen, PlayerDesignStyle.entries.map { it.name })
        assertEquals(frozen, playerDesignOptions().map { it.value })
        assertEquals(17, PlayerDesignStyle.METROLIST_KMP.ordinal)
        assertEquals("V4", PlayerDesignStyle.Default.name)
    }

    @Test fun everyStyleHasBaselineGatesAndHiddenEffectsKeepTheirPersistedValues() {
        PlayerDesignStyle.entries.forEach { style ->
            val preferences = PreferenceSnapshot.of(PlayerDesignStyleKey to style.name, TikTokMainLyricsEnabledKey to true,
                AppleMusicPauseScaleKey to false, FlamingoBackgroundEffectKey to true, ShowPlayerVolumeBarKey to false)
            val rows = appearance(preferences)
            val buttons = rows.single { it.anchor == "player_buttons_style" }
            assertEquals(style.name in setOf("V1","V2","V3","V4","V6"), buttons.enabled, style.name)
            assertEquals(if (buttons.enabled) null else Res.string.m6_player_setting_not_used, buttons.description, style.name)
            assertEquals(buttons.enabled, rows.single { it.anchor == "player_slider_style" }.enabled, style.name)
            assertEquals(style.name in setOf("V7","V8","BITCHORD","APPLE_MUSIC","FLAMINGO"),
                rows.single { it.anchor == "show_player_volume_bar" }.enabled, style.name)
            assertEquals(style.name !in setOf("V5","V8","V9"), rows.single { it.anchor == "player_background_style" }.enabled)
            assertEquals(style.name !in setOf("V5","V7","TIKTOK"), rows.single { it.anchor == "hide_player_thumbnail" }.enabled, style.name)
            assertEquals(style == PlayerDesignStyle.TIKTOK, rows.any { it.anchor == "tiktok_main_lyrics" })
            assertEquals(style == PlayerDesignStyle.APPLE_MUSIC, rows.any { it.anchor == "apple_music_pause_scale" })
            assertEquals(style == PlayerDesignStyle.METROLIST_KMP, rows.any { it.anchor == "metrolist_presentation" })
            assertEquals(true, preferences[FlamingoBackgroundEffectKey])
        }
    }

    @Test fun actualBackgroundLibraryAndVolumeHandlersAreAtomicAndKeepExperienceIndependent() {
        val before = PreferenceSnapshot.of(AppleMusicExperienceKey to true, PlayerDesignStyleKey to "FLAMINGO",
            FlamingoShowVolumeBarKey to false)
        var after = before.toEditor().apply {
            (appearance(before).single { it.anchor == "player_background_style" } as SettingsRow.Choice).write(this, "CUSTOM")
        }.snapshot()
        assertEquals("CUSTOM", after[LyricsBackgroundStyleKey])
        assertFalse(appearance(after).single { it.anchor == "lyrics_background_style" }.enabled)
        after = after.toEditor().apply {
            (appearance(after).single { it.anchor == "player_background_style" } as SettingsRow.Choice).write(this, "GRADIENT")
            (appearance(after).single { it.anchor == "library_style" } as SettingsRow.Choice).write(this, "DEFAULT")
            (appearance(after).single { it.anchor == "show_player_volume_bar" } as SettingsRow.Toggle).write(this, true)
        }.snapshot()
        assertEquals("DEFAULT", after[LyricsBackgroundStyleKey])
        assertEquals(true, after[AppleMusicExperienceKey])
        assertEquals("FLAMINGO", after[PlayerDesignStyleKey])
        assertNull(after[FlamingoShowVolumeBarKey])
        assertEquals(true, after[ShowPlayerVolumeBarKey])
        assertFalse(appearance(after).single { it.anchor == "compact_mini_player" }.enabled)
    }

    @Test fun playbackEnginesHistoryAndSponsorHandlersMatchActualBaselinePolicies() {
        var snapshot = PreferenceSnapshot.of(AudioOffload to true, LastwaveAudioProcessingKey to true,
            HISTORY_DURATION_LEGACY_FLOAT_KEY to 12.5f, SponsorBlockEnabledKey to true)
        var rows = playback(snapshot)
        assertFalse(rows.single { it.anchor == "skip_silence" }.enabled)
        val history = rows.single { it.anchor == "history_duration" } as SettingsRow.Number
        assertEquals(13, history.read(snapshot))
        snapshot = snapshot.toEditor().apply {
            history.write(this, 42)
            (rows.single { it.anchor == "tryptify_engine" } as SettingsRow.Toggle).write(this, true)
            (rows.single { it.anchor == "sponsor_block_categories" } as SettingsRow.Toggle).write(this, true)
        }.snapshot()
        assertNull(snapshot[HISTORY_DURATION_LEGACY_FLOAT_KEY])
        assertEquals(42, snapshot[HistoryDuration])
        assertEquals(false, snapshot[AudioOffload])
        assertEquals(false, snapshot[LastwaveAudioProcessingKey])
        assertEquals(setOf("music_offtopic", "sponsor"), snapshot[SponsorBlockCategoriesKey])
        rows = playback(snapshot, active = true)
        listOf("tryptify_engine", "lastwave_engine", "float_dsp").forEach {
            val row = rows.single { row -> row.anchor == it } as SettingsRow.Toggle
            assertFalse(row.enabled); assertFalse(row.read(snapshot))
        }
        assertEquals(true, snapshot[TryptifyAudioProcessingKey])
        assertFalse(rows.single { it.anchor == "bit_perfect_usb" }.enabled)
    }

    @Test fun realActionsUseActualRoutesAndRequiredEditors() {
        val destinations = mutableListOf<String>()
        var home = 0; var artwork = 0; var tags = 0
        val appearance = playerAppearanceSettingsRows(PreferenceSnapshot.of(PlayerBackgroundStyleKey to "CUSTOM"),
            false, destinations::add, { home++ })
        listOf("home_screens", "customized_background", "aod_customize").forEach { id ->
            (appearance.single { it.anchor == id } as SettingsRow.Action).run()
        }
        val playback = playbackSettingsRows(PreferenceSnapshot.EMPTY, false, false, false, destinations::add, { artwork++ }, { tags++ })
        listOf("preferred_sources", "lyrics", "archive_tune_canvas", "artwork_priority", "manage_playlist_tags").forEach { id ->
            (playback.single { it.anchor == id } as SettingsRow.Action).run()
        }
        assertEquals(listOf("customize_background", "settings/appearance/aod_customized", "settings/sources",
            "settings/lyrics", "settings/canvas"), destinations)
        assertEquals(listOf(1,1,1), listOf(home,artwork,tags))
    }

    @Test fun actualEighteenStylePickerAndVintageChoicePersistAcrossDiskRestart() = runBlocking {
        val directory = Files.createTempDirectory("m6-player-family-")
        val path = directory.resolve("settings.bin")
        val store = DesktopSettingsStore.open(path)
        try {
            val controller = SettingsController(store, store.snapshot(), false)
            runComposeUiTest {
                var anchor by mutableStateOf("player_design_style")
                setContent { MaterialTheme {
                    PlayerAppearanceSettings(controller, false, {}, {}, { error("Unexpected edit failure") }, anchor)
                } }
                onNodeWithTag("setting:player_design_style").performClick()
                onNodeWithText("Metrolist KMP").performScrollTo().performClick()
                waitUntil { store.snapshots.value?.get(PlayerDesignStyleKey) == "METROLIST_KMP" }
                runOnIdle { anchor = "metrolist_presentation" }
                onNodeWithTag("setting:metrolist_presentation").performClick()
                onNodeWithText("Vintage").performClick()
                waitUntil { store.snapshots.value?.get(MetrolistPresentationKey) == "VINTAGE" }
                assertFalse(controller.restart.value.visible)
            }
        } finally { store.close() }
        try {
            val reopened = DesktopSettingsStore.open(path)
            try {
                assertEquals("METROLIST_KMP", reopened.snapshot()[PlayerDesignStyleKey])
                assertEquals("VINTAGE", reopened.snapshot()[MetrolistPresentationKey])
            } finally { reopened.close() }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun realUsbCrossfadeAutomixWidgetsCommitExclusionsBeforeReleasingAndPersistPerformance() = runBlocking {
        val directory = Files.createTempDirectory("m6-playback-family-")
        val path = directory.resolve("settings.bin")
        val store = DesktopSettingsStore.open(path)
        var releases = 0
        try {
            store.edit { this[BitPerfectUsbOutputKey] = true; this[TryptifyAudioProcessingKey] = true }
            val controller = SettingsController(store, store.snapshot(), true)
            runComposeUiTest {
                var anchor by mutableStateOf("tryptify_engine")
                var active by mutableStateOf(true)
                setContent { MaterialTheme {
                    PlaybackSettings(controller, true, active, true, {
                        assertEquals(false, store.snapshot()[BitPerfectUsbOutputKey])
                        assertTrue(controller.restart.value.visible)
                        releases++; active = false
                    }, {}, {}, {}, { error("Unexpected storage failure") }, anchor)
                } }
                onNodeWithTag("toggle:tryptify_engine").assertIsNotEnabled().assertIsOff()
                runOnIdle { anchor = "crossfade" }
                onNodeWithTag("toggle:crossfade").performClick()
                waitUntil { releases == 1 }
                assertEquals(true, store.snapshots.value?.get(CrossfadeEnabledKey))
                assertEquals(false, store.snapshots.value?.get(AudioOffload))
                assertEquals(false, store.snapshots.value?.get(UsbExclusiveAudioKey))
                runOnIdle { anchor = "automix" }
                onNodeWithTag("toggle:automix").performClick()
                waitUntil { store.snapshots.value?.get(AutomixEnabledKey) == true }
                assertEquals(false, store.snapshots.value?.get(CrossfadeEnabledKey))
                runOnIdle { anchor = "automix_performance" }
                onNodeWithTag("setting:automix_performance").performClick()
                onNodeWithText("Maximum performance").performClick()
                waitUntil { store.snapshots.value?.get(AutomixPerformanceModeKey) == "PERFORMANCE" }
            }
            assertEquals(1, releases)
        } finally { store.close() }
        try {
            val reopened = DesktopSettingsStore.open(path)
            try {
                assertEquals("PERFORMANCE", reopened.snapshot()[AutomixPerformanceModeKey])
                assertEquals(false, reopened.snapshot()[BitPerfectUsbOutputKey])
                assertEquals(true, reopened.snapshot()[TryptifyAudioProcessingKey])
            } finally { reopened.close() }
        } finally { directory.toFile().deleteRecursively() }
    }

    private fun appearance(snapshot: PreferenceSnapshot) = playerAppearanceSettingsRows(snapshot, false, {}, {})
    private fun playback(snapshot: PreferenceSnapshot, active: Boolean = false) =
        playbackSettingsRows(snapshot, false, active, false, {}, {}, {})
}
