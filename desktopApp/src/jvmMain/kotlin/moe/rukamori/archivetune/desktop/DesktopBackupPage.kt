package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.domain.backup.BackupArchiveException
import moe.rukamori.archivetune.shared.domain.backup.BackupArchiveProblem
import moe.rukamori.archivetune.shared.domain.backup.BackupDataCategory
import moe.rukamori.archivetune.shared.domain.backup.DriveAppDataLimits
import moe.rukamori.archivetune.shared.domain.backup.PreparedArchive
import moe.rukamori.archivetune.shared.ui.settings.BackupAndRestoreSettings
import moe.rukamori.archivetune.shared.ui.settings.BackupExportResult
import moe.rukamori.archivetune.shared.ui.settings.BackupRestoreCandidate
import moe.rukamori.archivetune.shared.ui.settings.BackupRestorePreparation
import moe.rukamori.archivetune.shared.ui.settings.BackupRestoreProblem
import moe.rukamori.archivetune.shared.ui.settings.BackupSelectionCategory
import moe.rukamori.archivetune.shared.ui.settings.SettingsController
import moe.rukamori.archivetune.shared.ui.settings.SettingsNotice

/**
 * Settings > Backup and restore on this host: manual archives through the system file chooser, Google Drive app data
 * backup, scheduled folder backups and the Drive folder sync — folders are filesystem paths on this host, picked
 * through a directory chooser and synced on the Android cadence rules by an in-process scheduler. Playlist file
 * import/export runs through the shared owners. Below the shared page, Export for Android writes an archive in the
 * Android app's own format.
 */
@Composable
internal fun DesktopBackupRestorePage(
    graph: DesktopSessionGraph,
    controller: SettingsController,
    onNotice: (SettingsNotice) -> Unit,
    onFailure: () -> Unit,
    scrollTo: String?,
    show: (String) -> Unit,
) {
    val backup = graph.backup
    Column(Modifier.fillMaxSize()) {
        BackupAndRestoreSettings(
            controller = controller,
            createBackup = { selected -> saveDesktopBackup(graph, selected) },
            prepareRestore = { openDesktopBackup(graph) },
            discardPreparedBackup = backup.binding::discardPrepared,
            restoreBackup = backup.binding::restorePrepared,
            onNotice = onNotice,
            onFailure = onFailure,
            scrollTo = scrollTo,
            modifier = Modifier.weight(1f),
            backupSyncHost = backup.sync,
            driveAppDataHost = backup.drive,
        )
        HorizontalDivider()
        DesktopAndroidBackupExport(graph, show)
    }
}

/** The Export for Android row and its section picker. Account values are left out unless the user ticks them. */
@Composable
private fun DesktopAndroidBackupExport(graph: DesktopSessionGraph, show: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf(BackupDataCategory.LIBRARY, BackupDataCategory.SETTINGS)) }
    ListItem(
        modifier = Modifier.testTag("setting:android_backup_export").clickable(enabled = !busy) { picking = true },
        headlineContent = { Text(DesktopSettingsText["android_backup_export"]) },
        supportingContent = { Text(DesktopSettingsText["android_backup_export_description"]) },
    )
    if (!picking) return
    AlertDialog(
        onDismissRequest = { picking = false },
        title = { Text(DesktopSettingsText["android_backup_export"]) },
        text = {
            Column {
                ANDROID_EXPORT_SECTIONS.forEach { (category, labelKey) ->
                    fun toggle() { selected = if (category in selected) selected - category else selected + category }
                    Row(Modifier.fillMaxWidth().clickable { toggle() }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = category in selected, onCheckedChange = { toggle() })
                        Text(DesktopSettingsText[labelKey])
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = selected.isNotEmpty(), onClick = {
                picking = false
                busy = true
                val chosen = selected
                scope.launch {
                    try {
                        if (saveAndroidBackup(graph, chosen)) show(DesktopSettingsText["android_backup_export_done"])
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        desktopDiagnostic("android backup export failed: ${failure.javaClass.simpleName}")
                        show(DesktopSettingsText["android_backup_export_failed"])
                    } finally {
                        busy = false
                    }
                }
            }) { Text(DesktopSettingsText["android_backup_export_save"]) }
        },
        dismissButton = { TextButton(onClick = { picking = false }) { Text(DesktopStrings["cancel"]) } },
    )
}

