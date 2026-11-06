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
    fun `moved rows resolve to the page that now owns them`() {
        assertEquals(
            "settings/player?scrollTo=show_codec_on_player",
            searchableSettingsRoute("playback", "show_codec_on_player"),
        )
        assertEquals(
            "settings/tidal?scrollTo=tidal_animated_covers",
            searchableSettingsRoute("sources", "tidal_animated_covers"),
        )
        assertEquals(
            "settings/tidal?scrollTo=tidal_artwork_fallback",
            searchableSettingsRoute("sources", "tidal_artwork_fallback"),
        )
        assertEquals(
            "settings/ai_integration?scrollTo=target_language",
            searchableSettingsRoute("ai_integration", "target_language"),
        )
        assertEquals(
            "settings/sources?scrollTo=spotify_stream_enable",
            searchableSettingsRoute("playback", "spotify_stream_enable"),
        )
    }

    @Test
    fun `pages that moved out of appearance keep their own routes`() {
        assertEquals(
            "settings/appearance/extras?scrollTo=show_home_category_chips",
            searchableSettingsRoute("appearance", "show_home_category_chips"),
        )
        // The removed treatment row no longer belongs to the navigation-bar page; the bare anchor falls back to the
        // appearance page, and the search index no longer emits an entry for it.
        assertEquals(
            "settings/appearance?scrollTo=liquid_glass_nav_bar",
            searchableSettingsRoute("appearance", "liquid_glass_nav_bar"),
        )
    }

    @Test
    fun `liquid glass tuning rows open the customisation page at their anchor`() {
        assertEquals(
            "settings/appearance/liquid_glass?scrollTo=glass_blur_radius",
            searchableSettingsRoute(LIQUID_GLASS_SETTINGS_SEARCH_KEY, "glass_blur_radius"),
        )
        assertEquals(LIQUID_GLASS_SETTINGS_ROUTE, searchableSettingsRoute(LIQUID_GLASS_SETTINGS_SEARCH_KEY, null))
    }
}
