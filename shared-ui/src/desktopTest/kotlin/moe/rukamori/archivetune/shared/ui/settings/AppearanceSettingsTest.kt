/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.settings

import java.nio.file.Files
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.semantics.SemanticsActions
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.shared.domain.settings.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class AppearanceSettingsTest {
    @Test fun aodControlsKeepAllWireShapesAndHideOnlyTheTimeLabelChild() {
        val defaults = aodSettingsRows(PreferenceSnapshot.EMPTY)
        assertEquals(25, defaults.size)
        val shapes = defaults.single { it.anchor == "aod_thumbnail" } as SettingsRow.Choice
        assertEquals(listOf("ROUNDED", "SQUARE", "CIRCLE", "PILL", "ARCH", "SLANTED", "DIAMOND",
            "PENTAGON", "TRIANGLE", "HEART", "FLOWER", "CLOVER_4", "COOKIE_6", "COOKIE_9", "SUNNY",
            "SOFT_BURST", "GHOSTISH", "PIXEL_CIRCLE"), shapes.options.map { it.value })
        val preferences = PreferenceSnapshot.of(AodShowProgressKey to false, AodShowTimeLabelsKey to true,
            AodShowControlsKey to false, AodControlSizeKey to 80f)
        val hidden = aodSettingsRows(preferences)
        assertFalse(hidden.any { it.anchor == "aod_customize_show_time_labels" })
        assertFalse(hidden.single { it.anchor == "aod_customize_slider_style" }.enabled)
        assertFalse(hidden.single { it.anchor == "aod_customize_control_size" }.enabled)
        assertEquals(80f, preferences[AodControlSizeKey])
        assertEquals(true, preferences[AodShowTimeLabelsKey])
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

    @Test fun aodPickerWritesActualDiskWireValueNotCaption() = runBlocking {
        val directory = Files.createTempDirectory("m6-aod-ui-")
        val file = directory.resolve("settings.bin")
        val store = DesktopSettingsStore.open(file)
        try {
            val controller = SettingsController(store, store.snapshot(), false)
            runComposeUiTest {
                setContent {
                    val preferences = requireNotNull(store.snapshots.collectAsState().value)
                    MaterialTheme {
                        SettingsPage(aodSettingsRows(preferences), preferences, controller,
                            scrollTo = "aod_customize_background_style", onFailure = { error("Disk edit failed") })
                    }
                }
                onNodeWithTag("setting:aod_customize_background_style").performClick()
                onNodeWithText("Ambient glow").performClick()
                waitUntil { store.snapshots.value?.get(AodBackgroundStyleKey) == "AMBIENT_GLOW" }
            }
        } finally { store.close() }
        try {
            val reopened = DesktopSettingsStore.open(file)
            try { assertEquals("AMBIENT_GLOW", reopened.snapshot()[AodBackgroundStyleKey]) }
            finally { reopened.close() }
        } finally { directory.toFile().deleteRecursively() }
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
