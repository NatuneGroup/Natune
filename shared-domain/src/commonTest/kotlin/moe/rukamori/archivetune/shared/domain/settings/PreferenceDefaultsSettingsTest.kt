/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.domain.settings

import moe.rukamori.archivetune.shared.domain.library.LibraryDefaults
import moe.rukamori.archivetune.shared.domain.library.LibrarySource
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the default values the shared policies assume. The V4 player and the Material 3 Expressive interface are what a
 * fresh install shows, and the persisted enum names and order are what stored preferences decode against.
 */
class PreferenceDefaultsSettingsTest {
    @Test
    fun theDefaultPlayerDesignIsV4() {
        assertEquals(PlayerDesignStyle.V4, PreferenceDefaults.playerDesignStyle)
        assertEquals("V4", PlayerDesignStyle.Default.name)
        assertEquals(PlayerDesignStyle.V4, PreferenceSnapshot.EMPTY[PlayerDesignStyleKey].toEnum(PlayerDesignStyle.Default))
    }

    @Test
    fun materialExpressiveIsTheDefaultInterfaceStyle() {
        assertEquals(InterfaceStyle.MATERIAL_EXPRESSIVE, PreferenceDefaults.interfaceStyle)
        assertEquals(InterfaceStyle.MATERIAL_EXPRESSIVE, PreferenceSnapshot.EMPTY.interfaceStyle())
    }

    @Test
    fun appleMusicExperienceIsOffUntilChosen() {
        assertEquals(false, PreferenceDefaults.appleMusicExperience)
        assertEquals(false, PreferenceSnapshot.EMPTY[AppleMusicExperienceKey, PreferenceDefaults.appleMusicExperience])
    }

    @Test
    fun theLibraryDefaultsToItsOwnLayoutAndYtmSource() {
        assertEquals(LibraryStyle.DEFAULT, PreferenceDefaults.libraryStyle)
        assertEquals(LibrarySource.YTM, LibraryDefaults.source)
        assertEquals(LibraryStyle.DEFAULT, PreferenceSnapshot.EMPTY.libraryStyle())
    }

    @Test
    fun presentationEnumsKeepTheirPersistedNamesAndOrder() {
        assertEquals(
            listOf(
                "V1", "V2", "V3", "V4", "V5", "V6", "V7", "V8", "V9",
                "APPLE_MUSIC", "V10", "BITCHORD", "TIKTOK", "SIMPMUSIC", "SPATIALFLOW", "LOOPER", "FLAMINGO",
                "METROLIST_KMP",
            ),
            PlayerDesignStyle.entries.map { it.name },
        )
        assertEquals(listOf("MATERIAL_EXPRESSIVE", "APPLE_MUSIC"), InterfaceStyle.entries.map { it.name })
        assertEquals(listOf("DEFAULT", "APPLE_MUSIC"), LibraryStyle.entries.map { it.name })
        assertEquals(listOf("DEFAULT", "SYSTEM", "CUSTOM"), AppFontPreference.entries.map { it.name })
    }

    @Test
    fun unknownOrDifferentlyCasedStoredNamesFallBackToTheDefault() {
        assertEquals(PlayerDesignStyle.V4, "removed-style".toEnum(PlayerDesignStyle.Default))
        assertEquals(PlayerDesignStyle.V4, "tiktok".toEnum(PlayerDesignStyle.Default))
        assertEquals(PlayerDesignStyle.V4, null.toEnum(PlayerDesignStyle.Default))
        assertEquals(PlayerDesignStyle.TIKTOK, "TIKTOK".toEnum(PlayerDesignStyle.Default))
    }
}
