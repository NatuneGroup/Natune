/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The snapshot the page reads when no Drive app-data host is bound. It keeps one state read on every path, and it is never
 * written, so the unbound section stays in the checking state and offers no action.
 */
internal val UnboundDriveAppDataSnapshot: StateFlow<DriveAppDataSnapshot?> = MutableStateFlow(null)

/**
 * The Google Drive app-data rows in baseline order. They sit between the Drive folder sync and the manual backup rows, so
 * the page keeps one scroll position and one anchor space. A null [snapshot] is the checking state before the host's first
 * load. Every action waits for an idle host, and the page guards repeated taps while a call is still in flight.
 */
@Composable
internal fun driveAppDataRows(
    host: DriveAppDataBackupHost,
    snapshot: DriveAppDataSnapshot?,
    busy: Boolean,
    launch: (suspend () -> Unit) -> Unit,
    onCancel: () -> Unit,
    onFrequencyOpen: () -> Unit,
    onRestorePrepared: (DriveAppDataRestorePreparation) -> Unit,
): List<SettingsRow> {
    val data = snapshot ?: DriveAppDataSnapshot()
    val loading = snapshot == null
    val canAct = !loading && !busy && data.activity == null
    val connected = data.accountName != null
    val accountText = data.accountName ?: stringResource(Res.string.m6_drive_app_data_choose_account)
    val lastBackupText = data.lastBackup
        ?.let { stringResource(Res.string.m6_drive_app_data_details, it.date, it.size) }
        ?: stringResource(Res.string.m6_drive_app_data_no_backup)
    val frequencyText = stringResource(data.frequency.labelResource())
    val failure = data.failure
    val statusResource = activityResource(data.activity)
    val showStatus = loading || busy || data.activity != null
    val cancelable = !loading && (busy || data.activity != null)
    return buildList<SettingsRow> {
        add(SettingsRow.Header(anchor = "drive_app_data", title = Res.string.m6_drive_app_data_title))
        add(
            SettingsRow.Status(
                anchor = "drive_app_data_account",
                title = Res.string.m6_drive_app_data_account,
                value = accountText,
                run = { launch { host.chooseAccount() } },
                enabled = canAct,
            ),
        )
        add(
            SettingsRow.Custom(anchor = "drive_app_data_last_backup", title = Res.string.m6_drive_app_data_last_backup) {
                DriveAppDataItem(
                    anchor = "drive_app_data_last_backup",
                    headline = { Text(stringResource(Res.string.m6_drive_app_data_last_backup)) },
                    supporting = {
                        Column {
                            Text(lastBackupText)
                            Text(
                                text = stringResource(Res.string.m6_drive_app_data_contents),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    },
                )
            },
        )
        if (failure != null) {
            add(
                SettingsRow.Custom(anchor = "drive_app_data_error", title = failure.messageResource()) {
                    DriveAppDataItem(
                        anchor = "drive_app_data_error",
                        headline = {
                            Text(
                                text = stringResource(failure.messageResource()),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        supporting = { Text(stringResource(Res.string.m6_retry)) },
                        onClick = if (canAct) ({ launch { host.refresh() } }) else null,
                    )
                },
            )
        }
        if (showStatus) {
            add(
                SettingsRow.Custom(anchor = "drive_app_data_status", title = statusResource) {
                    DriveAppDataItem(
                        anchor = "drive_app_data_status",
                        headline = { Text(stringResource(statusResource)) },
                        supporting = { DriveAppDataProgress(percent = data.progressPercent) },
                        trailing = {
                            if (cancelable) {
                                TextButton(onClick = onCancel) { Text(stringResource(Res.string.m6_cancel)) }
                            }
                        },
                    )
                },
            )
        }
        add(
            SettingsRow.Action(
                anchor = "drive_app_data_backup_now",
                title = Res.string.m6_drive_app_data_now,
                run = { launch { host.backupNow() } },
                enabled = canAct && connected,
            ),
        )
        add(
            SettingsRow.Action(
                anchor = "drive_app_data_restore",
                title = Res.string.m6_drive_app_data_restore,
                run = { launch { onRestorePrepared(host.prepareLatestRestore()) } },
                enabled = canAct && connected && data.lastBackup != null,
                description = Res.string.m6_drive_app_data_restore_description,
            ),
        )
        add(
            SettingsRow.Status(
                anchor = "drive_app_data_frequency",
                title = Res.string.m6_scheduled_backup_frequency,
                value = frequencyText,
                run = onFrequencyOpen,
                enabled = canAct && connected,
            ),
        )
        add(
            SettingsRow.Custom(anchor = "drive_app_data_wifi_only", title = Res.string.m6_drive_app_data_wifi_only) {
                DriveAppDataWifiItem(
                    checked = data.wifiOnly,
                    enabled = canAct && connected,
                    onToggle = { checked -> launch { host.setWifiOnly(checked) } },
                )
            },
        )
        if (connected) {
            add(
                SettingsRow.Action(
                    anchor = "drive_app_data_disconnect",
                    title = Res.string.m6_drive_app_data_disconnect,
                    run = { launch { host.disconnect() } },
                    enabled = canAct,
                    description = Res.string.m6_drive_app_data_disconnect_description,
                ),
            )
        }
    }
}

/**
 * The schedule choice. A row is selected from the snapshot, never from its label, and choosing a row only starts the host
 * write, so the dialog closes before the host call begins.
 */
@Composable
internal fun DriveAppDataFrequencyDialog(
    selected: DriveAppDataFrequency,
    onSelect: (DriveAppDataFrequency) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.m6_scheduled_backup_frequency)) },
        text = {
            Column {
                DriveAppDataFrequency.entries.forEach { frequency ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(frequency) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = frequency == selected, onClick = null)
                        Text(
                            text = stringResource(frequency.labelResource()),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.m6_cancel)) }
        },
    )
}

