package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.domain.update.UpdateDownloadState
import moe.rukamori.archivetune.shared.domain.update.UpdateInstallMode
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

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

private enum class UpdateDialog { CHANNEL, NIGHTLY_CONFIRM, NOTIFICATION_CONFIRM, UP_TO_DATE, CHECK_ERROR }

private val updateNotificationPreferenceKeys = setOf(EnableUpdateNotificationKey, PersistentUpdatePopupKey)

/**
 * Channel, notification and popup preferences plus the manual check, the offer sheet, the download
 * progress dialog and the result dialogs — the UpdateScreen semantics in shared form. Notification
 * scheduling/permissions, feed transport and download/install are host callbacks; hosts without
 * notification delivery omit those rows. This page never performs a network call itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSettings(
    controller: SettingsController,
    isNightlyBuild: Boolean,
    updaterAvailable: Boolean,
    installMode: UpdateInstallMode,
    /** [forceRefresh] mirrors the app's split: the auto-check on open serves caches, the button forces. */
    checkFeed: suspend (UpdateChannel, forceRefresh: Boolean) -> UpdateFeedResult,
    onChannelChanged: suspend (UpdateChannel) -> Unit,
    /** Null hides notification controls on hosts with no notification scheduler or OS notification delivery. */
    onNotificationChanged: (suspend (Boolean) -> Unit)?,
    /** Invoked when the user taps Update on an offer (download for DIRECT_INSTALL, open URL for OPEN_URL). */
    installOffer: (UpdateOffer) -> Unit,
    /** Live download state from the host's installer port; null when the host never downloads. */
    downloadState: StateFlow<UpdateDownloadState>? = null,
    cancelDownload: () -> Unit = {},
    /** Terminal-state consumption: ReadyToInstall launches the platform installer, Failed dismisses. */
    consumeDownload: (UpdateDownloadState) -> Unit = {},
    /** "You are up to date" hook (e.g. clearing a badge), fired when a check finds no offer. */
    onUpToDate: () -> Unit = {},
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
    val download = downloadState?.collectAsState()?.value

    LaunchedEffect(selected) {
        selectionRevision += 1
        feed = null
        checking = false
    }

    // The app checks the channel when the page opens — a cached answer is fine, so no forced refresh.
    LaunchedEffect(selected, updaterAvailable) {
        if (!updaterAvailable) return@LaunchedEffect
        val requested = selected
        val revision = selectionRevision
        val result = try {
            checkFeed(requested, false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            UpdateFeedResult.Unavailable
        }
        if (updateRequestIsCurrent(requested, revision, latestSelected, latestRevision)) {
            feed = result
            if (result !is UpdateFeedResult.Offer) onUpToDate()
        }
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
                checkFeed(requested, true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                UpdateFeedResult.Unavailable
            }
            if (updateRequestIsCurrent(requested, revision, latestSelected, latestRevision)) {
                feed = result
                checking = false
                dialog = when (result) {
                    is UpdateFeedResult.Offer -> null // the offer sheet carries the action
                    UpdateFeedResult.Unavailable -> UpdateDialog.CHECK_ERROR
                    UpdateFeedResult.UpToDate, UpdateFeedResult.NoInstallable -> {
                        onUpToDate()
                        UpdateDialog.UP_TO_DATE
                    }
                }
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
        SettingsRow.Custom("update_fork_warning", Res.string.m6_update_channel) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(Res.string.m6_update_fork_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
        },
        SettingsRow.Status(
            "update_channel", Res.string.m6_update_channel,
            value = stringResource(channelLabel(selected)),
            run = { dialog = UpdateDialog.CHANNEL },
            enabled = updaterAvailable,
        ),
        SettingsRow.Toggle(
            "enable_update_notification", Res.string.m6_update_notification, EnableUpdateNotificationKey, false,
            description = Res.string.m6_update_notification_desc, enabled = updaterAvailable,
            read = { it[EnableUpdateNotificationKey, false] },
            write = {
                val enabling = it
                if (enabling) {
                    dialog = UpdateDialog.NOTIFICATION_CONFIRM
                } else {
                    this[EnableUpdateNotificationKey] = false
                }
            },
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
    ).filter { row ->
        onNotificationChanged != null ||
            (row as? SettingsRow.Toggle)?.key !in updateNotificationPreferenceKeys
    }
    SettingsPage(
        rows = rows,
        preferences = preferences,
        controller = controller,
        scrollTo = scrollTo,
        onFailure = onFailure,
        modifier = modifier,
        onCommitted = { change ->
            if (EnableUpdateNotificationKey in change.changedKeys) {
                onNotificationChanged?.invoke(change.after[EnableUpdateNotificationKey, false])
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

    // The offer sheet: version + notes + the Update action, gated on the channel the offer was checked on.
    val offer = (feed as? UpdateFeedResult.Offer)?.offer
    if (offer != null && offer.channel == selected) {
        ModalBottomSheet(onDismissRequest = { feed = UpdateFeedResult.UpToDate }) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            ) {
                Text(stringResource(Res.string.m6_update_new_available), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        offer.versionName,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    offer.notes?.takeIf { it.isNotBlank() }
                        ?: stringResource(Res.string.m6_update_release_notes_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { feed = null; installOffer(offer) },
                    enabled = installMode != UpdateInstallMode.UNAVAILABLE,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(if (installMode == UpdateInstallMode.DIRECT_INSTALL) Res.string.m6_update_download_and_install else Res.string.m6_update_view_release))
                }
            }
        }
    }

    // The download dialog: determinate when the stream's length is known, always cancellable by the user.
    when (val state = download) {
        is UpdateDownloadState.Downloading -> AlertDialog(
            onDismissRequest = { },
            title = {
                Text(
                    stringResource(
                        Res.string.m6_update_downloading_title,
                        stringResource(channelLabel(state.offer.channel)),
                        state.offer.versionName,
                    ),
                )
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    val fraction = state.fraction
                    if (fraction != null) {
                        CircularWavyProgressIndicator(progress = { fraction }, modifier = Modifier.size(96.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("${(fraction.coerceIn(0f, 1f) * 100).toInt()}%")
                    } else {
                        CircularWavyProgressIndicator(modifier = Modifier.size(72.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = cancelDownload) { Text(stringResource(Res.string.m6_cancel)) }
            },
        )
        is UpdateDownloadState.ReadyToInstall -> LaunchedEffect(state) { consumeDownload(state) }
        is UpdateDownloadState.Failed -> AlertDialog(
            onDismissRequest = { consumeDownload(state) },
            title = { Text(stringResource(Res.string.m6_update_download_failed_title)) },
            text = { Text(state.message ?: stringResource(Res.string.m6_update_download_failed_message)) },
            confirmButton = {
                TextButton(onClick = { consumeDownload(state) }) { Text(stringResource(Res.string.m6_done)) }
            },
        )
        else -> Unit
    }

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
    // Enabling notifications asks first, like the app: the warning names both channels before the commit lands.
    if (dialog == UpdateDialog.NOTIFICATION_CONFIRM) {
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(Res.string.m6_update_notification)) },
            text = {
                Text(
                    stringResource(Res.string.m6_update_notification_confirm_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    scope.launch {
                        try {
                            controller.edit { this[EnableUpdateNotificationKey] = true }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            onFailure()
                        }
                    }
                }) {
                    Text(stringResource(Res.string.m6_update_notification_confirm_ack))
                }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(Res.string.m6_cancel)) } },
        )
    }
    if (dialog == UpdateDialog.UP_TO_DATE) {
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(Res.string.m6_update_up_to_date_title)) },
            text = { Text(stringResource(Res.string.m6_update_up_to_date_build)) },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(Res.string.m6_done)) } },
        )
    }
    if (dialog == UpdateDialog.CHECK_ERROR) {
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(Res.string.m6_update_check_error_title)) },
            text = { Text(stringResource(Res.string.m6_update_check_error_body)) },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(Res.string.m6_done)) } },
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
