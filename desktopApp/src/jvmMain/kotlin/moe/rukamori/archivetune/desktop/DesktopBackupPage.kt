package moe.rukamori.archivetune.desktop

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.Dispatchers
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
 * Settings > Backup and restore on this host: manual archives through the system file chooser and Google Drive app data
 * backup. The scheduled folder backup and Drive folder sync rows of Android need a document-tree owner this host does
 * not have, so the page shows them only where [BackupAndRestoreSettings] has a host for them, which is not here.
 */
@Composable
internal fun DesktopBackupRestorePage(
    graph: DesktopSessionGraph,
    controller: SettingsController,
    onNotice: (SettingsNotice) -> Unit,
    onFailure: () -> Unit,
    scrollTo: String?,
) {
    val backup = graph.backup
    BackupAndRestoreSettings(
        controller = controller,
        createBackup = { selected -> saveDesktopBackup(graph, selected) },
        prepareRestore = { openDesktopBackup(graph) },
        discardPreparedBackup = backup.binding::discardPrepared,
        restoreBackup = backup.binding::restorePrepared,
        onNotice = onNotice,
        onFailure = onFailure,
        scrollTo = scrollTo,
        driveAppDataHost = backup.drive,
    )
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
    val archive = graph.backup.archives.create(selected.map { it.toDataCategory() }.toSet())
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
    return BackupExportResult.SAVED
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
private suspend fun chooseBackupFile(mode: Int, suggestedName: String?): File? = suspendCancellableCoroutine { continuation ->
    EventQueue.invokeLater {
        val chosen = try {
            val title = DesktopStrings[if (mode == FileDialog.SAVE) "backup_save_pick" else "backup_open_pick"]
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
