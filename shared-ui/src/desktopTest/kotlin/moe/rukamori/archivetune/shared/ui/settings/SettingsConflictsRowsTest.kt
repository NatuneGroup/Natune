package moe.rukamori.archivetune.shared.ui.settings

import kotlin.test.*
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*

class SettingsConflictsRowsTest {
    @Test fun hostsWithoutACustomFontPickerKeepTheSavedFontAndExplainTheUnavailableAction() {
        val before = PreferenceSnapshot.of(AppFontPreferenceKey to "CUSTOM", CustomFontUriKey to "file:///saved/font.otf")
        val row = appearanceThemeSettingsRows(before, false, false, false, false, {}, {}, {}, {}, {},
            customFontPickerSupported = false).single { it.anchor == "custom_font" }
        assertFalse(row.enabled)
        assertEquals(Res.string.m6_custom_font_picker_unavailable, row.description)
        assertEquals("CUSTOM", before[AppFontPreferenceKey])
    }

    @Test fun paletteAndCacheRowsUseTheSameAtomicRulesAsTheAndroidHost() {
        val before = PreferenceSnapshot.of(DynamicThemeKey to false, AudioNormalizationKey to false)
        val random = appearanceThemeSettingsRows(before, false, false, false, false, {}, {}, {}, {}, {})
            .single { it.anchor == "random_theme_on_startup" } as SettingsRow.Toggle
        val editor = before.toEditor().apply { random.write(this, true) }
        assertEquals(false, editor[DynamicThemeKey])
        assertEquals(true, editor[RandomThemeOnStartupKey])
        editor[MaxImageCacheSizeKey] = 0
        editor[SmartTrimmerKey] = true
        val songLimit = storageSettingsRows(editor.snapshot(), false, {}, {})
            .single { it.anchor == "max_song_cache_size" } as SettingsRow.IntChoice
        songLimit.write(editor, 0)
        assertEquals(false, editor[SmartTrimmerKey])
        val trimmer = storageSettingsRows(editor.snapshot(), false, {}, {}).single { it.anchor == "smart_trimmer" }
        assertFalse(trimmer.enabled)
        assertEquals(Res.string.m6_caches_required, trimmer.description)
        assertEquals(false, editor[AudioNormalizationKey])
    }

    @Test fun unavailableMaterialsStayInThePickerAndTheSavedSelectionIsNotSubstituted() {
        val before = PreferenceSnapshot.of(MiniPlayerBackgroundStyleKey to "LIQUID_GLASS", DisableBlurKey to true)
        val row = playerAppearanceSettingsRows(before, false, {}, {})
            .single { it.anchor == "mini_player_background_style" } as SettingsRow.Choice
        assertEquals("LIQUID_GLASS", row.read(before))
        assertEquals(5, row.options.size)
        val option = row.options.single { it.value == "LIQUID_GLASS" }
        assertFalse(option.enabled)
        assertEquals(Res.string.m6_blur_disabled, option.description)
        val editor = before.toEditor()
        row.write(editor, "FROSTED")
        assertEquals("LIQUID_GLASS", editor[MiniPlayerBackgroundStyleKey])
        row.write(editor, "THEME")
        assertEquals("THEME", editor[MiniPlayerBackgroundStyleKey])
    }

    @Test fun sharedHostDoesNotAdvertiseTheUnportedAndroidV2TuningAsWorking() {
        val rows = lyricsAnimationSettingsRows()
        assertTrue(rows.none { it.enabled })
        assertEquals(Res.string.m6_lyrics_tuning_unavailable, rows.first().description)
    }

    @Test fun sourceSelectorKeepsManualModeWithoutOfferingTheRetiredWebRemixAlias() {
        val manual = sourceSettingsRows(PreferenceSnapshot.of(AutoChoosePlaybackClientKey to false), false, {}, {}, {}, null)
        val choice = manual.single { it.anchor == "player_stream_client" } as SettingsRow.Choice
        assertTrue(choice.enabled)
        assertTrue(choice.options.any { it.value == "WEB_REMIX" })
        assertFalse(choice.options.any { it.value == "HI_RES_LOSSLESS" })
    }
}
