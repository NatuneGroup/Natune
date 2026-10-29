package moe.rukamori.archivetune.shared.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class NativeShellChromeTest {
    private val song = NativeNowPlaying("occ-1", "Title", "Artist", "https://example.invalid/a.jpg", playing = true)

    @Test fun stateStaysHiddenUntilHostShellAndRootAllAgree() {
        val chrome = NativeShellChrome()
        chrome.publish(NativeShellState(visible = true, selectedTab = ShellTab.Library, nowPlaying = song))
        assertFalse(chrome.state.value.visible)
        chrome.setAvailable(true)
        assertTrue(chrome.active.value)
        assertFalse(chrome.state.value.visible)
        chrome.reportShellYields(true)
        assertTrue(chrome.state.value.visible)
        assertEquals(ShellTab.Library, chrome.state.value.selectedTab)
        assertEquals(song, chrome.state.value.nowPlaying)
    }

    @Test fun anyGateClosingHidesTheChromeAgain() {
        val chrome = NativeShellChrome()
        chrome.setAvailable(true)
        chrome.reportShellYields(true)
        chrome.publish(NativeShellState(visible = true))
        assertTrue(chrome.state.value.visible)

        chrome.publish(NativeShellState(visible = false))
        assertFalse(chrome.state.value.visible)
        chrome.publish(NativeShellState(visible = true))
        chrome.reportShellYields(false)
        assertFalse(chrome.state.value.visible)
        chrome.reportShellYields(true)
        chrome.setAvailable(false)
        assertFalse(chrome.state.value.visible)
        assertFalse(chrome.active.value)
    }

    @Test fun badInsetReportsAreIgnoredAndLargeOnesClamped() {
        val chrome = NativeShellChrome()
        chrome.setBottomInset(132f)
        assertEquals(132f, chrome.bottomInsetDp.value)
        chrome.setBottomInset(Float.NaN)
        chrome.setBottomInset(-4f)
        chrome.setBottomInset(Float.POSITIVE_INFINITY)
        assertEquals(132f, chrome.bottomInsetDp.value)
        chrome.setBottomInset(5_000f)
        assertEquals(NativeShellChrome.MAX_INSET_DP, chrome.bottomInsetDp.value)
    }

    @Test fun commandsArriveInOrderAndABurstKeepsTheNewest() = runTest {
        val chrome = NativeShellChrome()
        assertTrue(chrome.send(NativeShellCommand.SelectTab(ShellTab.Search)))
        assertEquals(NativeShellCommand.SelectTab(ShellTab.Search), chrome.commands.first())

        repeat(NativeShellChrome.COMMAND_CAPACITY) { chrome.send(NativeShellCommand.TogglePlayback) }
        chrome.send(NativeShellCommand.ExpandPlayer)
        val drained = chrome.commands.take(NativeShellChrome.COMMAND_CAPACITY).toList()
        assertEquals(NativeShellChrome.COMMAND_CAPACITY, drained.size)
        assertEquals(NativeShellCommand.ExpandPlayer, drained.last())
    }

    @Test fun onlyTheBottomLayoutsAreReplaced() {
        assertTrue(ShellLayout.BottomBar.nativeChromeReplaces())
        assertTrue(ShellLayout.CompactRow.nativeChromeReplaces())
        assertTrue(ShellLayout.MiniPlayerOnly.nativeChromeReplaces())
        assertFalse(ShellLayout.Rail.nativeChromeReplaces())
        assertFalse(ShellLayout.TvRail.nativeChromeReplaces())
        assertFalse(ShellLayout.Hidden.nativeChromeReplaces())
    }
}
