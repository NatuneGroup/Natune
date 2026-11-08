package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.backup.BackupArchiveException
import moe.rukamori.archivetune.shared.domain.backup.BackupArchiveProblem
import moe.rukamori.archivetune.shared.domain.backup.DriveAppDataLimits
import moe.rukamori.archivetune.shared.domain.backup.PreparedArchive
import moe.rukamori.archivetune.shared.ui.IosHost
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.ios_backup_restart_later
import moe.rukamori.archivetune.shared.ui.resources.ios_backup_restart_message
import moe.rukamori.archivetune.shared.ui.resources.ios_backup_restart_quit
import moe.rukamori.archivetune.shared.ui.resources.ios_backup_restart_title
import moe.rukamori.archivetune.shared.ui.resources.ios_backup_restore_failed_dismiss
import moe.rukamori.archivetune.shared.ui.resources.ios_backup_restore_failed_message
import moe.rukamori.archivetune.shared.ui.resources.ios_backup_restore_failed_title
import moe.rukamori.archivetune.shared.ui.resources.ios_restore_blocked_empty
import moe.rukamori.archivetune.shared.ui.resources.ios_restore_blocked_message
import moe.rukamori.archivetune.shared.ui.resources.ios_restore_blocked_previous
import moe.rukamori.archivetune.shared.ui.resources.ios_restore_blocked_title
import moe.rukamori.archivetune.shared.ui.settings.BackupAndRestoreSettings
import moe.rukamori.archivetune.shared.ui.settings.BackupExportResult
import moe.rukamori.archivetune.shared.ui.settings.BackupRestoreCandidate
import moe.rukamori.archivetune.shared.ui.settings.BackupRestorePreparation
import moe.rukamori.archivetune.shared.ui.settings.BackupRestoreProblem
import moe.rukamori.archivetune.shared.ui.settings.BackupSelectionCategory
import moe.rukamori.archivetune.shared.ui.settings.SettingsController
import moe.rukamori.archivetune.shared.ui.settings.SettingsNotice
import org.jetbrains.compose.resources.stringResource
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.posix.exit

/**
 * Settings > Backup and restore on this host: manual archives through the document picker and the share sheet. The
 * archive is the same portable format the desktop host writes, so a file crosses platforms unchanged. The Google
 * Drive app-data section is always mounted; while the build carries no iOS OAuth client (the
 * `ArchiveTuneGoogleDriveClientId` Info.plist key is blank) the section reports its not-configured state with the
 * same localized string an action would surface. Its refresher and uploads use the same archive and the same
 * staged restore as a file, so one binding consumes both prepared handles. The scheduled, Drive folder-sync and
 * playlist rows do not appear: this host has no folder picker for them.
 */
@Composable
internal fun IosBackupRestorePage(
    host: IosHost,
    graph: IosSessionGraph,
    controller: SettingsController,
    onNotice: (SettingsNotice) -> Unit,
    onFailure: () -> Unit,
    scrollTo: String?,
) {
    val driveBinding = graph.driveBackup.binding
    BackupAndRestoreSettings(
        controller = controller,
        createBackup = { selected -> saveIosBackup(host, graph, selected) },
        prepareRestore = { openIosBackup(host, graph) },
        discardPreparedBackup = driveBinding::discardPrepared,
        restoreBackup = driveBinding::restorePrepared,
        onNotice = onNotice,
        onFailure = onFailure,
        scrollTo = scrollTo,
        driveAppDataHost = graph.driveBackup.drive,
        backupSyncHost = graph.backupSync,
        modifier = Modifier.fillMaxSize(),
    )
    graph.backupSync?.let { IosBackupSyncDateDialog(it) }
    IosBackupRestartDialog(graph)
    IosBackupRestoreFailedDialog(graph)
    IosRestoreBlockedDialog(graph)
}

/**
 * Writes the archive beside the share sheet's temporary file and shares it, the Save to Files and AirDrop
 * counterpart of the desktop save dialog. A dismissed sheet is a cancel; a failed write throws, so the page reports
 * the export as failed rather than saved.
 */
private suspend fun saveIosBackup(
    host: IosHost,
    graph: IosSessionGraph,
    selected: Set<BackupSelectionCategory>,
): BackupExportResult {
    val archive = graph.backup.archives.create(selected.map { it.toDataCategory() }.toSet())
    val stamp = NSDateFormatter().apply { dateFormat = "yyyyMMddHHmmss" }.stringFromDate(NSDate())
    return when (iosExportDataFile(host, "Natune_$stamp.backup", archive)) {
        true -> BackupExportResult.SAVED
        null -> BackupExportResult.CANCELLED
        else -> throw BackupArchiveException(BackupArchiveProblem.UNAVAILABLE)
    }
}

