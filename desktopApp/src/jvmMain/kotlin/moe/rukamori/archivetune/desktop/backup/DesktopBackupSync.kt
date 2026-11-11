package moe.rukamori.archivetune.desktop.backup

import java.awt.EventQueue
import java.awt.Frame
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.text.DateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.JSpinner
import javax.swing.SpinnerDateModel
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.data.playlistimport.PlaylistImportOwner
import moe.rukamori.archivetune.shared.data.playlistexport.PlaylistExportOwner
import moe.rukamori.archivetune.shared.domain.backup.createSyncBackup
import moe.rukamori.archivetune.shared.domain.playlistexport.PlaylistExportDestination
import moe.rukamori.archivetune.shared.domain.playlistimport.PlaylistImportException
import moe.rukamori.archivetune.shared.domain.playlistimport.PlaylistImportFormat
import moe.rukamori.archivetune.shared.domain.playlistimport.PlaylistImportProblem
import moe.rukamori.archivetune.shared.domain.playlistimport.PlaylistImportTarget
import moe.rukamori.archivetune.shared.domain.settings.LAST_SYNC_FAILED_KEY
import moe.rukamori.archivetune.shared.domain.settings.LAST_SYNC_MS_KEY
import moe.rukamori.archivetune.shared.domain.settings.GoogleDriveSyncRepository_CUSTOM_DATE_KEY
import moe.rukamori.archivetune.shared.domain.settings.GoogleDriveSyncRepository_ENABLED_KEY
import moe.rukamori.archivetune.shared.domain.settings.GoogleDriveSyncRepository_FREQUENCY_KEY
import moe.rukamori.archivetune.shared.domain.settings.GoogleDriveSyncRepository_OVERWRITE_KEY
import moe.rukamori.archivetune.shared.domain.settings.REMOTE_FOLDER_URI_KEY
import moe.rukamori.archivetune.shared.domain.settings.DIRECTORY_URI_KEY
import moe.rukamori.archivetune.shared.domain.settings.ScheduledBackupRepository_CUSTOM_DATE_KEY
import moe.rukamori.archivetune.shared.domain.settings.ScheduledBackupRepository_ENABLED_KEY
import moe.rukamori.archivetune.shared.domain.settings.ScheduledBackupRepository_FREQUENCY_KEY
import moe.rukamori.archivetune.shared.domain.settings.ScheduledBackupRepository_OVERWRITE_KEY
import moe.rukamori.archivetune.shared.ui.screens.PlaylistCollectionRow
import moe.rukamori.archivetune.shared.ui.settings.BackupFolderChoice
import moe.rukamori.archivetune.shared.ui.settings.BackupSyncHost
import moe.rukamori.archivetune.shared.ui.settings.BackupSyncOutcome
import moe.rukamori.archivetune.shared.ui.settings.PlaylistChoice
import moe.rukamori.archivetune.shared.ui.settings.PlaylistFileCandidate
import moe.rukamori.archivetune.shared.ui.settings.PlaylistFileFormat
import moe.rukamori.archivetune.shared.ui.settings.PlaylistFileProblem
import moe.rukamori.archivetune.shared.ui.settings.PlaylistFileSelection
import moe.rukamori.archivetune.shared.ui.settings.PlaylistExportOutcome
import moe.rukamori.archivetune.shared.ui.settings.PlaylistImportProgress
import moe.rukamori.archivetune.shared.ui.settings.PlaylistImportResult
import moe.rukamori.archivetune.shared.ui.settings.PlaylistImportTarget as UiImportTarget
import moe.rukamori.archivetune.shared.ui.settings.VaultSettingsStore

/** Scheme under which desktop folder picks are stored, so the page's local/cloud check reads them as local. */
internal const val LOCAL_FOLDER_SCHEME = "localfs"

/** The cadence values the page offers; identical to the Android repository's enum names. */
private const val CUSTOM_FREQUENCY = "CUSTOM"

/**
 * The folder and file transports behind the backup-sync rows on this host, plus the in-process driver that replaces
 * the Android workers. Folders are filesystem paths stored under the `localfs:` scheme; the shared page keeps the
 * local/cloud distinction, so a picked folder is confirmed as local before it is saved. One scheduler ticks both
 * stored schedules — the folder backup and the Drive folder sync — on the Android cadence rules (a run lands at
 * 02:00 local the chosen day; a custom date that already passed is skipped).
 */
