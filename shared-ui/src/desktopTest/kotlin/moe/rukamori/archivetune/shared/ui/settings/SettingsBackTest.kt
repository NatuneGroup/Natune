package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.flow.MutableStateFlow
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.navigation.ShellNavigation
import moe.rukamori.archivetune.shared.ui.navigation.ShellTab
import kotlin.test.*

/** The host-supplied back control a desktop or iOS settings page draws, and the stack it pops. */
@OptIn(ExperimentalTestApi::class)
class SettingsBackTest {
    @Test fun aHostSuppliedBackIsTheOnlyHeaderAndPops() = runComposeUiTest {
        val store = Store()
        val controller = SettingsController(store, store.snapshots.value, false)
        var backs = 0
        val back: () -> Unit = { backs++ }
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSettingsBack provides back) {
                    SettingsPage(
                        privacyInlineSettingsRows(), store.snapshots.collectAsState().value, controller,
                        onFailure = { error("Edit failed") },
                    )
                }
            }
        }
        onAllNodesWithTag("settings-back").assertCountEquals(1)
        onNodeWithTag("settings-back").performClick()
        assertEquals(1, backs)
    }

    @Test fun openingPhoneSettingsWithoutAnAnchorKeepsTheFirstGroupVisible() = runComposeUiTest {
        val store = Store()
        val controller = SettingsController(store, store.snapshots.value, false)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalPhoneSettingsReference provides phoneSettingsReference("settings")) {
                    SettingsHomePage(controller, {}, onFailure = { error("Edit failed") })
                }
            }
        }
        onNodeWithTag("setting:account").assertIsDisplayed()
        onNodeWithTag("setting:appearance").assertIsDisplayed()
    }

    @Test fun phoneRootSeparatesHeadingAndSearchAndKeepsSubpageAnchorsSearchable() = runComposeUiTest {
        val store = Store()
        val controller = SettingsController(store, store.snapshots.value, false)
        val routes = mutableListOf<String>()
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalPhoneSettingsReference provides phoneSettingsReference("settings")) {
                    SettingsHomePage(controller, routes::add, onFailure = { error("Edit failed") })
                }
            }
        }
        val title = onNodeWithText("Settings").fetchSemanticsNode().boundsInRoot
        val search = onNodeWithTag("settings-search").fetchSemanticsNode().boundsInRoot
        assertTrue(title.bottom < search.top)
        onNodeWithTag("setting:account").assertIsDisplayed()
        onNodeWithTag("setting:playback").assertIsDisplayed()
        onNodeWithTag("setting:appearance_extras").assertDoesNotExist()
        onNodeWithTag("settings-search").performTextInput("Appearance extras")
        onNodeWithTag("setting:appearance_extras").assertIsDisplayed().performClick()
        runOnIdle {
            assertEquals(listOf("settings/appearance/extras"), routes)
            assertEquals(PreferenceSnapshot.EMPTY, store.snapshots.value)
        }
    }

    @Test fun phoneRootStillAcceptsAnAnchorForASearchOnlySubpage() = runComposeUiTest {
        val store = Store()
        val controller = SettingsController(store, store.snapshots.value, false)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalPhoneSettingsReference provides phoneSettingsReference("settings")) {
                    SettingsHomePage(controller, {}, onFailure = { error("Edit failed") }, scrollTo = "lyrics")
                }
            }
        }
        onNodeWithTag("setting:lyrics").assertIsDisplayed()
    }

    @Test fun aHostWithoutBackKeepsThePageHeaderless() = runComposeUiTest {
        val store = Store()
        val controller = SettingsController(store, store.snapshots.value, false)
        setContent {
            MaterialTheme {
                SettingsPage(privacyInlineSettingsRows(), store.snapshots.collectAsState().value, controller,
                    onFailure = { error("Edit failed") })
            }
        }
        onAllNodesWithTag("settings-back").assertCountEquals(0)
    }

    @Test fun aHeaderlessHostPageDrawsTheSameBack() = runComposeUiTest {
        var backs = 0
        val back: () -> Unit = { backs++ }
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSettingsBack provides back) {
                    YtDlpRuntimeSettings { YtDlpRuntimeStatus(pythonAvailable = false, scriptAvailable = false) }
                }
            }
        }
        onNodeWithTag("settings-back").performClick()
        assertEquals(1, backs)
    }

    @Test fun backFromSettingsAndANestedPageLandsOnTheTabRoute() = runComposeUiTest {
        val store = Store()
        val controller = SettingsController(store, store.snapshots.value, false)
        val navigation = ShellNavigation(ShellTab.Library)
        val back: () -> Unit = { navigation.back() }
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSettingsBack provides back) {
                    when (navigation.route) {
                        ShellTab.Library.route -> TextButton(
                            onClick = { navigation.open("settings") },
                            modifier = Modifier.testTag("open-settings"),
                        ) { Text("Settings") }
                        "settings" -> SettingsHomePage(
                            controller = controller,
                            navigate = { navigation.open(it) },
                            onFailure = { error("Edit failed") },
                        )
                        "settings/appearance" -> SettingsPage(
                            lyricsSettingsRows {}, store.snapshots.collectAsState().value, controller,
                            onFailure = { error("Edit failed") },
                        )
                    }
                }
            }
        }
        onNodeWithTag("open-settings").performClick()
        assertEquals("settings", navigation.route)
        onNodeWithTag("setting:appearance").performClick()
        assertEquals("settings/appearance", navigation.route)
        onNodeWithTag("settings-back").performClick()
        assertEquals("settings", navigation.route)
        onNodeWithTag("settings-back").performClick()
        assertEquals(ShellTab.Library.route, navigation.route)
        assertFalse(navigation.canGoBack)
    }

    private class Store : SettingsStore {
        override val snapshots = MutableStateFlow(PreferenceSnapshot.EMPTY)
        override suspend fun edit(change: PreferenceEditor.() -> Unit): SettingsChange {
            val before = snapshots.value
            val after = before.toEditor().apply(change).snapshot()
            snapshots.value = after
            return SettingsChange(before, after)
        }
    }
}
