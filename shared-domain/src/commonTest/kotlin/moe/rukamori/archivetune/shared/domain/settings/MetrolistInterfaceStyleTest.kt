/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared.domain.settings

import kotlin.test.*

class MetrolistInterfaceStyleTest {
    @Test fun newStyleIsOnlyAnAppend() {
        assertEquals("METROLIST_KMP", PlayerDesignStyle.entries[17].name)
        assertEquals(18, PlayerDesignStyle.entries.size)
        assertEquals(PlayerDesignStyle.V4, PlayerDesignStyle.Default)
        for (style in PlayerDesignStyle.entries) assertEquals(style, style.name.toEnum(PlayerDesignStyle.Default))
    }

    @Test fun appleRoundTripRestoresMetrolistAndReselectDoesNotOverwriteIt() {
        val metrolist = PlayerDesignStyle.valueOf("METROLIST_KMP")
        val editor = PreferenceSnapshot.of(PlayerDesignStyleKey to metrolist.name).toEditor()
        editor.applyInterfaceStyle(InterfaceStyle.APPLE_MUSIC)
        editor.applyInterfaceStyle(InterfaceStyle.APPLE_MUSIC)
        assertEquals(metrolist.name, editor[StyleBeforeAppleMusicKey])
        editor.applyInterfaceStyle(InterfaceStyle.MATERIAL_EXPRESSIVE)
        assertEquals(metrolist.name, editor[PlayerDesignStyleKey])
    }

    @Test fun manualMetrolistSelectionWhileAppleEnabledWins() {
        val editor = PreferenceSnapshot.EMPTY.toEditor()
        editor.applyInterfaceStyle(InterfaceStyle.APPLE_MUSIC)
        editor[PlayerDesignStyleKey] = "METROLIST_KMP"
        editor.applyInterfaceStyle(InterfaceStyle.MATERIAL_EXPRESSIVE)
        assertEquals("METROLIST_KMP", editor[PlayerDesignStyleKey])
    }
}