internal class DesktopBackupSync(
    private val scope: CoroutineScope,
    private val settings: VaultSettingsStore,
    private val archives: DesktopBackupArchiveGateway,
    private val playlistExport: PlaylistExportOwner,
    private val playlistImport: PlaylistImportOwner,
    private val playlistRows: suspend () -> List<PlaylistCollectionRow>,
    private val report: (String) -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : BackupSyncHost {

    private val importLock = Mutex()
    private val importFiles = mutableMapOf<String, ParsedHolder>()

    /** A parsed import file kept under its chooser handle until the target is picked. */
    private class ParsedHolder(val format: PlaylistImportFormat, val raw: String)

    override suspend fun pickScheduledBackupFolder(): BackupFolderChoice? =
        pickFolder(DesktopStrings["scheduled_backup_folder_pick"])

    override suspend fun pickGoogleDriveFolder(): BackupFolderChoice? =
        pickFolder(DesktopStrings["drive_sync_folder_pick"])

    /** A date spinner seeded with [current] (or today); days before today are refused by looping the dialog. */
    override suspend fun pickBackupDay(current: Long?): Long? = suspendCancellableCoroutine { continuation ->
        EventQueue.invokeLater {
            try {
                val today = LocalDate.now()
                val initial = current?.let(LocalDate::ofEpochDay)?.takeIf { !it.isBefore(today) } ?: today
                var picked: Long? = null
                while (true) {
                    val spinner = JSpinner(SpinnerDateModel(
                        Date.from(initial.atStartOfDay(ZoneId.systemDefault()).toInstant()), null, null,
                        java.util.Calendar.DAY_OF_MONTH,
                    ))
                    spinner.editor = JSpinner.DateEditor(spinner, "yyyy-MM-dd")
                    val answer = JOptionPane.showConfirmDialog(
                        null as Frame?, spinner, DesktopStrings["scheduled_backup_day_pick"],
                        JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE,
                    )
                    if (answer != JOptionPane.OK_OPTION) break
                    val day = (spinner.value as Date).toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
                    if (day.isBefore(today)) continue
                    picked = day.toEpochDay()
                    break
                }
                if (continuation.isActive) continuation.resume(picked)
            } catch (failure: Exception) {
                report("date picker unavailable: ${failure.javaClass.simpleName}")
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    /**
     * Writes one archive into the stored folder with the Android naming rules: `<app>.backup` when overwriting, else
     * `<app>_<yyyyMMddHHmmss>.backup`. An overwrite removes the stale file first, as the SAF path did, so a failed
     * write never reads as a fresh one.
     */
    override suspend fun syncGoogleDriveNow(): BackupSyncOutcome {
        val snapshot = settings.snapshots.value ?: return BackupSyncOutcome.TRANSIENT_FAILURE
        val folder = snapshot[REMOTE_FOLDER_URI_KEY]?.let(::folderPath)
            ?: return BackupSyncOutcome.NO_FOLDER
        val overwrite = snapshot[GoogleDriveSyncRepository_OVERWRITE_KEY, false]
        val outcome = runCatchingOutcome {
            writeArchive(folder, overwrite)
        }
        settings.edit {
            this[LAST_SYNC_MS_KEY] = nowMillis()
            this[LAST_SYNC_FAILED_KEY] = outcome != BackupSyncOutcome.COMPLETED
        }
        return outcome
    }

    /** The file chooser for one playlist file, parsed at once so the picker dialog reports the song count. */
    override suspend fun pickPlaylistFile(format: PlaylistFileFormat): PlaylistFileSelection {
        val extension = if (format == PlaylistFileFormat.CSV) "csv" else "m3u"
        val file = chooseFile(DesktopStrings["playlist_import_pick"], setOf(extension), save = false)
            ?: return PlaylistFileSelection.Cancelled
        val path = file.toPath().toAbsolutePath()
        val raw = try {
            withContext(Dispatchers.IO) { Files.readString(path) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return PlaylistFileSelection.Rejected(PlaylistFileProblem.UNREADABLE)
        }
        val parsed = try {
            playlistImport.parse(
                if (format == PlaylistFileFormat.CSV) PlaylistImportFormat.CSV else PlaylistImportFormat.M3U,
                raw,
            )
        } catch (failure: PlaylistImportException) {
            return PlaylistFileSelection.Rejected(
                when (failure.problem) {
                    PlaylistImportProblem.NOT_A_PLAYLIST -> PlaylistFileProblem.NOT_A_PLAYLIST
                    PlaylistImportProblem.EMPTY -> PlaylistFileProblem.EMPTY
                    PlaylistImportProblem.UNREADABLE -> PlaylistFileProblem.UNREADABLE
                },
            )
        }
        importLock.withLock {
            importFiles[path.toString()] = ParsedHolder(
                if (format == PlaylistFileFormat.CSV) PlaylistImportFormat.CSV else PlaylistImportFormat.M3U,
                raw,
            )
        }
        return PlaylistFileSelection.Ready(PlaylistFileCandidate(path.toString(), parsed.songCount))
    }

    override suspend fun discardPlaylistFile(handle: String) {
        importLock.withLock { importFiles.remove(handle) }
    }

    override suspend fun loadPlaylistImportTargets(): List<PlaylistChoice> =
        playlistRows().map { PlaylistChoice(it.item.ref.id, it.item.title, it.songCount) }

    override suspend fun importPlaylistFile(
        handle: String,
        target: UiImportTarget,
        onProgress: (PlaylistImportProgress) -> Unit,
    ): PlaylistImportResult {
        val held = importLock.withLock { importFiles[handle] } ?: throw IllegalArgumentException("Unknown file handle")
        val parsed = playlistImport.parse(held.format, held.raw)
        val domainTarget = when (target) {
            is UiImportTarget.Existing -> PlaylistImportTarget.Existing(target.playlistId)
            is UiImportTarget.Created -> PlaylistImportTarget.Created(target.name)
            UiImportTarget.LikedSongs -> PlaylistImportTarget.LikedSongs
        }
        val result = playlistImport.importInto(parsed, domainTarget) { progress ->
            onProgress(PlaylistImportProgress(progress.processed, progress.total, progress.failed))
        }
        importLock.withLock { importFiles.remove(handle) }
        return PlaylistImportResult(result.total, result.imported, result.failedTitles)
    }

    override suspend fun loadExportablePlaylists(): List<PlaylistChoice> =
        playlistExport.observeExportable().first().map { PlaylistChoice(it.ref.id, it.name, it.songCount) }

    override suspend fun exportPlaylistCsv(playlistId: String): PlaylistExportOutcome {
        val exportable = playlistExport.observeExportable().first().firstOrNull { it.ref.id == playlistId }
            ?: return PlaylistExportOutcome.Cancelled
        val file = chooseFile(exportable.suggestedFileName, setOf("csv"), save = true)
            ?: return PlaylistExportOutcome.Cancelled
        playlistExport.exportCsv(exportable.ref, object : PlaylistExportDestination {
            override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) { file.writeBytes(bytes) }
        })
        return PlaylistExportOutcome.Done(exportable.songCount)
    }

    override fun formatEpochDay(epochDay: Long): String =
        LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ISO_LOCAL_DATE)

    override fun formatEpochMillis(epochMillis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMillis))

    /** The scheduler loop: both stored schedules, one hourly tick. Runs only while this process lives. */
    fun start() {
        scope.launch { runSchedules() }
    }

    private suspend fun runSchedules() {
        val folderPlan = FolderBackupPlan()
        val drivePlan = FolderBackupPlan()
        while (currentCoroutineContext().isActive) {
            val snapshot = settings.snapshots.value
            if (snapshot != null) {
                val now = LocalDateTime.now()
                folderPlan.arm(
                    enabled = snapshot[ScheduledBackupRepository_ENABLED_KEY, false],
                    frequency = snapshot[ScheduledBackupRepository_FREQUENCY_KEY, "WEEKLY"],
                    customDateEpochDay = snapshot[ScheduledBackupRepository_CUSTOM_DATE_KEY],
                    folder = snapshot[DIRECTORY_URI_KEY]?.let(::folderPath),
                    now = now,
                )
                if (folderPlan.due(now)) {
                    val folder = requireNotNull(folderPlan.folder)
                    runCatchingOutcome { writeArchive(folder, snapshot[ScheduledBackupRepository_OVERWRITE_KEY, false]) }
                }
                drivePlan.arm(
                    enabled = snapshot[GoogleDriveSyncRepository_ENABLED_KEY, false],
                    frequency = snapshot[GoogleDriveSyncRepository_FREQUENCY_KEY, "WEEKLY"],
                    customDateEpochDay = snapshot[GoogleDriveSyncRepository_CUSTOM_DATE_KEY],
                    folder = snapshot[REMOTE_FOLDER_URI_KEY]?.let(::folderPath),
                    now = now,
                )
                if (drivePlan.due(now)) {
                    val folder = requireNotNull(drivePlan.folder)
                    val outcome = runCatchingOutcome {
                        writeArchive(folder, snapshot[GoogleDriveSyncRepository_OVERWRITE_KEY, false])
                    }
                    settings.edit {
                        this[LAST_SYNC_MS_KEY] = nowMillis()
                        this[LAST_SYNC_FAILED_KEY] = outcome != BackupSyncOutcome.COMPLETED
                    }
                }
            }
            delay(CHECK_INTERVAL_MS)
        }
    }

    /**
     * One schedule's in-process arm state, matching the Android worker's reschedule-on-settings-change: [arm] recomputes
     * the next run the first time the stored settings change. A run lands at 02:00 local the chosen day (tomorrow for
     * DAILY, +1 week for WEEKLY, +1 month for MONTHLY, the picked day for CUSTOM); a past custom date is skipped. The
     * state only lives as long as the process, as the Drive app-data scheduler does.
     */
    private class FolderBackupPlan {
        var armed: ArmKey? = null
        var nextRun: LocalDateTime? = null
        var folder: Path? = null

        private data class ArmKey(val enabled: Boolean, val frequency: String, val customDateEpochDay: Long?, val folder: Path?)

        fun arm(enabled: Boolean, frequency: String, customDateEpochDay: Long?, folder: Path?, now: LocalDateTime) {
            val key = ArmKey(enabled, frequency, customDateEpochDay, folder)
            if (key == armed) return
            armed = key
            this.folder = folder
            nextRun = if (!enabled || folder == null) {
                null
            } else {
                val day = when (frequency) {
                    "DAILY" -> now.toLocalDate().plusDays(1)
                    "WEEKLY" -> now.toLocalDate().plusWeeks(1)
                    "MONTHLY" -> now.toLocalDate().plusMonths(1)
                    CUSTOM_FREQUENCY -> customDateEpochDay?.let(LocalDate::ofEpochDay)
                        ?.takeUnless { it.isBefore(now.toLocalDate()) } ?: return
                    else -> null
                }
                day?.let { LocalDateTime.of(it, BACKUP_TIME) }
            }
        }

        fun due(now: LocalDateTime): Boolean {
            val run = nextRun ?: return false
            if (now.isBefore(run)) return false
            nextRun = null
            armed = null
            return true
        }
    }

    /** Creates the archive and writes it atomically under [folder] with the Android naming rule. */
    private suspend fun writeArchive(folder: Path, overwrite: Boolean) {
        val appName = DesktopStrings["app_name"]
        val fileName = if (overwrite) {
            "$appName.backup"
        } else {
            "$appName.${LocalDateTime.now().format(FILE_TIMESTAMP_FORMATTER)}.backup"
        }
        val target = folder.resolve(fileName)
        val archive = createSyncBackup(archives)
        withContext(Dispatchers.IO + NonCancellable) {
            Files.createDirectories(folder)
            if (overwrite) Files.deleteIfExists(target)
            val temporary = Files.createTempFile(folder, ".$fileName", ".tmp")
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

    private suspend fun runCatchingOutcome(write: suspend () -> Unit): BackupSyncOutcome = try {
        write()
        BackupSyncOutcome.COMPLETED
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: SecurityException) {
        report("backup folder access refused: ${failure.message}")
        BackupSyncOutcome.PERMANENT_FAILURE
    } catch (failure: Exception) {
        report("folder backup write failed: ${failure.javaClass.simpleName}")
        BackupSyncOutcome.TRANSIENT_FAILURE
    }

    /** A directory chooser; the pick is stored under the `localfs:` scheme the page reads as a local folder. */
    private suspend fun pickFolder(title: String): BackupFolderChoice? = suspendCancellableCoroutine { continuation ->
        EventQueue.invokeLater {
            val choice = try {
                val chooser = JFileChooser()
                chooser.dialogTitle = title
                chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                chooser.isMultiSelectionEnabled = false
                if (chooser.showOpenDialog(null as Frame?) != JFileChooser.APPROVE_OPTION) null else {
                    val picked = chooser.selectedFile
                    BackupFolderChoice("$LOCAL_FOLDER_SCHEME:${picked.absolutePath}", picked.name.ifBlank { picked.absolutePath })
                }
            } catch (failure: Exception) {
                report("folder chooser unavailable: ${failure.javaClass.simpleName}")
                null
            }
            if (continuation.isActive) continuation.resume(choice)
        }
    }

    /** A file save or open chooser filtered to [extensions]. Null when the user cancels. */
    private suspend fun chooseFile(suggestion: String, extensions: Set<String>, save: Boolean): File? =
        suspendCancellableCoroutine { continuation ->
            EventQueue.invokeLater {
                val chosen = try {
                    val chooser = JFileChooser()
                    chooser.fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
                        extensions.joinToString(",").uppercase(), *extensions.toTypedArray(),
                    )
                    if (save) {
                        chooser.selectedFile = File(chooser.currentDirectory, suggestion)
                        if (chooser.showSaveDialog(null as Frame?) != JFileChooser.APPROVE_OPTION) null else chooser.selectedFile
                    } else {
                        if (chooser.showOpenDialog(null as Frame?) != JFileChooser.APPROVE_OPTION) null else chooser.selectedFile
                    }
                } catch (failure: Exception) {
                    report("file chooser unavailable: ${failure.javaClass.simpleName}")
                    null
                }
                if (continuation.isActive) continuation.resume(chosen)
            }
        }

    private fun folderPath(uri: String): Path? =
        uri.takeIf { it.startsWith("$LOCAL_FOLDER_SCHEME:") }?.removePrefix("$LOCAL_FOLDER_SCHEME:")?.let(Path::of)

    private companion object {
        val FILE_TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
        val BACKUP_TIME: LocalTime = LocalTime.of(2, 0)
        const val CHECK_INTERVAL_MS = 60 * 60 * 1000L
    }
}
