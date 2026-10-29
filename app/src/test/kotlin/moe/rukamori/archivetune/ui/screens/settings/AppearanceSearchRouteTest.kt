package moe.rukamori.archivetune.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AppearanceSearchRouteTest {
    @Test
    fun `rows of every former appearance sub-page open the one appearance page at their anchor`() {
        assertEquals("settings/appearance?scrollTo=dynamic_theme", searchableSettingsRoute("appearance", "dynamic_theme"))
        assertEquals(
            "settings/appearance?scrollTo=player_design_style",
            searchableSettingsRoute("appearance", "player_design_style"),
        )
        assertEquals("settings/appearance?scrollTo=tablet_mode", searchableSettingsRoute("appearance", "tablet_mode"))
        assertEquals(
            "settings/appearance?scrollTo=lyrics_background_style",
            searchableSettingsRoute("lyrics", "lyrics_background_style"),
        )
    }

    @Test
    fun `former sub-page owners resolve to the appearance page`() {
        AppearanceSection.entries.forEach { section ->
            assertEquals("settings/appearance", searchableSettingsRoute(section.anchor, null))
        }
    }

    @Test
    fun `section anchors keep the former sub-page keys`() {
        assertEquals(
            listOf("appearance_theme", "appearance_player", "appearance_interface"),
            AppearanceSection.entries.map { it.anchor },
        )
    }

    @Test
    fun `pages that moved out of appearance keep their own routes`() {
        assertEquals(
            "settings/appearance/navigation_bar?scrollTo=liquid_glass_nav_bar",
            searchableSettingsRoute("appearance", "liquid_glass_nav_bar"),
        )
        assertEquals(
            "settings/appearance/extras?scrollTo=show_home_category_chips",
            searchableSettingsRoute("appearance", "show_home_category_chips"),
        )
    }
}