/**
 * Opens the document picker, reads the file within the shared archive bound and prepares it for the section dialog.
 * An oversized document fails the bounded read, which the page reports as a failed restore rather than a bad backup.
 */
private suspend fun openIosBackup(host: IosHost, graph: IosSessionGraph): BackupRestorePreparation {
    val handle = host.files.pickDocument(host.viewController) ?: return BackupRestorePreparation.Cancelled
    val archive = host.files.read(handle, DriveAppDataLimits.MAX_ARCHIVE_BYTES.toLong())
    return when (val prepared = graph.backup.restores.prepare(archive)) {
        is PreparedArchive.Ready -> BackupRestorePreparation.Ready(
            BackupRestoreCandidate(prepared.handle, prepared.categories.map { it.toSelection() }.toSet()),
        )
        is PreparedArchive.Rejected -> when (prepared.problem) {
            BackupArchiveProblem.NOT_A_BACKUP -> BackupRestorePreparation.Rejected(BackupRestoreProblem.NOT_A_BACKUP)
            BackupArchiveProblem.NO_RESTORABLE_DATA ->
                BackupRestorePreparation.Rejected(BackupRestoreProblem.NO_RESTORABLE_DATA)
            BackupArchiveProblem.CORRUPTED -> BackupRestorePreparation.Rejected(BackupRestoreProblem.CORRUPTED)
            // Not a property of the file: the page reports these as a failed restore, not as a bad backup.
            BackupArchiveProblem.INSUFFICIENT_STORAGE, BackupArchiveProblem.UNAVAILABLE ->
                throw BackupArchiveException(prepared.problem)
        }
    }
}

/**
 * The restart a staged restore needs. iOS has no relaunch mechanism, so Quit now ends the process and the next launch
 * applies the restore; Later keeps the current library and settings until the next start.
 */
@Composable
internal fun IosBackupRestartDialog(graph: IosSessionGraph) {
    val requested by graph.backup.restartRequested.collectAsState()
    if (!requested) return
    AlertDialog(
        onDismissRequest = { graph.backup.dismissRestart() },
        title = { Text(stringResource(Res.string.ios_backup_restart_title)) },
        text = { Text(stringResource(Res.string.ios_backup_restart_message)) },
        confirmButton = {
            TextButton(onClick = { exit(0) }) {
                Text(stringResource(Res.string.ios_backup_restart_quit))
            }
        },
        dismissButton = {
            TextButton(onClick = { graph.backup.dismissRestart() }) {
                Text(stringResource(Res.string.ios_backup_restart_later))
            }
        },
    )
}

/**
 * The open-time report for a staged restore that failed: the previous library and settings are already back in
 * place, the pending set rests under the failed-staging name, and this dialog holds the notice until answered.
 */
@Composable
internal fun IosBackupRestoreFailedDialog(graph: IosSessionGraph) {
    val failed by graph.backup.restoreFailed.collectAsState()
    if (!failed) return
    AlertDialog(
        onDismissRequest = { graph.backup.dismissRestoreFailed() },
        title = { Text(stringResource(Res.string.ios_backup_restore_failed_title)) },
        text = { Text(stringResource(Res.string.ios_backup_restore_failed_message)) },
        confirmButton = {
            TextButton(onClick = { graph.backup.dismissRestoreFailed() }) {
                Text(stringResource(Res.string.ios_backup_restore_failed_dismiss))
            }
        },
    )
}

/**
 * The recovery surface for a restore that left the library unresolvable: the session runs on a throwaway
 * database, so this dialog replaces the normal state until the user picks a resolution. Both actions move the
 * displaced files into a dated quarantine — nothing is deleted — and the restart dialog then carries the
 * session out.
 */
@Composable
internal fun IosRestoreBlockedDialog(graph: IosSessionGraph) {
    val blocked by graph.backup.recoveryMode.collectAsState()
    if (!blocked) return
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { /* a blocked library resolves only by choosing */ },
        title = { Text(stringResource(Res.string.ios_restore_blocked_title)) },
        text = { Text(stringResource(Res.string.ios_restore_blocked_message)) },
        confirmButton = {
            if (graph.backup.previousLibraryAvailable) {
                TextButton(
                    onClick = { scope.launch { graph.backup.resolveRestoreRecovery(restorePrevious = true) } },
                ) {
                    Text(stringResource(Res.string.ios_restore_blocked_previous))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = { scope.launch { graph.backup.resolveRestoreRecovery(restorePrevious = false) } },
            ) {
                Text(stringResource(Res.string.ios_restore_blocked_empty))
            }
        },
    )
}
