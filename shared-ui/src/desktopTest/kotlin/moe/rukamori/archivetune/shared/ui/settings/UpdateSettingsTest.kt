package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.material3.MaterialTheme
import moe.rukamori.archivetune.shared.domain.update.UpdateInstallMode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import moe.rukamori.archivetune.shared.domain.settings.*

@OptIn(ExperimentalTestApi::class)
class UpdateSettingsTest {
    @Test
    fun enablingNotificationFromConfirmationRunsTheSameHostCallbackAsTheSwitch() = runComposeUiTest {
        val store = Store()
        val controller = SettingsController(store, store.snapshots.value, false)
        var notificationCallbacks = 0
        setContent {
            MaterialTheme {
                UpdateSettings(
                    controller = controller,
                    isNightlyBuild = false,
                    updaterAvailable = true,
                    installMode = UpdateInstallMode.OPEN_URL,
                    checkFeed = { _, _ -> UpdateFeedResult.UpToDate },
                    onChannelChanged = {},
                    onNotificationChanged = { if (it) notificationCallbacks++ },
                    installOffer = {},
                    loadCommits = { emptyList() },
                    commitAvatar = { _, _ -> },
                    onOpenCommit = {},
                    navigate = {},
                    onFailure = { error("Update fixture failed") },
                )
            }
        }

        onNodeWithTag("toggle:enable_update_notification").performScrollTo().performClick()
        onNodeWithText("Enable").performClick()

        waitUntil { store.snapshots.value[EnableUpdateNotificationKey] == true }
        waitUntil { notificationCallbacks == 1 }
        assertEquals(1, notificationCallbacks)
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