private val ANDROID_EXPORT_SECTIONS = listOf(
    BackupDataCategory.LIBRARY to "android_backup_export_library",
    BackupDataCategory.SETTINGS to "android_backup_export_settings",
    BackupDataCategory.ACCOUNT to "android_backup_export_account",
)

/** False when the user cancels the chooser. */
private suspend fun saveAndroidBackup(graph: DesktopSessionGraph, selected: Set<BackupDataCategory>): Boolean {
    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
    val file = chooseBackupFile(FileDialog.SAVE, "${DesktopStrings["app_name"]}_android_$stamp.backup", "android_backup_export_pick")
        ?: return false
    writeReplacing(file, graph.backup.archives.createAndroid(selected))
    return true
}

/**
 * Offers the restart a staged restore needs. Restart now quits and starts this app again once the old process has
 * gone; Later keeps the current library and settings until the next start applies the restore.
 */
@Composable
internal fun DesktopBackupRestartDialog(graph: DesktopSessionGraph, quit: () -> Unit) {
    val requested by graph.backup.restartRequested.collectAsState()
    var manual by remember { mutableStateOf(false) }
    if (!requested) return
    AlertDialog(
        onDismissRequest = { graph.backup.dismissRestart() },
        title = { Text(DesktopStrings["backup_restart_title"]) },
        text = { Text(DesktopStrings[if (manual) "backup_restart_manual" else "backup_restart_message"]) },
        confirmButton = {
            if (!manual) {
                TextButton(onClick = {
                    if (DesktopRelaunch.scheduleAfterExit()) quit() else manual = true
                }) { Text(DesktopStrings["backup_restart_now"]) }
            }
        },
        dismissButton = {
            TextButton(onClick = { graph.backup.dismissRestart() }) {
                Text(DesktopStrings[if (manual) "close" else "later"])
            }
        },
    )
}

private suspend fun saveDesktopBackup(graph: DesktopSessionGraph, selected: Set<BackupSelectionCategory>): BackupExportResult {
    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
    val file = chooseBackupFile(FileDialog.SAVE, "${DesktopStrings["app_name"]}_$stamp.backup")
        ?: return BackupExportResult.CANCELLED
    writeReplacing(file, graph.backup.archives.create(selected.map { it.toDataCategory() }.toSet()))
    return BackupExportResult.SAVED
}

