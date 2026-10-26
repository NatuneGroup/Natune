package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Host answers for a feed check. The host builds [UpdateOffer] through [eligibleUpdateOffer], so a
 * build that is not a verified newer one never reaches this page as an offer.
 */
sealed interface UpdateFeedResult {
    data class Offer(val offer: UpdateOffer) : UpdateFeedResult
    data object UpToDate : UpdateFeedResult
    data object NoInstallable : UpdateFeedResult
    data object Unavailable : UpdateFeedResult
}

private enum class UpdateDialog { CHANNEL, NIGHTLY_CONFIRM }

/**
 * Channel, notification and popup preferences plus a manual check. Notification scheduling, feed
 * transport and download/install are host callbacks; this page never performs a network call itself.
 */
@Composable
fun UpdateSettings(
    controller: SettingsController,
    isNightlyBuild: Boolean,
    updaterAvailable: Boolean,
    checkFeed: suspend (UpdateChannel) -> UpdateFeedResult,
    onChannelChanged: suspend (UpdateChannel) -> Unit,
    onNotificationChanged: suspend (Boolean) -> Unit,
    loadCommits: suspend (UpdateChannel) -> List<UpdateCommit>,
    commitAvatar: @Composable (url: String?, modifier: Modifier) -> Unit,
    onOpenCommit: (String) -> Unit,
    navigate: (String) -> Unit,
    onFailure: () -> Unit,
    scrollTo: String? = null,
    modifier: Modifier = Modifier,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot
    if (preferences == null) { CircularProgressIndicator(); return }
    val scope = rememberCoroutineScope()
    val selected = preferences.selectedUpdateChannel(isNightlyBuild)
    val latestSelected by rememberUpdatedState(selected)
    // Every channel change starts a new revision, so a slow answer for the previous channel can never land.
    var selectionRevision by remember { mutableLongStateOf(0L) }
    val latestRevision by rememberUpdatedState(selectionRevision)
    var dialog by remember { mutableStateOf<UpdateDialog?>(null) }
    var checking by remember { mutableStateOf(false) }
    var feed by remember { mutableStateOf<UpdateFeedResult?>(null) }
    LaunchedEffect(selected) {
        selectionRevision += 1
        feed = null
        checking = false
    }

    fun switchChannel(channel: UpdateChannel) {
        if (channel == latestSelected) return
        scope.launch {
            try {
                controller.edit { setUpdateChannel(channel) }
                onChannelChanged(channel)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onFailure()
            }
        }
    }

    fun requestChannel(channel: UpdateChannel) {
        // Nightly carries a warning, so the switch waits for an explicit confirmation.
        if (channel == UpdateChannel.NIGHTLY && latestSelected != UpdateChannel.NIGHTLY) {
            dialog = UpdateDialog.NIGHTLY_CONFIRM
        } else {
            switchChannel(channel)
        }
    }

    fun runCheck() {
        val requested = selected
        val revision = selectionRevision
        checking = true
        scope.launch {
            val result = try {
                checkFeed(requested)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                UpdateFeedResult.Unavailable
            }
            if (updateRequestIsCurrent(requested, revision, latestSelected, latestRevision)) {
                feed = result
                checking = false
            }
        }
    }

    val statusText = when {
        !updaterAvailable -> stringResource(Res.string.m6_update_status_disabled)
        checking -> stringResource(Res.string.m6_update_status_checking)
        else -> when (val result = feed) {
            null -> stringResource(Res.string.m6_update_status_idle)
            is UpdateFeedResult.Offer -> stringResource(Res.string.m6_update_status_available, result.offer.versionName)
            UpdateFeedResult.UpToDate -> stringResource(Res.string.m6_update_status_up_to_date)
            UpdateFeedResult.NoInstallable -> stringResource(Res.string.m6_update_status_no_installable)
            UpdateFeedResult.Unavailable -> stringResource(Res.string.m6_update_status_unavailable)
        }
    }
    val rows = listOf<SettingsRow>(
        SettingsRow.Status(
            "update_channel", Res.string.m6_update_channel,
            value = stringResource(channelLabel(selected)),
            run = { dialog = UpdateDialog.CHANNEL },
            enabled = updaterAvailable,
        ),
        SettingsRow.Toggle(
            "enable_update_notification", Res.string.m6_update_notification, EnableUpdateNotificationKey, false,
            description = Res.string.m6_update_notification_desc, enabled = updaterAvailable,
        ),
        SettingsRow.Toggle(
            "persistent_update_popup", Res.string.m6_update_persistent, PersistentUpdatePopupKey, false,
            description = Res.string.m6_update_persistent_desc, enabled = updaterAvailable,
        ),
        SettingsRow.Action(
            "check_for_update", Res.string.m6_update_check, run = { runCheck() },
            enabled = updaterAvailable && !checking,
        ),
        SettingsRow.Status("update_status", Res.string.m6_update_status_title, value = statusText),
        SettingsRow.Action(
            "changelog", Res.string.m6_update_changelog,
            run = { navigate("settings/changelog?channel=${selected.name}") },
            description = Res.string.m6_update_changelog_desc,
        ),
    )
    SettingsPage(
        rows = rows,
        preferences = preferences,
        controller = controller,
        scrollTo = scrollTo,
        onFailure = onFailure,
        modifier = modifier,
        onCommitted = { change ->
            if (EnableUpdateNotificationKey in change.changedKeys) {
                onNotificationChanged(change.after[EnableUpdateNotificationKey, false])
            }
        },
        extraItems = {
            item(key = "commit_history", contentType = "commit_history") {
                UpdateCommitHistory(
                    channel = selected,
                    loadCommits = loadCommits,
                    commitAvatar = commitAvatar,
                    onOpenCommit = onOpenCommit,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )

    if (dialog == UpdateDialog.CHANNEL) {
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(Res.string.m6_update_choose_channel)) },
            text = {
                Column {
                    Text(stringResource(Res.string.m6_update_channel_help), style = MaterialTheme.typography.bodySmall)
                    ChannelOption(UpdateChannel.STABLE, selected) { dialog = null; requestChannel(UpdateChannel.STABLE) }
                    ChannelOption(UpdateChannel.NIGHTLY, selected) { dialog = null; requestChannel(UpdateChannel.NIGHTLY) }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(Res.string.m6_done)) } },
        )
    }
    if (dialog == UpdateDialog.NIGHTLY_CONFIRM) {
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(Res.string.m6_update_channel_nightly)) },
            text = { Text(stringResource(Res.string.m6_update_nightly_confirmation), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { dialog = null; switchChannel(UpdateChannel.NIGHTLY) }) {
                    Text(stringResource(Res.string.m6_confirm))
                }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(Res.string.m6_cancel)) } },
        )
    }
}

@Composable
private fun ChannelOption(channel: UpdateChannel, selected: UpdateChannel, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = channel == selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(channelLabel(channel)))
    }
}

internal fun channelLabel(channel: UpdateChannel): StringResource = when (channel) {
    UpdateChannel.STABLE -> Res.string.m6_update_channel_stable
    UpdateChannel.NIGHTLY -> Res.string.m6_update_channel_nightly
}
