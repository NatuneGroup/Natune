package moe.rukamori.archivetune.shared.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.glass.*
import kotlin.test.*
import androidx.compose.runtime.setValue

@OptIn(ExperimentalTestApi::class)
class ShellComposeTest {
    @Test fun scrollingViewportExtendsUnderChromeButLastItemCanClearIt() = runComposeUiTest {
        val nav = ShellNavigation().apply { select(ShellTab.Library) }
        val prefs = PreferenceSnapshot.of(LiquidGlassEnabledKey to true, LiquidGlassNavBarEnabledKey to true).toEditor()
            .apply { applyInterfaceStyle(InterfaceStyle.APPLE_MUSIC) }.snapshot()
        setContent {
            MaterialTheme {
                ProvideBackdropSources(OpticalPolicy(true, false, false, true)) { source ->
                    Box(Modifier.size(600.dp, 720.dp)) {
                        ArchiveTuneShell(nav, prefs, {}, contentModifier = source, miniPlayer = { Text("Fixture player") }) {
                            LazyColumn(Modifier.fillMaxSize().testTag("underlay-feed"), contentPadding = LocalShellContentPadding.current) {
                                items(40) { index ->
                                    Box(Modifier.fillMaxWidth().height(80.dp)
                                        .background(if (index % 2 == 0) Color(0xffe54b6c) else Color(0xff3564c4))) {
                                        Text("Row $index", Modifier.testTag("underlay-$index"))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        val feed = onNodeWithTag("underlay-feed").fetchSemanticsNode().boundsInRoot
        val chrome = onNodeWithTag("shell-compact").fetchSemanticsNode().boundsInRoot
        assertTrue(feed.bottom > chrome.top)
        onNodeWithTag("underlay-feed").performScrollToIndex(6)
        val bounds = onNodeWithTag("shell-tab-Library").fetchSemanticsNode().boundsInRoot
        onNodeWithTag("shell-tab-Library").performTouchInput { down(center) }
        mainClock.advanceTimeBy(120)
        onRoot().writeM4Screenshot("m4-glass-underlay-pressed.png")
        onNodeWithTag("shell-tab-Library").performTouchInput { up() }
        assertEquals(bounds, onNodeWithTag("shell-tab-Library").fetchSemanticsNode().boundsInRoot)
        onNodeWithTag("underlay-feed").performScrollToIndex(39)
        val last = onNodeWithTag("underlay-39").fetchSemanticsNode().boundsInRoot
        assertTrue(last.bottom <= chrome.top)
    }

    @Test fun appleShellRecordsOnlyPageAndLiveBlurOffPreservesControls() = runComposeUiTest {
        var glass by mutableStateOf(true)
        val nav = ShellNavigation()
        val preferences = PreferenceSnapshot.of(LiquidGlassNavBarEnabledKey to true).toEditor()
            .apply { applyInterfaceStyle(InterfaceStyle.APPLE_MUSIC) }.snapshot()
        setContent {
            MaterialTheme {
                ProvideBackdropSources(OpticalPolicy(glass, false, false, false)) { source ->
                    Box(Modifier.size(600.dp, 720.dp)) {
                        ArchiveTuneShell(nav, preferences, { nav.select(ShellTab.Search) }, contentModifier = source,
                            miniPlayer = { Text("Fixture player") },
                            content = { Box(Modifier.fillMaxSize().background(
                                Brush.linearGradient(listOf(Color.Red, Color.Blue)))) })
                    }
                }
            }
        }
        val bounds = onNodeWithTag("shell-tab-Library").fetchSemanticsNode().boundsInRoot
        onRoot().writeM4Screenshot("m4-apple-glass-shell.png")
        runOnIdle { glass = false }
        assertEquals(bounds, onNodeWithTag("shell-tab-Library").fetchSemanticsNode().boundsInRoot)
        onNodeWithTag("shell-tab-Library").performClick()
        assertEquals(ShellTab.Library, nav.selectedTab)
        onNodeWithTag("shell-tab-Search").performClick()
        assertEquals(ShellTab.Search, nav.selectedTab)
        onRoot().writeM4Screenshot("m4-apple-flat-shell.png")
    }
    @Test fun compactWindowTabsNavigateToActualContent() = runComposeUiTest {
        val navigation = ShellNavigation()
        setContent {
            MaterialTheme {
                Box(Modifier.size(480.dp, 720.dp)) {
                    ArchiveTuneShell(navigation, PreferenceSnapshot.EMPTY, { navigation.select(ShellTab.Search) },
                        miniPlayer = { Text("Fixture player") }, content = { Text("Route $it") })
                }
            }
        }
        onNodeWithTag("shell-bottom-bar").assertIsDisplayed()
        // Every layout names its tabs the same way, so the bottom bar owes the rail's contract too.
        ShellTab.entries.forEach { onNodeWithTag("shell-tab-${it.name}").assertIsDisplayed() }
        onNodeWithTag("shell-tab-Library").performClick()
        onNodeWithText("Route library").assertIsDisplayed()
        onRoot().writeM4Screenshot("shell-home-desktop.png")
    }
    @Test fun appleRowRemainsLockedAndAtomicEditRestoresDisplacedStyle() = runComposeUiTest {
        var preferences by mutableStateOf(PreferenceSnapshot.of(PlayerDesignStyleKey to "V6"))
        val nav = ShellNavigation()
        setContent {
            MaterialTheme {
                Box(Modifier.size(1000.dp, 720.dp)) {
                    ArchiveTuneShell(nav, preferences, { nav.select(ShellTab.Search) },
                        miniPlayer = { Text("Fixture player") }, content = { Text("Route $it") })
                }
            }
        }
        runOnIdle { preferences = preferences.toEditor().apply { applyInterfaceStyle(InterfaceStyle.APPLE_MUSIC) }.snapshot() }
        onNodeWithTag("shell-compact").assertIsDisplayed()
        onNodeWithTag("shell-rail").assertDoesNotExist()
        onNodeWithTag("shell-tab-Home").assertDoesNotExist()
        onNodeWithTag("shell-tab-Library").performClick()
        onNodeWithText("Route library").assertIsDisplayed()
        onNodeWithTag("shell-tab-Search").performClick()
        onNodeWithText("Route search").assertIsDisplayed()
        onRoot().writeM4Screenshot("apple-music-experience.png")
        runOnIdle {
            preferences = preferences.toEditor().apply { applyInterfaceStyle(InterfaceStyle.MATERIAL_EXPRESSIVE) }.snapshot()
            assertEquals("V6", preferences[PlayerDesignStyleKey])
        }
        onNodeWithTag("shell-rail").assertIsDisplayed()
    }
    @Test fun resizedWideShellHasExactlyTheSameTabs() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.size(1000.dp, 720.dp)) {
                    ArchiveTuneShell(ShellNavigation(), PreferenceSnapshot.EMPTY, {},
                        miniPlayer = { Text("Fixture player") }, content = { Text(it) })
                }
            }
        }
        onNodeWithTag("shell-rail").assertIsDisplayed()
        ShellTab.entries.forEach { onNodeWithTag("shell-tab-${it.name}").assertIsDisplayed() }
        onAllNodes(hasTestTag("shell-mini-player")).assertCountEquals(1)
    }
}