private suspend fun writeReplacing(file: File, archive: ByteArray) {
    withContext(Dispatchers.IO) {
        val target = file.toPath().toAbsolutePath()
        val parent = requireNotNull(target.parent)
        // The archive is written beside the target and renamed over it, so a failed write never leaves half a backup.
        val temporary = Files.createTempFile(parent, ".${target.fileName}", ".tmp")
        try {
            Files.write(temporary, archive)
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

private suspend fun openDesktopBackup(graph: DesktopSessionGraph): BackupRestorePreparation {
    val file = chooseBackupFile(FileDialog.LOAD, null) ?: return BackupRestorePreparation.Cancelled
    val archive = withContext(Dispatchers.IO) {
        val path: Path = file.toPath()
        if (Files.size(path) > DriveAppDataLimits.MAX_ARCHIVE_BYTES) {
            throw BackupArchiveException(BackupArchiveProblem.INSUFFICIENT_STORAGE)
        }
        Files.readAllBytes(path)
    }
    return when (val prepared = graph.backup.restores.prepare(archive)) {
        is PreparedArchive.Ready -> BackupRestorePreparation.Ready(
            BackupRestoreCandidate(prepared.handle, prepared.categories.map { it.toSelection() }.toSet()),
        )
        is PreparedArchive.Rejected -> when (prepared.problem) {
            BackupArchiveProblem.NOT_A_BACKUP -> BackupRestorePreparation.Rejected(BackupRestoreProblem.NOT_A_BACKUP)
            BackupArchiveProblem.NO_RESTORABLE_DATA -> BackupRestorePreparation.Rejected(BackupRestoreProblem.NO_RESTORABLE_DATA)
            BackupArchiveProblem.CORRUPTED -> BackupRestorePreparation.Rejected(BackupRestoreProblem.CORRUPTED)
            // Not a property of the file: the page reports these as a failed restore, not as a bad backup.
            BackupArchiveProblem.INSUFFICIENT_STORAGE, BackupArchiveProblem.UNAVAILABLE ->
                throw BackupArchiveException(prepared.problem)
        }
    }
}

/** A save or open chooser on the event queue, as the playlist export opens its chooser. Null when the user cancels. */
private suspend fun chooseBackupFile(mode: Int, suggestedName: String?, titleKey: String? = null): File? = suspendCancellableCoroutine { continuation ->
    EventQueue.invokeLater {
        val chosen = try {
            val title = titleKey?.let { DesktopSettingsText[it] }
                ?: DesktopStrings[if (mode == FileDialog.SAVE) "backup_save_pick" else "backup_open_pick"]
            val dialog = FileDialog(null as Frame?, title, mode)
            if (suggestedName != null) dialog.file = suggestedName
            dialog.isMultipleMode = false
            // A modal file dialog blocks this event-queue task until it closes, while AWT keeps pumping events.
            dialog.isVisible = true
            val name = dialog.file
            val folder = dialog.directory
            dialog.dispose()
            if (name == null || folder == null) null else File(folder, name)
        } catch (failure: Exception) {
            desktopDiagnostic("file chooser unavailable: ${failure.javaClass.simpleName}")
            null
        }
        if (continuation.isActive) continuation.resume(chosen)
    }
}

private fun BackupSelectionCategory.toDataCategory(): BackupDataCategory = when (this) {
    BackupSelectionCategory.LIBRARY -> BackupDataCategory.LIBRARY
    BackupSelectionCategory.ACCOUNT -> BackupDataCategory.ACCOUNT
    BackupSelectionCategory.SETTINGS -> BackupDataCategory.SETTINGS
}

private fun BackupDataCategory.toSelection(): BackupSelectionCategory = when (this) {
    BackupDataCategory.LIBRARY -> BackupSelectionCategory.LIBRARY
    BackupDataCategory.ACCOUNT -> BackupSelectionCategory.ACCOUNT
    BackupDataCategory.SETTINGS -> BackupSelectionCategory.SETTINGS
}

/**
 * Starts this app again after the current process exits. The new process is told the old process id and waits for it
 * to end, because the single-instance lock is only free once the old process is gone.
 */
internal object DesktopRelaunch {
    const val AFTER_ARGUMENT = "--relaunch-after="
    private const val WAIT_SECONDS = 30L

    /** Registers the relaunch. False when the command that started this process cannot be determined. */
    fun scheduleAfterExit(): Boolean {
        val command = command() ?: return false
        val pid = ProcessHandle.current().pid()
        Runtime.getRuntime().addShutdownHook(Thread({
            try {
                ProcessBuilder(command + "$AFTER_ARGUMENT$pid")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            } catch (_: Exception) {
                // The process is already exiting; the user opens the app again and the staged restore still applies.
            }
        }, "archivetune-relaunch"))
        return true
    }

    /** Blocks until the process named by a relaunch argument has exited, or a bounded wait passes. */
    fun awaitPrevious(args: Array<String>) {
        val pid = args.firstOrNull { it.startsWith(AFTER_ARGUMENT) }?.removePrefix(AFTER_ARGUMENT)?.toLongOrNull() ?: return
        try {
            ProcessHandle.of(pid).ifPresent { previous -> previous.onExit().get(WAIT_SECONDS, TimeUnit.SECONDS) }
        } catch (_: Exception) {
            // A wait that times out continues; the single-instance check still refuses a second live owner.
        }
    }

    private fun command(): List<String>? {
        // A packaged launcher names itself; starting it again keeps the bundled runtime and options.
        System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() && Files.isExecutable(Path.of(it)) }?.let {
            return listOf(it)
        }
        val info = ProcessHandle.current().info()
        val executable = info.command().orElse(null) ?: return null
        val arguments = info.arguments().orElse(null) ?: return null
        return listOf(executable) + arguments.filterNot { it.startsWith(AFTER_ARGUMENT) }
    }
}
