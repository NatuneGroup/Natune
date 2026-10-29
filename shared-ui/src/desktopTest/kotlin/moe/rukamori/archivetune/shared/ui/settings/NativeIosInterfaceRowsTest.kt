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

    @Test fun iosReplacesTheGlassRowsWithTheNativeToggleDefaultingOn() {
        val before = PreferenceSnapshot.of(LiquidGlassEnabledKey to true)
        val list = rows(before, native = true)
        val anchors = list.map { it.anchor }
        assertFalse("liquid_glass_effects" in anchors)
        assertFalse("liquid_glass_nav_bar_link" in anchors)
        val toggle = list.single { it.anchor == "native_ios_interface" } as SettingsRow.Toggle
        assertEquals(NativeIosInterfaceKey, toggle.key)
        assertEquals(Res.string.ios_native_interface_desc, toggle.description)
        assertTrue(toggle.read(before))
        val editor = before.toEditor().apply { toggle.write(this, false) }
        assertEquals(false, editor[NativeIosInterfaceKey])
        assertEquals(true, editor[LiquidGlassEnabledKey])
    }

    @Test fun searchLandsOnTheAppearancePageRow() {
        assertEquals("settings/appearance?scrollTo=native_ios_interface",
            settingsSearchRoute("appearance", "native_ios_interface"))
    }
}
