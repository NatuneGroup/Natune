package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.flow.MutableStateFlow
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.domain.update.UpdateInstallMode

@OptIn(ExperimentalTestApi::class)
class UpdateAvailabilityTest {
    @Test
    fun aHostWithoutNotificationDeliveryOmitsThoseControlsButStillChecksTheFeed() = runComposeUiTest {
        val store = Store()
        val controller = SettingsController(store, store.snapshots.value, false)
        var feedChecks = 0
        setContent {
            MaterialTheme {
                Box(Modifier.size(500.dp, 900.dp)) {
                UpdateSettings(
                    controller = controller,
                    isNightlyBuild = false,
                    updaterAvailable = true,
                    installMode = UpdateInstallMode.OPEN_URL,
                    checkFeed = { channel, _ ->
                        assertEquals(UpdateChannel.STABLE, channel)
                        feedChecks++
                        UpdateFeedResult.UpToDate
                    },
                    onChannelChanged = {},
                    onNotificationChanged = null,
                    installOffer = {},
                    loadCommits = { emptyList() },
                    commitAvatar = { _, _ -> },
                    onOpenCommit = {},
                    navigate = {},
                    onFailure = { error("Update fixture failed") },
                )
                }
            }
        }

        waitUntil { feedChecks == 1 }
        onNodeWithTag("toggle:enable_update_notification").assertDoesNotExist()
        onNodeWithTag("toggle:persistent_update_popup").assertDoesNotExist()
        runOnIdle {
            assertFalse(store.snapshots.value[EnableUpdateNotificationKey, false])
            assertFalse(store.snapshots.value[PersistentUpdatePopupKey, false])
        }
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
