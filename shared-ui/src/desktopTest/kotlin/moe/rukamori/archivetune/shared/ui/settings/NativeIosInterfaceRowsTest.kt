package moe.rukamori.archivetune.shared.ui.settings

import kotlin.test.*
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*

class NativeIosInterfaceRowsTest {
    private fun rows(preferences: PreferenceSnapshot, native: Boolean) =
        appearanceThemeSettingsRows(preferences, false, false, false, false, {}, {}, {}, {}, {},
            nativeIosInterface = native)

    @Test fun otherHostsKeepTheSharedGlassRows() {
        val anchors = rows(PreferenceSnapshot.of(), native = false).map { it.anchor }
        assertTrue("liquid_glass_effects" in anchors)
        assertTrue("liquid_glass_nav_bar_link" in anchors)
        assertFalse("native_ios_interface" in anchors)
    }

    @Test fun iosShowsNeitherGlassNorADeadNativeInterfaceToggle() {
        // The iOS host draws the app in the shared Compose tree, so it has no refraction to toggle and no second
        // interface to switch to; the stored glass choices stay untouched for the other hosts.
        val before = PreferenceSnapshot.of(LiquidGlassEnabledKey to true)
        val anchors = rows(before, native = true).map { it.anchor }
        assertFalse("liquid_glass_effects" in anchors)
        assertFalse("liquid_glass_nav_bar_link" in anchors)
        assertFalse("native_ios_interface" in anchors)
    }
}
