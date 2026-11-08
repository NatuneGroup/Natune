package moe.rukamori.archivetune.shared.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.ui.player.PlayerInteractionState
import moe.rukamori.archivetune.shared.ui.player.PlayerPanel

class PlayerBackStackTest {
    @Test fun dismissesInnerPanelsThenPlayerBeforePoppingTheUnderlyingSearchRoute() {
        val navigation = ShellNavigation(ShellTab.Search)
        navigation.open("search/example")
        val route = navigation.entry
        val player = PlayerInteractionState().apply {
            panel = PlayerPanel.QUEUE
            fullscreenLyrics = true
        }
        var expanded = true
        fun back() {
            if (expanded) player.backOrCollapse { expanded = false } else navigation.back()
        }
        back()
        assertFalse(player.fullscreenLyrics)
        assertEquals(PlayerPanel.QUEUE, player.panel)
        back()
        assertEquals(PlayerPanel.ARTWORK, player.panel)
        assertTrue(expanded)
        back()
        assertFalse(expanded)
        assertEquals(route, navigation.entry)
        back()
        assertEquals("search", navigation.route)
        assertFalse(navigation.back())
    }

    @Test fun tabStacksSurvivePlayerDismissalAndNavigationRestore() {
        val navigation = ShellNavigation()
        navigation.open("settings")
        navigation.select(ShellTab.Search)
        navigation.open("search/example")
        val restored = ShellNavigation.restore(navigation.snapshot())
        assertTrue(restored.back())
        restored.select(ShellTab.Home)
        assertEquals("settings", restored.route)
        assertTrue(restored.back())
        assertEquals("home", restored.route)
    }
}
