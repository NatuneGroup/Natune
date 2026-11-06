package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SettingsTwoPaneTest {
    @Test fun searchFiltersCategoriesAndSelectionSwapsTheDetail() = runComposeUiTest {
        var selected: String? = null
        val rows = settingsHomeRows(navigate = {}, androidAuto = false, hideToTrayKey = null, openShortcuts = {})
        setContent {
            MaterialTheme {
                Box(Modifier.size(1200.dp, 800.dp)) {
                    SettingsTwoPane(
                        selectedRoute = "settings/player",
                        rows = rows,
                        onSelect = { selected = it },
                        fallbackTitle = "Settings",
                        detail = { Text("Playback detail body") },
                    )
                }
            }
        }
        onNodeWithTag("settings-two-pane").assertIsDisplayed()
        onNodeWithText("Playback").assertIsDisplayed()
        onNodeWithText("Playback detail body").assertIsDisplayed()
        onNodeWithTag("settings-nav-settings/account").assertIsDisplayed()

        onNodeWithTag("settings-search-field").performTextInput("worker")
        onNodeWithTag("settings-nav-empty").assertIsDisplayed()
        onNodeWithTag("settings-nav-settings/account").assertDoesNotExist()

        // Clear, then a real category narrows the pane to the matching row.
        onNodeWithTag("settings-search-clear").performClick()
        onNodeWithTag("settings-search-field").performTextInput("playback")
        onNodeWithTag("settings-nav-settings/player").assertIsDisplayed()
        onNodeWithTag("settings-nav-settings/account").assertDoesNotExist()
        onNodeWithTag("settings-nav-settings/player").performClick()
        assertEquals("settings/player", selected)
    }

    @Test fun narrowPaneKeepsTheDetailHeaderAndBackFreeBody() = runComposeUiTest {
        var selected: String? = null
        val rows = settingsHomeRows(navigate = {}, androidAuto = false, hideToTrayKey = null, openShortcuts = {})
        setContent {
            MaterialTheme {
                Box(Modifier.size(960.dp, 720.dp)) {
                    SettingsTwoPane(
                        selectedRoute = null,
                        rows = rows,
                        onSelect = { selected = it },
                        fallbackTitle = "Settings",
                        detail = { Text("Root detail body") },
                    )
                }
            }
        }
        onNodeWithTag("settings-detail-title").assertIsDisplayed()
        onNodeWithText("Root detail body").assertIsDisplayed()
        onNodeWithTag("settings-nav-settings/player").performScrollTo().performClick()
        assertEquals("settings/player", selected)
    }
}
