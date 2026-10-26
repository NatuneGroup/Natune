package moe.rukamori.archivetune.shared.domain.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The restart-to-apply latch: the running UI keeps the startup values, and a restart is asked for only when a stored
 * restart-scoped value really differs from them.
 */
class RestartScopedSettingsTest {
    @Test
    fun missingAndEmptySnapshotsGiveDefaults() {
        assertEquals(RestartScopedSettings.Defaults, RestartScopedSettings.from(null))
        assertEquals(RestartScopedSettings.Defaults, RestartScopedSettings.from(PreferenceSnapshot.EMPTY))
    }

    @Test
    fun defaultsMatchTheShippedStartupValues() {
        val defaults = RestartScopedSettings.Defaults

        assertEquals(1.0f, defaults.uiScale)
        assertEquals(AppFontPreference.DEFAULT, defaults.fontPreference)
        assertEquals("", defaults.customFontUri)
        assertFalse(defaults.tabletModeEnabled)
        assertFalse(defaults.liquidGlassEnabled)
        assertFalse(defaults.liquidGlassNavBarEnabled)
        assertFalse(defaults.navigationBarFrostedBlur)
        assertFalse(defaults.navigationBarTintFrostedBlur)
    }

    @Test
    fun uiScaleIsClampedToItsRange() {
        assertEquals(
            RestartScopedSettings.UI_SCALE_MIN,
            RestartScopedSettings.from(PreferenceSnapshot.of(UiScaleFactorKey to 0.2f)).uiScale,
        )
        assertEquals(
            RestartScopedSettings.UI_SCALE_MAX,
            RestartScopedSettings.from(PreferenceSnapshot.of(UiScaleFactorKey to 3f)).uiScale,
        )
        assertEquals(1.1f, RestartScopedSettings.from(PreferenceSnapshot.of(UiScaleFactorKey to 1.1f)).uiScale)
    }

    @Test
    fun fromReadsEveryRestartScopedKey() {
        val settings =
            RestartScopedSettings.from(
                PreferenceSnapshot.of(
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
        assertTrue(settings.navigationBarFrostedBlur)
        assertTrue(settings.navigationBarTintFrostedBlur)
    }

    @Test
    fun legacySystemFontWithoutAStoredPreferenceIsSystem() {
        assertEquals(
            AppFontPreference.SYSTEM,
            RestartScopedSettings.from(PreferenceSnapshot.of(UseSystemFontKey to true)).fontPreference,
        )
    }

    @Test
    fun aStoredFontPreferenceWinsOverTheLegacySwitch() {
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
    fun fontFallbacksWithoutAStoredPreference() {
        assertEquals(AppFontPreference.SYSTEM, RestartScopedSettings.resolveFontPreference(null, legacyUseSystemFont = true))
        assertEquals(AppFontPreference.DEFAULT, RestartScopedSettings.resolveFontPreference(null, legacyUseSystemFont = false))
        assertEquals(AppFontPreference.DEFAULT, RestartScopedSettings.resolveFontPreference(null, legacyUseSystemFont = null))
        assertEquals(AppFontPreference.DEFAULT, RestartScopedSettings.resolveFontPreference("NOT_A_FONT", legacyUseSystemFont = null))
    }

    @Test
    fun legacyFontMigrationDoesNotReadAsPendingRestart() {
        val running = RestartScopedSettings.from(PreferenceSnapshot.of(UseSystemFontKey to true))
        val migrated =
            RestartScopedSettings.from(
                PreferenceSnapshot.of(
                    UseSystemFontKey to true,
                    FontPreferenceKey to AppFontPreference.SYSTEM.name,
                ),
            )

        assertFalse(PendingRestart.between(running, migrated).any)
    }

    @Test
    fun identicalSettingsHaveNothingPending() {
        val defaults = RestartScopedSettings.Defaults

        assertFalse(PendingRestart.between(defaults, defaults.copy()).any)
    }

    @Test
    fun eachChangeFlagsItsOwnField() {
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
    fun customFontUriMattersOnlyForTheCustomFont() {
        val custom = RestartScopedSettings.Defaults.copy(fontPreference = AppFontPreference.CUSTOM, customFontUri = "a")
        assertTrue(PendingRestart.between(custom, custom.copy(customFontUri = "b")).font)

        val system = RestartScopedSettings.Defaults.copy(fontPreference = AppFontPreference.SYSTEM, customFontUri = "a")
        assertFalse(PendingRestart.between(system, system.copy(customFontUri = "b")).font)
    }
}
