/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.flow.MutableStateFlow
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class SettingsPageTest {
    @Test fun inlineControlUpdatesTheSameSnapshotOrdinaryConsumersRead() = runComposeUiTest {
        val store = Store()
        val controller = SettingsController(store, store.snapshots.value, false)
        setContent {
            MaterialTheme {
                SettingsPage(privacyInlineSettingsRows(), store.snapshots.collectAsState().value, controller, onFailure = { error("Edit failed") })
            }
        }
        onNodeWithTag("toggle:sync_yt_history").assertIsOn().performClick()
        waitUntil { !store.snapshots.value.syncYouTubeHistory() }
        onNodeWithTag("toggle:sync_yt_history").assertIsOff()
        onNodeWithTag("toggle:network_metered").assertIsOn()
    }

    @Test fun credentialDraftIsNotPersistedUntilExplicitSaveAndCancelLeavesItUntouched() = runComposeUiTest {
        val store = Store()
        val controller = SettingsController(store, store.snapshots.value, false)
        setContent {
            MaterialTheme {
                SettingsPage(listOf(SettingsRow.Text("credential", Res.string.m6_api_key, AiApiKeyKey, masked = true)),
                    store.snapshots.collectAsState().value, controller, onFailure = { error("Edit failed") })
            }
        }
        onNodeWithTag("setting:credential").performClick()
        onNodeWithTag("settings-draft").performTextReplacement("fixture-cancelled")
        assertNull(store.snapshots.value[AiApiKeyKey])
        onNodeWithText("Cancel").performClick()
        assertNull(store.snapshots.value[AiApiKeyKey])
        onNodeWithTag("setting:credential").performClick()
        onNodeWithTag("settings-draft").performTextReplacement("fixture-saved")
        onNodeWithText("Save").performClick()
        waitUntil { store.snapshots.value[AiApiKeyKey] == "fixture-saved" }
        assertFalse(controller.restart.value.visible)
    }

    @Test fun pickerStoresWireIdentityNotItsTranslatedCaption() = runComposeUiTest {
        val store = Store()
        val controller = SettingsController(store, store.snapshots.value, false)
        setContent {
            MaterialTheme {
                SettingsPage(lyricsSettingsRows {}, store.snapshots.collectAsState().value, controller, onFailure = { error("Edit failed") })
            }
        }
        onNodeWithTag("setting:lyrics_mode").performClick()
        onNodeWithText("Spotify").performClick()
        waitUntil { store.snapshots.value[LyricsModeKey] == "SPOTIFY" }
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
