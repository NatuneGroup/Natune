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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.DIRECTORY_NAME_KEY
import moe.rukamori.archivetune.shared.domain.settings.DIRECTORY_URI_KEY
import moe.rukamori.archivetune.shared.domain.settings.GoogleDriveSyncRepository_CUSTOM_DATE_KEY
import moe.rukamori.archivetune.shared.domain.settings.GoogleDriveSyncRepository_ENABLED_KEY
import moe.rukamori.archivetune.shared.domain.settings.GoogleDriveSyncRepository_FREQUENCY_KEY
import moe.rukamori.archivetune.shared.domain.settings.GoogleDriveSyncRepository_OVERWRITE_KEY
import moe.rukamori.archivetune.shared.domain.settings.LAST_SYNC_FAILED_KEY
import moe.rukamori.archivetune.shared.domain.settings.LAST_SYNC_MS_KEY
import moe.rukamori.archivetune.shared.domain.settings.LongPreferenceKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceEditor
import moe.rukamori.archivetune.shared.domain.settings.REMOTE_FOLDER_NAME_KEY
import moe.rukamori.archivetune.shared.domain.settings.REMOTE_FOLDER_URI_KEY
import moe.rukamori.archivetune.shared.domain.settings.ScheduledBackupRepository_CUSTOM_DATE_KEY
import moe.rukamori.archivetune.shared.domain.settings.ScheduledBackupRepository_ENABLED_KEY
import moe.rukamori.archivetune.shared.domain.settings.ScheduledBackupRepository_FREQUENCY_KEY
import moe.rukamori.archivetune.shared.domain.settings.ScheduledBackupRepository_OVERWRITE_KEY
import moe.rukamori.archivetune.shared.domain.settings.StringPreferenceKey
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The backup categories as this page presents them. The host maps each entry to the M7 backup category with the same
 * name. The page never builds an archive, so the ZIP layout, the codec and the account-secret classification stay in M7.
 */
enum class BackupSelectionCategory { LIBRARY, ACCOUNT, SETTINGS }

/** The host's handle for a validated archive. The page only carries the handle and never sees archive bytes. */
data class BackupRestoreCandidate(val handle: String, val categories: Set<BackupSelectionCategory>)

/** Why a chosen file cannot be restored. The host maps the validation results of the M7 codec into these. */
enum class BackupRestoreProblem { NOT_A_BACKUP, NO_RESTORABLE_DATA, CORRUPTED }

sealed interface BackupRestorePreparation {
    data class Ready(val candidate: BackupRestoreCandidate) : BackupRestorePreparation
    data object Cancelled : BackupRestorePreparation
    data class Rejected(val problem: BackupRestoreProblem) : BackupRestorePreparation
}

/** [SAVED] only after the host has written the file. [CANCELLED] means the user backed out of the save location. */
enum class BackupExportResult { SAVED, CANCELLED }

/** The frequency values the scheduler reads. They match the persisted names of the baseline schedule enum. */
private const val DEFAULT_FREQUENCY = "WEEKLY"
private const val CUSTOM_FREQUENCY = "CUSTOM"

private val FREQUENCY_OPTIONS: List<SettingsRow.Option> = listOf(
    SettingsRow.Option("DAILY", Res.string.m6_scheduled_backup_daily),
    SettingsRow.Option("WEEKLY", Res.string.m6_scheduled_backup_weekly),
    SettingsRow.Option("MONTHLY", Res.string.m6_scheduled_backup_monthly),
    SettingsRow.Option(CUSTOM_FREQUENCY, Res.string.m6_scheduled_backup_custom),
)

/** Which schedule a custom date belongs to. Scheduled backup and Drive sync keep separate keys. */
private enum class BackupDateTarget { SCHEDULED, GOOGLE_DRIVE }

/** The list of folder-sync exports, or the state of loading it. The picker never shows a stale list. */
private sealed interface ExportPickerState {
    data object Loading : ExportPickerState
    data object Failed : ExportPickerState
    data class Ready(val playlists: List<PlaylistChoice>) : ExportPickerState
}

/**
 * Manual backup and restore, plus the scheduled backup, Drive folder sync and playlist file rows. The host owns the save
 * and open pickers, the M7 archive codec, the transactional restore, the app restart after a restore and every Drive or
 * playlist transport. This page owns the category choices, the single-flight action state, the schedule choices and the
 * notices.
 *
 * A notice is emitted only after its host call returns, so a failed write, a failed restore or a cancelled sync never
 * reports success. A host exception becomes a failure notice and is never shown as completion. The scheduled backup,
 * Drive sync and playlist file rows appear only when [backupSyncHost] is supplied. The Google Drive app-data rows appear
 * only when [driveAppDataHost] is supplied, so the page never invents a folder, an account or a successful transfer.
 */
