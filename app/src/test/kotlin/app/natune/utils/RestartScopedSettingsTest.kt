/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package app.natune.utils

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import app.natune.constants.AppFontPreference
import app.natune.constants.CustomFontUriKey
import app.natune.constants.FontPreferenceKey
import app.natune.constants.LiquidGlassEnabledKey
import app.natune.constants.LiquidGlassNavBarEnabledKey
import app.natune.constants.NavigationBarFrostedBlurKey
import app.natune.constants.NavigationBarTintFrostedBlurKey
import app.natune.constants.TabletModeEnabledKey
import app.natune.constants.UiScaleFactorKey
import app.natune.constants.UseSystemFontKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The restart-to-apply latch: the running UI keeps the startup values, and a restart is asked for
 * only when a stored restart-scoped value really differs from them.
 */
class RestartScopedSettingsTest {
    @Test
    fun from_nullAndEmpty_giveDefaults() {
        assertEquals(RestartScopedSettings.Defaults, RestartScopedSettings.from(null))
        assertEquals(RestartScopedSettings.Defaults, RestartScopedSettings.from(emptyPreferences()))
    }

    @Test
    fun from_clampsUiScale() {
        assertEquals(
            RestartScopedSettings.UI_SCALE_MIN,
            RestartScopedSettings.from(mutablePreferencesOf(UiScaleFactorKey to 0.2f)).uiScale,
            0f,
        )
        assertEquals(
            RestartScopedSettings.UI_SCALE_MAX,
            RestartScopedSettings.from(mutablePreferencesOf(UiScaleFactorKey to 3f)).uiScale,
            0f,
        )
        assertEquals(1.1f, RestartScopedSettings.from(mutablePreferencesOf(UiScaleFactorKey to 1.1f)).uiScale, 0f)
    }

    @Test
    fun from_readsEveryKey() {
        val settings =
            RestartScopedSettings.from(
                mutablePreferencesOf(
                    FontPreferenceKey to AppFontPreference.CUSTOM.name,
                    CustomFontUriKey to "content://font",
                    TabletModeEnabledKey to true,
                    LiquidGlassEnabledKey to true,
                    LiquidGlassNavBarEnabledKey to true,
                    NavigationBarFrostedBlurKey to true,
                    NavigationBarTintFrostedBlurKey to true,
                ),
            )
        assertEquals(AppFontPreference.CUSTOM, settings.fontPreference)
        assertEquals("content://font", settings.customFontUri)
        assertTrue(settings.tabletModeEnabled)
        assertTrue(settings.liquidGlassEnabled)
        assertTrue(settings.liquidGlassNavBarEnabled)
        assertFalse(settings.navigationBarFrostedBlur)
        assertFalse(settings.navigationBarTintFrostedBlur)
    }

    @Test
    fun from_legacySystemFontWithoutStoredPreference_isSystem() {
        assertEquals(
            AppFontPreference.SYSTEM,
            RestartScopedSettings.from(mutablePreferencesOf(UseSystemFontKey to true)).fontPreference,
        )
    }

    @Test
    fun resolveFontPreference_storedWinsOverLegacy() {
        assertEquals(
            AppFontPreference.DEFAULT,
            RestartScopedSettings.resolveFontPreference(AppFontPreference.DEFAULT.name, legacyUseSystemFont = true),
        )
        assertEquals(
            AppFontPreference.CUSTOM,
            RestartScopedSettings.resolveFontPreference(AppFontPreference.CUSTOM.name, legacyUseSystemFont = null),
        )
    }

    @Test
    fun resolveFontPreference_fallbacks() {
        assertEquals(AppFontPreference.SYSTEM, RestartScopedSettings.resolveFontPreference(null, legacyUseSystemFont = true))
        assertEquals(AppFontPreference.DEFAULT, RestartScopedSettings.resolveFontPreference(null, legacyUseSystemFont = false))
        assertEquals(AppFontPreference.DEFAULT, RestartScopedSettings.resolveFontPreference(null, legacyUseSystemFont = null))
        assertEquals(AppFontPreference.DEFAULT, RestartScopedSettings.resolveFontPreference("NOT_A_FONT", legacyUseSystemFont = null))
    }

    @Test
    fun legacyMigration_doesNotReadAsPendingRestart() {
        val running = RestartScopedSettings.from(mutablePreferencesOf(UseSystemFontKey to true))
        val migrated =
            RestartScopedSettings.from(
                mutablePreferencesOf(UseSystemFontKey to true, FontPreferenceKey to AppFontPreference.SYSTEM.name),
            )
        assertFalse(PendingRestart.between(running, migrated).any)
    }

    @Test
    fun between_identical_nothingPending() {
        val defaults = RestartScopedSettings.Defaults
        assertFalse(PendingRestart.between(defaults, defaults.copy()).any)
    }

    @Test
    fun between_eachChangeFlagsItsOwnField() {
        val running = RestartScopedSettings.Defaults

        val scale = PendingRestart.between(running, running.copy(uiScale = 1.2f))
        assertTrue(scale.uiScale)
        assertTrue(scale.any)
        assertFalse(scale.font || scale.tabletMode || scale.liquidGlass || scale.navigationBarGlass)

        assertTrue(PendingRestart.between(running, running.copy(fontPreference = AppFontPreference.SYSTEM)).font)
        assertTrue(PendingRestart.between(running, running.copy(tabletModeEnabled = true)).tabletMode)
        assertTrue(PendingRestart.between(running, running.copy(liquidGlassEnabled = true)).liquidGlass)
        assertTrue(PendingRestart.between(running, running.copy(liquidGlassNavBarEnabled = true)).navigationBarGlass)
        assertTrue(PendingRestart.between(running, running.copy(navigationBarFrostedBlur = true)).navigationBarGlass)
        assertTrue(PendingRestart.between(running, running.copy(navigationBarTintFrostedBlur = true)).navigationBarGlass)
    }

    @Test
    fun between_customFontUriMattersOnlyForCustomFont() {
        val custom = RestartScopedSettings.Defaults.copy(fontPreference = AppFontPreference.CUSTOM, customFontUri = "a")
        assertTrue(PendingRestart.between(custom, custom.copy(customFontUri = "b")).font)

        val system = RestartScopedSettings.Defaults.copy(fontPreference = AppFontPreference.SYSTEM, customFontUri = "a")
        assertFalse(PendingRestart.between(system, system.copy(customFontUri = "b")).font)
    }
}
