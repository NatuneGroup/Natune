package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.domain.settings.*
import kotlin.test.*

class AppearancePageTest {
    private fun rows(preferences: PreferenceSnapshot = PreferenceSnapshot.EMPTY) = appearanceSettingsRows(
        preferences, systemDark = false, lowRam = false, nativeDynamicColors = false, wallpaperPermissionNeeded = false,
        blurSupported = true, navigate = {}, openWallpaperSettings = {}, chooseInterface = {}, chooseFont = {},
        pickCustomFont = {}, chooseHomeScreens = {}, arrangeLibraryChips = {}, arrangePlaylistTags = {},
    )

    @Test fun onePageCarriesEverySectionRowUnderItsHeadingInOrder() {
        val preferences = PreferenceSnapshot.EMPTY
        val theme = appearanceThemeSettingsRows(preferences, false, false, false, false, {}, {}, {}, {}, {}).map { it.anchor }
        val player = playerAppearanceSettingsRows(preferences, true, {}, {}).map { it.anchor }
        val ui = appearanceInterfaceSettingsRows({}, {}, {}).map { it.anchor }
        assertEquals(
            listOf("appearance_theme") + theme + "appearance_player" + player + "appearance_interface" + ui,
            rows(preferences).map { it.anchor },
        )
        listOf("appearance_theme", "appearance_player", "appearance_interface").forEach { anchor ->
            assertTrue(rows(preferences).single { it.anchor == anchor } is SettingsRow.Header)
        }
    }

    @Test fun anchorsStayUniqueSoScrollTargetsAndLazyKeysCannotCollide() {
        val stateful = PreferenceSnapshot.of(PlayerDesignStyleKey to "APPLE_MUSIC", PlayerBackgroundStyleKey to "CUSTOM",
            DynamicThemeKey to false, DarkModeKey to "ON", FontPreferenceKey to "CUSTOM")
        listOf(PreferenceSnapshot.EMPTY, stateful).forEach { preferences ->
            val anchors = rows(preferences).map { it.anchor }
            assertEquals(anchors.size, anchors.distinct().size)
        }
    }

    @Test fun formerSubPageRoutesOpenTheirSectionAndAnExplicitAnchorStillWins() {
        assertEquals("appearance_theme", appearanceScrollTarget("settings/appearance/theme", null))
        assertEquals("appearance_player", appearanceScrollTarget("settings/appearance/player", null))
        assertEquals("appearance_interface", appearanceScrollTarget("settings/appearance/interface", null))
        assertEquals("dark_theme", appearanceScrollTarget("settings/appearance/theme", "dark_theme"))
        assertNull(appearanceScrollTarget(AppearanceRoute, null))
    }

    @Test fun searchResultsFromEverySectionOpenTheOnePage() {
        assertEquals("settings/appearance?scrollTo=dynamic_theme", settingsSearchRoute("appearance", "dynamic_theme"))
        assertEquals("settings/appearance?scrollTo=player_design_style", settingsSearchRoute("appearance", "player_design_style"))
        assertEquals("settings/appearance?scrollTo=tablet_mode", settingsSearchRoute("appearance", "tablet_mode"))
        assertEquals("settings/appearance?scrollTo=lyrics_background_style", settingsSearchRoute("lyrics", "lyrics_background_style"))
        assertEquals("settings/appearance/navigation_bar?scrollTo=liquid_glass_nav_bar",
            settingsSearchRoute("appearance", "liquid_glass_nav_bar"))
    }
}