@Composable
fun BackupAndRestoreSettings(
    controller: SettingsController,
    createBackup: suspend (Set<BackupSelectionCategory>) -> BackupExportResult,
    prepareRestore: suspend () -> BackupRestorePreparation,
    discardPreparedBackup: suspend (handle: String) -> Unit,
    restoreBackup: suspend (handle: String, selected: Set<BackupSelectionCategory>) -> Unit,
    onNotice: (SettingsNotice) -> Unit,
    onFailure: () -> Unit,
    scrollTo: String? = null,
    resumed: Boolean = true,
    motionEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    backupSyncHost: BackupSyncHost? = null,
    driveAppDataHost: DriveAppDataBackupHost? = null,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var createOpen by remember { mutableStateOf(false) }
    var restoreCandidate by remember { mutableStateOf<BackupRestoreCandidate?>(null) }
    var rejected by remember { mutableStateOf<BackupRestoreProblem?>(null) }
    var driveSyncing by remember { mutableStateOf(false) }
    var driveHelpOpen by remember { mutableStateOf(false) }
    var pendingDriveFolder by remember { mutableStateOf<BackupFolderChoice?>(null) }
    var dateTarget by remember { mutableStateOf<BackupDateTarget?>(null) }
    var rejectedImport by remember { mutableStateOf<PlaylistFileProblem?>(null) }
    var importCandidate by remember { mutableStateOf<PlaylistFileCandidate?>(null) }
    var importTargets by remember { mutableStateOf<List<PlaylistChoice>>(emptyList()) }
    var importCreateName by remember { mutableStateOf<String?>(null) }
    var importProgress by remember { mutableStateOf<PlaylistImportProgress?>(null) }
    var importResult by remember { mutableStateOf<PlaylistImportResult?>(null) }
    var exportPicker by remember { mutableStateOf<ExportPickerState?>(null) }
    var driveFrequencyOpen by remember { mutableStateOf(false) }
    val driveHost = driveAppDataHost
    // The fallback is never written, so this state read is unconditional and keeps its slot when no host is bound.
    val driveSnapshot by (driveHost?.snapshot ?: UnboundDriveAppDataSnapshot).collectAsState()
    val preferences = snapshot
    if (preferences == null) {
        CircularProgressIndicator()
        return
    }
    val host = backupSyncHost
    val defaultDriveFolderName = stringResource(Res.string.m6_drive_sync_folder_default)
    val cloudDisclaimer = stringResource(Res.string.m6_drive_sync_cloud_disclaimer)

    /** One manual, folder or file operation at a time, so a second tap cannot start a second archive or import. */
    fun hostAction(failure: StringResource, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onNotice(SettingsNotice.Message(failure))
            } finally {
                busy = false
            }
        }
    }

    /** Cancel is not single-flight: the running call holds [busy], so the cancel tap must reach the host directly. */
    fun cancelDriveAppData(bound: DriveAppDataBackupHost) {
        scope.launch {
            try {
                bound.cancelRunning()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onNotice(SettingsNotice.Message(Res.string.m6_drive_app_data_failed))
            }
        }
    }

    fun runCreate(selected: Set<BackupSelectionCategory>) =
        hostAction(Res.string.m6_backup_create_failed) {
            if (createBackup(selected) == BackupExportResult.SAVED) {
                onNotice(SettingsNotice.Message(Res.string.m6_backup_create_success))
            }
        }

    fun startRestore() =
        hostAction(Res.string.m6_backup_restore_failed) {
            val prepared = prepareRestore()
            if (prepared is BackupRestorePreparation.Ready) {
                restoreCandidate = prepared.candidate
            } else if (prepared is BackupRestorePreparation.Rejected) {
                rejected = prepared.problem
            }
        }

    fun runRestore(handle: String, selected: Set<BackupSelectionCategory>) =
        hostAction(Res.string.m6_backup_restore_failed) {
            restoreBackup(handle, selected)
            onNotice(SettingsNotice.Message(Res.string.m6_backup_restore_success))
        }

    /** A backed-out restore releases the host's prepared archive so it does not stay in memory. */
    fun discard(handle: String) {
        scope.launch {
            try {
                discardPreparedBackup(handle)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onFailure()
            }
        }
    }

    /** A settings write that the page started itself. A store failure is reported as [failure], never as success. */
    fun saveSettings(failure: StringResource, change: PreferenceEditor.() -> Unit) =
        scope.launch {
            try {
                controller.edit(change)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onNotice(SettingsNotice.Message(failure))
            }
        }

    fun pickScheduledFolder(activeHost: BackupSyncHost) =
        hostAction(Res.string.m6_backup_folder_failed) {
            val choice = activeHost.pickScheduledBackupFolder() ?: return@hostAction
            controller.edit {
                this[DIRECTORY_URI_KEY] = choice.uri
                this[DIRECTORY_NAME_KEY] = choice.displayName
            }
            onNotice(SettingsNotice.Message(Res.string.m6_scheduled_backup_directory_saved))
        }

    suspend fun persistDriveFolder(choice: BackupFolderChoice) {
        controller.edit {
            this[REMOTE_FOLDER_URI_KEY] = choice.uri
            this[REMOTE_FOLDER_NAME_KEY] = choice.displayName.ifBlank { defaultDriveFolderName }
        }
    }

    /** A local-storage pick waits for the user's confirmation, because backups there never reach the cloud. */
    suspend fun offerDriveFolder(choice: BackupFolderChoice?) {
        if (choice == null) return
        if (isLocalStorageFolder(choice.uri)) pendingDriveFolder = choice else persistDriveFolder(choice)
    }

    fun pickDriveFolder(activeHost: BackupSyncHost) =
        hostAction(Res.string.m6_backup_folder_failed) {
            offerDriveFolder(activeHost.pickGoogleDriveFolder())
        }

    fun clearDriveFolder() =
        saveSettings(Res.string.m6_backup_folder_failed) {
            this[REMOTE_FOLDER_URI_KEY] = null
            this[REMOTE_FOLDER_NAME_KEY] = null
            this[GoogleDriveSyncRepository_ENABLED_KEY] = false
        }

    fun startDriveSync(activeHost: BackupSyncHost) {
        if (driveSyncing) return
        driveSyncing = true
        scope.launch {
            try {
                val notice = when (activeHost.syncGoogleDriveNow()) {
                    BackupSyncOutcome.COMPLETED -> Res.string.m6_drive_sync_succeeded
                    BackupSyncOutcome.TRANSIENT_FAILURE -> Res.string.m6_drive_sync_failed_transient
                    BackupSyncOutcome.PERMANENT_FAILURE -> Res.string.m6_drive_sync_failed_permanent
                    BackupSyncOutcome.NO_FOLDER -> Res.string.m6_drive_sync_folder_not_set
                    BackupSyncOutcome.NOT_SIGNED_IN -> Res.string.m6_drive_sync_not_signed_in
                    BackupSyncOutcome.CANCELLED -> null
                }
                if (notice != null) onNotice(SettingsNotice.Message(notice))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onNotice(SettingsNotice.Message(Res.string.m6_drive_sync_failed_transient))
            } finally {
                driveSyncing = false
            }
        }
    }

    fun runImport(activeHost: BackupSyncHost, candidate: PlaylistFileCandidate, target: PlaylistImportTarget) {
        importCandidate = null
        importCreateName = null
        hostAction(Res.string.m6_playlist_import_failed) {
            importProgress = PlaylistImportProgress(processed = 0, total = candidate.songCount, failed = 0)
            try {
                importResult = activeHost.importPlaylistFile(candidate.handle, target) { progress ->
                    importProgress = progress
                }
            } finally {
                importProgress = null
            }
        }
    }

    fun startImport(activeHost: BackupSyncHost, format: PlaylistFileFormat) =
        hostAction(Res.string.m6_playlist_import_failed) {
            when (val selection = activeHost.pickPlaylistFile(format)) {
                PlaylistFileSelection.Cancelled -> Unit
                is PlaylistFileSelection.Rejected -> {
                    rejectedImport = selection.problem
                }
                is PlaylistFileSelection.Ready -> {
                    val candidate = selection.candidate
                    try {
                        importTargets = activeHost.loadPlaylistImportTargets()
                    } catch (failure: Exception) {
                        activeHost.discardPlaylistFile(candidate.handle)
                        throw failure
                    }
                    importCandidate = candidate
                }
            }
        }

    fun cancelImport(activeHost: BackupSyncHost, candidate: PlaylistFileCandidate) {
        importCandidate = null
        importCreateName = null
        scope.launch {
            try {
                activeHost.discardPlaylistFile(candidate.handle)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onFailure()
            }
        }
    }

    fun openExportPicker(activeHost: BackupSyncHost) {
        exportPicker = ExportPickerState.Loading
        scope.launch {
            val loaded = try {
                ExportPickerState.Ready(activeHost.loadExportablePlaylists())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ExportPickerState.Failed
            }
            // A picker the user already closed stays closed, so a late load cannot reopen it.
            if (exportPicker == ExportPickerState.Loading) exportPicker = loaded
        }
    }

    fun exportPlaylist(activeHost: BackupSyncHost, playlist: PlaylistChoice) {
        exportPicker = null
        hostAction(Res.string.m6_playlist_export_failed) {
            when (activeHost.exportPlaylistCsv(playlist.id)) {
                is PlaylistExportOutcome.Done -> onNotice(SettingsNotice.Message(Res.string.m6_playlist_export_success))
                PlaylistExportOutcome.Cancelled -> Unit
            }
        }
    }

    // The custom date is picked outside the controller lock, so the host picker never blocks other settings writes.
    LaunchedEffect(dateTarget) {
        val target = dateTarget ?: return@LaunchedEffect
        val activeHost = host
        if (activeHost == null) {
            dateTarget = null
            return@LaunchedEffect
        }
        try {
            val (frequencyKey, dateKey) = dateKeysFor(target)
            val day = activeHost.pickBackupDay(preferences[dateKey]) ?: return@LaunchedEffect
            controller.edit {
                this[frequencyKey] = CUSTOM_FREQUENCY
                this[dateKey] = day
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            onNotice(SettingsNotice.Message(Res.string.m6_backup_date_failed))
        } finally {
            dateTarget = null
        }
    }

    val scheduledFrequency = preferences[ScheduledBackupRepository_FREQUENCY_KEY, DEFAULT_FREQUENCY]
    val scheduledDate = preferences[ScheduledBackupRepository_CUSTOM_DATE_KEY]
    val scheduledFolder = preferences[DIRECTORY_NAME_KEY]
    val scheduledFolderText = scheduledFolder ?: stringResource(Res.string.m6_scheduled_backup_directory_description)
    val driveFrequency = preferences[GoogleDriveSyncRepository_FREQUENCY_KEY, DEFAULT_FREQUENCY]
    val driveDate = preferences[GoogleDriveSyncRepository_CUSTOM_DATE_KEY]
    val driveFolderName = preferences[REMOTE_FOLDER_NAME_KEY]
    val driveFolderUri = preferences[REMOTE_FOLDER_URI_KEY]
    val driveConfigured = driveFolderName != null
    val driveProviderText = driveFolderUri?.takeIf { folderAuthority(it) != null }?.let { driveProviderLabel(it) }
    val driveFolderText = when {
        driveFolderName != null && driveProviderText != null ->
            stringResource(Res.string.m6_drive_sync_folder_with_provider, driveFolderName, driveProviderText)
        driveFolderName != null -> driveFolderName
        else -> stringResource(Res.string.m6_drive_sync_folder_not_set)
    }
    val lastSyncMs = preferences[LAST_SYNC_MS_KEY]
    val lastSyncFailed = preferences[LAST_SYNC_FAILED_KEY, false]
    val driveStatusText = when {
        driveSyncing -> stringResource(Res.string.m6_drive_sync_running)
        lastSyncFailed -> stringResource(Res.string.m6_drive_sync_last_sync_failed)
        lastSyncMs != null && host != null ->
            stringResource(Res.string.m6_drive_sync_last_synced, host.formatEpochMillis(lastSyncMs))
        else -> stringResource(Res.string.m6_drive_sync_never_synced)
    }

    // The Drive app-data rows keep the baseline order: after the folder sync rows and before the manual backup rows.
    val driveRows: List<SettingsRow> = if (driveHost != null) {
        driveAppDataRows(
            host = driveHost,
            snapshot = driveSnapshot,
            busy = busy,
            launch = { block -> hostAction(Res.string.m6_drive_app_data_failed, block) },
            onCancel = { cancelDriveAppData(driveHost) },
            onFrequencyOpen = { driveFrequencyOpen = true },
            onRestorePrepared = { prepared ->
                if (prepared is DriveAppDataRestorePreparation.Ready) {
                    restoreCandidate = prepared.candidate
                } else if (prepared is DriveAppDataRestorePreparation.Rejected) {
                    rejected = prepared.problem
                }
            },
        )
    } else {
        emptyList()
    }

    val rows = buildList {
        if (host != null) {
            add(SettingsRow.Header("scheduled_backup", Res.string.m6_scheduled_backup_title))
            add(
                SettingsRow.Toggle(
                    anchor = "scheduled_backup_enabled",
                    title = Res.string.m6_scheduled_backup_enabled,
                    key = ScheduledBackupRepository_ENABLED_KEY,
                    default = false,
                    descriptionFor = { on ->
                        if (on) Res.string.m6_scheduled_backup_enabled_on else Res.string.m6_scheduled_backup_enabled_off
                    },
                ),
            )
            add(
                SettingsRow.Choice(
                    anchor = "scheduled_backup_frequency",
                    title = Res.string.m6_scheduled_backup_frequency,
                    key = ScheduledBackupRepository_FREQUENCY_KEY,
                    default = DEFAULT_FREQUENCY,
                    options = FREQUENCY_OPTIONS,
                    write = { value ->
                        // Custom waits for the host date picker, so the stored frequency changes only with a date.
                        if (value == CUSTOM_FREQUENCY) {
                            dateTarget = BackupDateTarget.SCHEDULED
                        } else {
                            this[ScheduledBackupRepository_FREQUENCY_KEY] = value
                        }
                    },
                ),
            )
            if (scheduledFrequency == CUSTOM_FREQUENCY && scheduledDate != null) {
                add(
                    SettingsRow.Status(
                        anchor = "scheduled_backup_next",
                        title = Res.string.m6_scheduled_backup_next,
                        value = host.formatEpochDay(scheduledDate),
                        run = { dateTarget = BackupDateTarget.SCHEDULED },
                    ),
                )
            }
            add(
                SettingsRow.Status(
                    anchor = "scheduled_backup_directory",
                    title = Res.string.m6_scheduled_backup_directory,
                    value = scheduledFolderText,
                    run = { pickScheduledFolder(host) },
                ),
            )
            add(
                SettingsRow.Toggle(
                    anchor = "scheduled_backup_overwrite",
                    title = Res.string.m6_scheduled_backup_overwrite,
                    key = ScheduledBackupRepository_OVERWRITE_KEY,
                    default = false,
                    description = Res.string.m6_scheduled_backup_overwrite_description,
                    enabled = scheduledFolder != null,
                ),
            )

            add(SettingsRow.Header("google_drive_sync", Res.string.m6_drive_sync_title))
            // Display-only: no run lambda, so the paragraph is never a tap target and never a silent action.
            add(
                SettingsRow.Status(
                    anchor = "google_drive_sync_disclaimer",
                    title = Res.string.m6_drive_sync_cloud_label,
                    value = cloudDisclaimer,
                    fullText = true,
                ),
            )
            add(
                SettingsRow.Status(
                    anchor = "google_drive_sync_remote_folder",
                    title = Res.string.m6_drive_sync_folder,
                    value = driveFolderText,
                    run = { driveHelpOpen = true },
                ),
            )
            if (driveConfigured) {
                add(
                    SettingsRow.Action(
                        anchor = "google_drive_sync_clear_folder",
                        title = Res.string.m6_drive_sync_clear_folder,
                        run = { clearDriveFolder() },
                        description = Res.string.m6_drive_sync_clear_folder_description,
                    ),
                )
            }
            add(
                SettingsRow.Toggle(
                    anchor = "google_drive_sync_enabled",
                    title = Res.string.m6_drive_sync_enabled,
                    key = GoogleDriveSyncRepository_ENABLED_KEY,
                    default = false,
                    enabled = driveConfigured,
                    descriptionFor = { on ->
                        if (on) Res.string.m6_drive_sync_enabled_on else Res.string.m6_drive_sync_enabled_off
                    },
                ),
            )
            add(
                SettingsRow.Choice(
                    anchor = "google_drive_sync_frequency",
                    title = Res.string.m6_scheduled_backup_frequency,
                    key = GoogleDriveSyncRepository_FREQUENCY_KEY,
                    default = DEFAULT_FREQUENCY,
                    options = FREQUENCY_OPTIONS,
                    enabled = driveConfigured,
                    write = { value ->
                        if (value == CUSTOM_FREQUENCY) {
                            dateTarget = BackupDateTarget.GOOGLE_DRIVE
                        } else {
                            this[GoogleDriveSyncRepository_FREQUENCY_KEY] = value
                        }
                    },
                ),
            )
            if (driveFrequency == CUSTOM_FREQUENCY && driveDate != null) {
                add(
                    SettingsRow.Status(
                        anchor = "google_drive_sync_custom_date",
                        title = Res.string.m6_drive_sync_next,
                        value = host.formatEpochDay(driveDate),
                        run = { dateTarget = BackupDateTarget.GOOGLE_DRIVE },
                        enabled = driveConfigured,
                    ),
                )
            }
            add(
                SettingsRow.Toggle(
                    anchor = "google_drive_sync_overwrite",
                    title = Res.string.m6_drive_sync_overwrite,
                    key = GoogleDriveSyncRepository_OVERWRITE_KEY,
                    default = false,
                    description = Res.string.m6_drive_sync_overwrite_description,
                    enabled = driveConfigured,
                ),
            )
            add(
                SettingsRow.Status(
                    anchor = "google_drive_sync_run_now",
                    title = Res.string.m6_drive_sync_run_now,
                    value = driveStatusText,
                    run = { startDriveSync(host) },
                    enabled = driveConfigured && !driveSyncing,
                ),
            )
        }
        addAll(driveRows)

        add(SettingsRow.Header("backup_internal", Res.string.m6_backup_section_internal))
        add(
            SettingsRow.Action(
                anchor = "backup_create",
                title = Res.string.m6_backup_action_create,
                run = { createOpen = true },
                enabled = !busy,
                description = Res.string.m6_backup_create_description,
            ),
        )
        add(
            SettingsRow.Action(
                anchor = "backup_restore",
                title = Res.string.m6_backup_action_restore,
                run = { startRestore() },
                enabled = !busy,
                description = Res.string.m6_backup_restore_description,
            ),
        )
        if (host != null) {
            add(
                SettingsRow.Action(
                    anchor = "import_online",
                    title = Res.string.m6_playlist_import_m3u_title,
                    run = { startImport(host, PlaylistFileFormat.M3U) },
                    enabled = !busy,
                    description = Res.string.m6_playlist_import_m3u_format,
                ),
            )
            add(
                SettingsRow.Action(
                    anchor = "import_csv",
                    title = Res.string.m6_playlist_import_csv_title,
                    run = { startImport(host, PlaylistFileFormat.CSV) },
                    enabled = !busy,
                    description = Res.string.m6_playlist_import_csv_format,
                ),
            )
            add(
                SettingsRow.Action(
                    anchor = "export_playlist_csv",
                    title = Res.string.m6_playlist_export_csv_title,
                    run = { openExportPicker(host) },
                    enabled = !busy,
                    description = Res.string.m6_playlist_export_csv_description,
                ),
            )
        }
    }

    SettingsPage(
        rows = rows,
        preferences = preferences,
        controller = controller,
        scrollTo = scrollTo,
        resumed = resumed,
        motionEnabled = motionEnabled,
        onFailure = onFailure,
        modifier = modifier,
    )

    if (createOpen) {
        BackupCategoryDialog(
            title = Res.string.m6_backup_options_title,
            confirmLabel = Res.string.m6_backup_action_create,
            choices = BackupSelectionCategory.entries,
            onConfirm = { selected ->
                createOpen = false
                runCreate(selected)
            },
            onDismiss = { createOpen = false },
        )
    }

    restoreCandidate?.let { candidate ->
        // Only categories the archive actually carries are offered, so ACCOUNT appears only when it is present.
        BackupCategoryDialog(
            title = Res.string.m6_backup_restore_options_title,
            confirmLabel = Res.string.m6_backup_action_restore,
            choices = BackupSelectionCategory.entries.filter { it in candidate.categories },
            onConfirm = { selected ->
                restoreCandidate = null
                runRestore(candidate.handle, selected)
            },
            onDismiss = {
                restoreCandidate = null
                discard(candidate.handle)
            },
        )
    }

    rejected?.let { problem ->
        AlertDialog(
            onDismissRequest = { rejected = null },
            title = { Text(stringResource(Res.string.m6_backup_restore_failed)) },
            text = { Text(stringResource(problem.messageResource())) },
            confirmButton = {
                TextButton(onClick = { rejected = null }) { Text(stringResource(Res.string.m6_backup_ok)) }
            },
        )
    }

    rejectedImport?.let { problem ->
        AlertDialog(
            onDismissRequest = { rejectedImport = null },
            title = { Text(stringResource(Res.string.m6_playlist_import_failed)) },
            text = { Text(stringResource(problem.messageResource())) },
            confirmButton = {
                TextButton(onClick = { rejectedImport = null }) { Text(stringResource(Res.string.m6_backup_ok)) }
            },
        )
    }

    val driveFrequency = driveSnapshot?.frequency
    if (driveHost != null && driveFrequency != null && driveFrequencyOpen) {
        DriveAppDataFrequencyDialog(
            selected = driveFrequency,
            onSelect = { frequency ->
                // The choice closes the dialog first, so the host write runs under the page's single-flight guard.
                driveFrequencyOpen = false
                hostAction(Res.string.m6_drive_app_data_failed) { driveHost.setFrequency(frequency) }
            },
            onDismiss = { driveFrequencyOpen = false },
        )
    }

    if (driveHelpOpen) {
        // Shown before the system picker so the user knows Drive needs its app to appear in the picker.
        AlertDialog(
            onDismissRequest = { driveHelpOpen = false },
            title = { Text(stringResource(Res.string.m6_drive_sync_pick_help_title)) },
            text = {
                Text(
                    text = stringResource(Res.string.m6_drive_sync_pick_help_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    driveHelpOpen = false
                    host?.let { pickDriveFolder(it) }
                }) { Text(stringResource(Res.string.m6_drive_sync_pick_help_open)) }
            },
            dismissButton = {
                TextButton(onClick = { driveHelpOpen = false }) { Text(stringResource(Res.string.m6_cancel)) }
            },
        )
    }

    pendingDriveFolder?.let { choice ->
        AlertDialog(
            onDismissRequest = { pendingDriveFolder = null },
            title = { Text(stringResource(Res.string.m6_drive_sync_local_title)) },
            text = {
                Text(
                    text = stringResource(Res.string.m6_drive_sync_local_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDriveFolder = null
                    hostAction(Res.string.m6_backup_folder_failed) { persistDriveFolder(choice) }
                }) { Text(stringResource(Res.string.m6_drive_sync_local_use)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingDriveFolder = null
                    host?.let { pickDriveFolder(it) }
                }) { Text(stringResource(Res.string.m6_drive_sync_local_try_again)) }
            },
        )
    }

    importCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { host?.let { cancelImport(it, candidate) } },
            title = { Text(stringResource(Res.string.m6_playlist_picker_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TextButton(onClick = { importCreateName = "" }) {
                        Text(stringResource(Res.string.m6_playlist_create))
                    }
                    importTargets.forEach { target ->
                        TextButton(onClick = { host?.let { runImport(it, candidate, PlaylistImportTarget.Existing(target.id)) } }) {
                            Text(target.title)
                        }
                    }
                    TextButton(onClick = { host?.let { runImport(it, candidate, PlaylistImportTarget.LikedSongs) } }) {
                        Text(stringResource(Res.string.m6_playlist_liked_songs))
                    }
                    Text(
                        text = stringResource(Res.string.m6_playlist_synced_note),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { host?.let { cancelImport(it, candidate) } }) {
                    Text(stringResource(Res.string.m6_cancel))
                }
            },
        )
    }

    importCreateName?.let { name ->
        AlertDialog(
            onDismissRequest = { importCreateName = null },
            title = { Text(stringResource(Res.string.m6_playlist_create)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { importCreateName = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(Res.string.m6_playlist_name)) },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        val candidate = importCandidate
                        val activeHost = host
                        if (candidate != null && activeHost != null) {
                            runImport(activeHost, candidate, PlaylistImportTarget.Created(name.trim()))
                        }
                    },
                ) { Text(stringResource(Res.string.m6_save)) }
            },
            dismissButton = {
                TextButton(onClick = { importCreateName = null }) { Text(stringResource(Res.string.m6_cancel)) }
            },
        )
    }

    importProgress?.let { progress ->
        // No dismiss action: the host is still matching songs, and a closed dialog would hide that work.
        val fraction = if (progress.total > 0) progress.processed.toFloat() / progress.total else 0f
        AlertDialog(
            onDismissRequest = {},
            text = {
                Column {
                    Text(
                        text = if (progress.processed == 0) {
                            stringResource(Res.string.m6_playlist_import_preparing)
                        } else {
                            stringResource(
                                Res.string.m6_playlist_import_progress,
                                progress.processed,
                                progress.total,
                                progress.failed,
                            )
                        },
                    )
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
            },
            confirmButton = {},
        )
    }

    importResult?.let { result ->
        AlertDialog(
            onDismissRequest = { importResult = null },
            title = { Text(stringResource(Res.string.m6_playlist_import_complete)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(Res.string.m6_playlist_import_total, result.total))
                    Text(
                        text = stringResource(Res.string.m6_playlist_import_imported, result.imported),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (result.failedTitles.isNotEmpty()) {
                        Text(
                            text = stringResource(Res.string.m6_playlist_import_failed_count, result.failedTitles.size),
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = stringResource(Res.string.m6_playlist_import_failed_items),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        result.failedTitles.forEach { title ->
                            Text(text = "• $title", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { importResult = null }) { Text(stringResource(Res.string.m6_backup_ok)) }
            },
        )
    }

    exportPicker?.let { state ->
        AlertDialog(
            onDismissRequest = { exportPicker = null },
            title = { Text(stringResource(Res.string.m6_playlist_picker_title)) },
            text = {
                when (state) {
                    ExportPickerState.Loading -> Text(stringResource(Res.string.m6_playlist_export_loading))
                    ExportPickerState.Failed -> TextButton(onClick = { host?.let { openExportPicker(it) } }) {
                        Text(stringResource(Res.string.m6_playlist_export_load_failed))
                    }
                    is ExportPickerState.Ready -> if (state.playlists.isEmpty()) {
                        Text(stringResource(Res.string.m6_playlist_export_empty))
                    } else {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            state.playlists.forEach { playlist ->
                                TextButton(onClick = { host?.let { exportPlaylist(it, playlist) } }) {
                                    Text("${playlist.title} · ${stringResource(Res.string.m6_playlist_track_count, playlist.trackCount)}")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { exportPicker = null }) { Text(stringResource(Res.string.m6_cancel)) }
            },
        )
    }
}

/** The domain keys for a schedule's frequency and its custom date, so one picker serves both schedules. */
private fun dateKeysFor(target: BackupDateTarget): Pair<StringPreferenceKey, LongPreferenceKey> =
    when (target) {
        BackupDateTarget.SCHEDULED -> ScheduledBackupRepository_FREQUENCY_KEY to ScheduledBackupRepository_CUSTOM_DATE_KEY
        BackupDateTarget.GOOGLE_DRIVE -> GoogleDriveSyncRepository_FREQUENCY_KEY to GoogleDriveSyncRepository_CUSTOM_DATE_KEY
    }

/**
 * A checklist of backup categories. The confirm action stays disabled while nothing is selected, because an empty
 * archive or restore would report success for no data.
 */
@Composable
private fun BackupCategoryDialog(
    title: StringResource,
    confirmLabel: StringResource,
    choices: List<BackupSelectionCategory>,
    onConfirm: (Set<BackupSelectionCategory>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(choices) { mutableStateOf(choices.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column {
                choices.forEach { category ->
                    val checked = category in selected
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (checked) selected - category else selected + category
                                }
                                .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(stringResource(category.titleResource()))
                            Text(
                                text = stringResource(category.descriptionResource()),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = selected.isNotEmpty(), onClick = { onConfirm(selected) }) {
                Text(stringResource(confirmLabel))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.m6_cancel)) }
        },
    )
}

/** The provider label for a picked folder. A local pick is named as local, so it is never mistaken for cloud storage. */
@Composable
private fun driveProviderLabel(uri: String): String =
    when (backupFolderProvider(uri)) {
        BackupFolderProvider.DRIVE -> stringResource(Res.string.m6_drive_sync_provider_drive)
        BackupFolderProvider.DROPBOX -> stringResource(Res.string.m6_drive_sync_provider_dropbox)
        BackupFolderProvider.NEXTCLOUD -> stringResource(Res.string.m6_drive_sync_provider_nextcloud)
        BackupFolderProvider.ONEDRIVE -> stringResource(Res.string.m6_drive_sync_provider_onedrive)
        BackupFolderProvider.LOCAL -> stringResource(Res.string.m6_drive_sync_provider_local)
        BackupFolderProvider.UNKNOWN -> stringResource(Res.string.m6_drive_sync_provider_unknown)
    }

private fun BackupSelectionCategory.titleResource(): StringResource =
    when (this) {
        BackupSelectionCategory.LIBRARY -> Res.string.m6_backup_category_library
        BackupSelectionCategory.ACCOUNT -> Res.string.m6_backup_category_account
        BackupSelectionCategory.SETTINGS -> Res.string.m6_backup_category_settings
    }

private fun BackupSelectionCategory.descriptionResource(): StringResource =
    when (this) {
        BackupSelectionCategory.LIBRARY -> Res.string.m6_backup_category_library_desc
        BackupSelectionCategory.ACCOUNT -> Res.string.m6_backup_category_account_desc
        BackupSelectionCategory.SETTINGS -> Res.string.m6_backup_category_settings_desc
    }

private fun BackupRestoreProblem.messageResource(): StringResource =
    when (this) {
        BackupRestoreProblem.NOT_A_BACKUP -> Res.string.m6_backup_invalid_file
        BackupRestoreProblem.NO_RESTORABLE_DATA -> Res.string.m6_backup_no_restorable_data
        BackupRestoreProblem.CORRUPTED -> Res.string.m6_backup_corrupted
    }

private fun PlaylistFileProblem.messageResource(): StringResource =
    when (this) {
        PlaylistFileProblem.NOT_A_PLAYLIST -> Res.string.m6_playlist_problem_not_playlist
        PlaylistFileProblem.EMPTY -> Res.string.m6_playlist_problem_empty
        PlaylistFileProblem.UNREADABLE -> Res.string.m6_playlist_problem_unreadable
    }