/** One Drive app-data row laid out like the settings page rows, so the section matches its neighbours. */
@Composable
private fun DriveAppDataItem(
    anchor: String,
    headline: @Composable () -> Unit,
    supporting: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = headline,
        supportingContent = supporting,
        trailingContent = trailing,
        modifier = Modifier
            .testTag("setting:$anchor")
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    )
}

/** An indeterminate bar while the length is unknown, and a determinate bar once the download reports a percentage. */
@Composable
private fun DriveAppDataProgress(percent: Int?) {
    val modifier = Modifier.fillMaxWidth()
    if (percent == null) {
        LinearProgressIndicator(modifier = modifier)
    } else {
        val fraction = percent.coerceIn(0, 100) / 100f
        LinearProgressIndicator(progress = { fraction }, modifier = modifier)
    }
}

@Composable
private fun DriveAppDataWifiItem(checked: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val onRowClick: () -> Unit = { onToggle(!checked) }
    DriveAppDataItem(
        anchor = "drive_app_data_wifi_only",
        headline = { Text(stringResource(Res.string.m6_drive_app_data_wifi_only)) },
        supporting = { Text(stringResource(Res.string.m6_drive_app_data_wifi_description)) },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onToggle,
                enabled = enabled,
                modifier = Modifier.testTag("toggle:drive_app_data_wifi_only"),
            )
        },
        onClick = if (enabled) onRowClick else null,
    )
}

private fun DriveAppDataFrequency.labelResource(): StringResource = when (this) {
    DriveAppDataFrequency.MANUAL -> Res.string.m6_drive_app_data_frequency_manual
    DriveAppDataFrequency.DAILY -> Res.string.m6_scheduled_backup_daily
    DriveAppDataFrequency.WEEKLY -> Res.string.m6_scheduled_backup_weekly
    DriveAppDataFrequency.MONTHLY -> Res.string.m6_scheduled_backup_monthly
}

/** A corrupt archive gets the same wording as a failed manual restore, so both restore paths report it the same way. */
private fun DriveAppDataFailure.messageResource(): StringResource = when (this) {
    DriveAppDataFailure.AUTHORIZATION -> Res.string.m6_drive_app_data_authorization_failed
    DriveAppDataFailure.CONFIGURATION -> Res.string.m6_drive_app_data_configuration_error
    DriveAppDataFailure.NETWORK -> Res.string.m6_drive_app_data_network_error
    DriveAppDataFailure.QUOTA -> Res.string.m6_drive_app_data_quota_error
    DriveAppDataFailure.MISSING -> Res.string.m6_drive_app_data_not_found
    DriveAppDataFailure.INVALID -> Res.string.m6_backup_corrupted
    DriveAppDataFailure.STORAGE -> Res.string.m6_drive_app_data_storage_error
    DriveAppDataFailure.UNKNOWN -> Res.string.m6_drive_app_data_failed
}

/** A null activity is the checking state, so the status row never shows an empty line. */
private fun activityResource(activity: DriveAppDataActivity?): StringResource = when (activity) {
    null, DriveAppDataActivity.CHECKING -> Res.string.m6_drive_app_data_loading
    DriveAppDataActivity.PREPARING -> Res.string.m6_drive_app_data_preparing
    DriveAppDataActivity.UPLOADING -> Res.string.m6_drive_app_data_uploading
    DriveAppDataActivity.DOWNLOADING -> Res.string.m6_drive_app_data_downloading
    DriveAppDataActivity.WAITING -> Res.string.m6_drive_app_data_waiting
}
