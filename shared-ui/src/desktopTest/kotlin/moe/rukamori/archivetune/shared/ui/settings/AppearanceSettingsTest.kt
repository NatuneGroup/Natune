package moe.rukamori.archivetune.shared.ui.settings

import java.nio.file.Files
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.semantics.SemanticsActions
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.shared.domain.settings.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class AppearanceSettingsTest {
    @Test fun individualAppleControlsNeverApplyThePresetAndReportMixedStatus() {
        val rows = appearanceThemeSettingsRows(PreferenceSnapshot.EMPTY, false, false, false, false, {}, {}, {}, {}, {})
        listOf("apple_music_menus", "apple_music_search_bar", "apple_music_palette").forEach { anchor ->
            val toggle = rows.single { it.anchor == anchor } as SettingsRow.Toggle
            val editor = PreferenceSnapshot.EMPTY.toEditor()
            toggle.write(editor, true)
            val result = editor.snapshot()
            assertEquals(setOf(toggle.key), result.keys)
            assertEquals(InterfaceStyle.CUSTOM, result.interfaceStyle())
            assertNull(result[AppleMusicExperienceKey])
        }
        val preset = PreferenceSnapshot.EMPTY.toEditor().apply { applyInterfaceStyle(InterfaceStyle.APPLE_MUSIC) }
        val playerRows = playerAppearanceSettingsRows(preset.snapshot(), true, {}, {})
        assertTrue(playerRows.single { it.anchor == "compact_mini_player" }.enabled)
    }

    @Test fun aodVisibilityAndTimingStayWhileRemovedLayoutRowsKeepStoredValues() {
        val defaults = aodSettingsRows(PreferenceSnapshot.EMPTY)
        assertEquals(11, defaults.size)
        val removed = setOf(
            "aod_thumbnail", "aod_customize_background_style", "aod_customize_accent_style",
            "aod_customize_content_position", "aod_customize_text_alignment", "aod_customize_title_max_lines",
            "aod_customize_slider_style", "aod_customize_shape_rotation", "aod_customize_horizontal_padding",
            "aod_customize_vertical_spacing", "aod_customize_ambient_intensity", "aod_customize_artwork_glow",
            "aod_customize_control_style", "aod_customize_control_size",
        )
        assertTrue(defaults.none { it.anchor in removed })
        val preferences = PreferenceSnapshot.of(
            AodShowProgressKey to false, AodShowTimeLabelsKey to true, AodBackgroundStyleKey to "AMBIENT_GLOW",
        )
        val hidden = aodSettingsRows(preferences)
        assertFalse(hidden.any { it.anchor == "aod_customize_show_time_labels" })
        // The removed rows left their stored meanings alone; the AOD screen still renders from them.
        assertEquals("AMBIENT_GLOW", preferences[AodBackgroundStyleKey])
        assertEquals(true, preferences[AodShowTimeLabelsKey])
        assertTrue(hidden.any { it.anchor == "aod_customize_thumbnail_size" })
        assertTrue(hidden.any { it.anchor == "aod_customize_auto_timer" })
    }

    @Test fun themeCapabilitiesGateBlurWithoutResettingStoredValues() {
        val preferences = PreferenceSnapshot.of(DisableBlurKey to true, LiquidGlassEnabledKey to true,
            BackdropEnabledKey to true, BlurRadiusKey to 55f)
        val rows = appearanceThemeSettingsRows(preferences, false, true, true, false, {}, {}, {}, {}, {})
        listOf("liquid_glass_effects", "blur_intensity", "backdrop_blur", "backdrop_blur_amount").forEach { anchor ->
            assertFalse(rows.single { it.anchor == anchor }.enabled)
        }
        assertTrue((rows.single { it.anchor == "disable_animations" } as SettingsRow.Toggle).default)
        assertFalse(rows.any { it.anchor == "pure_black" || it.anchor == "palette_picker" || it.anchor == "random_theme_on_startup" })
        assertEquals(true, preferences[LiquidGlassEnabledKey])
        assertEquals(55f, preferences[BlurRadiusKey])
        assertEquals(13, appearanceInterfaceSettingsRows({}, {}, {}).size)
        assertEquals(7, appearanceExtrasSettingsRows().size)
    }

    @Test fun interfacePickerIsConnectedToAtomicAppleEditOnDisk() = runBlocking {
        val directory = Files.createTempDirectory("m6-theme-ui-")
        val store = DesktopSettingsStore.open(directory.resolve("settings.bin"))
        try {
            store.edit { this[PlayerDesignStyleKey] = "FLAMINGO" }
            val controller = SettingsController(store, store.snapshot(), false)
            runComposeUiTest {
                setContent {
                    MaterialTheme {
                        AppearanceThemeSettings(controller, false, false, false, false, {}, {},
                            pickCustomFont = { error("Not a font action") }, onFailure = { error("Edit failed") },
                            scrollTo = "interface_style")
                    }
                }
                onNodeWithTag("setting:interface_style").performClick()
                onNodeWithText("Apple Music").performClick()
                waitUntil { store.snapshots.value?.get(AppleMusicExperienceKey) == true }
                assertEquals("APPLE_MUSIC", store.snapshots.value?.get(PlayerDesignStyleKey))
                assertEquals("APPLE_MUSIC", store.snapshots.value?.get(LibraryStyleKey))
                assertEquals("FLAMINGO", store.snapshots.value?.get(StyleBeforeAppleMusicKey))
                assertFalse(controller.restart.value.visible)
            }
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun actualScaleSliderCommitsRoundedWireValueAndUsesSharedRestartPolicy() = runBlocking {
        val directory = Files.createTempDirectory("m6-scale-ui-")
        val store = DesktopSettingsStore.open(directory.resolve("settings.bin"))
        val controller = SettingsController(store, store.snapshot(), false)
        try {
            runComposeUiTest {
                setContent { MaterialTheme {
                    AppearanceThemeSettings(controller, false, false, false, false, {}, {}, { null },
                        { error("Edit failed") }, scrollTo = "ui_scale")
                } }
                onNodeWithTag("setting:ui_scale").performClick()
                onNodeWithTag("settings-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(1.20001f) }
                waitUntil { store.snapshots.value?.get(UiScaleFactorKey) == 1.2f }
                assertTrue(controller.restart.value.visible)
                onNodeWithText("Done").performClick()
            }
            controller.later()
            assertEquals(1.2f, store.snapshot()[UiScaleFactorKey])
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }
}
