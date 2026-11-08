@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.shared.data.playlistimport.ParsedPlaylistFile
import moe.rukamori.archivetune.shared.data.playlistimport.PlaylistImportOwner
import moe.rukamori.archivetune.shared.data.playlistimport.YouTubePlaylistImportSearch
import moe.rukamori.archivetune.shared.data.platform.ios.iosNowMillis
import moe.rukamori.archivetune.shared.domain.backup.BackupDataCategory
import moe.rukamori.archivetune.shared.domain.platform.FileHandle
import moe.rukamori.archivetune.shared.domain.playlistexport.PlaylistExportDestination
import moe.rukamori.archivetune.shared.domain.playlistimport.PlaylistImportFormat
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.IosHost
import moe.rukamori.archivetune.shared.ui.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import platform.Foundation.*

private val LastFolderBackup = LongPreferenceKey("iosFolderBackupLastRun")
private const val FOLDER_PREFIX = "localfs:"

/** Files-provider folders (including an installed Drive provider); scheduling lasts only while this process runs. */
internal class IosBackupSync(private val graph: IosSessionGraph, private val host: IosHost) : BackupSyncHost {
    private val importer = PlaylistImportOwner(graph.library, YouTubePlaylistImportSearch(graph.youtubeContent))
    private val candidates = mutableMapOf<String, ParsedPlaylistFile>()
    private val gate = Mutex()
    var dateRequest by mutableStateOf<CompletableDeferred<Long?>?>(null)
        private set
    var initialDay: Long? = null
        private set
    private var job: Job? = null

    override suspend fun pickScheduledBackupFolder(): BackupFolderChoice? = pickFolder()
    override suspend fun pickGoogleDriveFolder(): BackupFolderChoice? = pickFolder()
    private suspend fun pickFolder(): BackupFolderChoice? = host.files.pickDirectory(host.viewController)?.let {
        BackupFolderChoice(FOLDER_PREFIX + it.value, "Files")
    }
    override suspend fun pickBackupDay(current: Long?): Long? {
        val request = CompletableDeferred<Long?>()
        initialDay = current; dateRequest = request
        return try { request.await() } finally { if (dateRequest == request) dateRequest = null }
    }
    fun selectDay(day: Long?) { dateRequest?.complete(day) }

    override suspend fun syncGoogleDriveNow(): BackupSyncOutcome = writeFolder(REMOTE_FOLDER_URI_KEY, GoogleDriveSyncRepository_OVERWRITE_KEY)

    private suspend fun writeFolder(key: StringPreferenceKey, overwriteKey: BooleanPreferenceKey): BackupSyncOutcome = gate.withLock {
        val prefs = graph.settings.snapshot()
        val value = prefs[key]?.takeIf { it.startsWith(FOLDER_PREFIX) } ?: return@withLock BackupSyncOutcome.NO_FOLDER
        try {
            val archive = graph.backup.archives.create(BackupDataCategory.entries.toSet())
            val name = if (prefs[overwriteKey] == true) "Natune.backup" else "Natune.${iosNowMillis()}.backup"
            host.files.writeChildAtomic(FileHandle(value.removePrefix(FOLDER_PREFIX)), name, archive)
            graph.settings.edit {
                if (key == REMOTE_FOLDER_URI_KEY) { this[LAST_SYNC_MS_KEY] = iosNowMillis(); this[LAST_SYNC_FAILED_KEY] = false }
                else this[LastFolderBackup] = iosNowMillis()
            }
            BackupSyncOutcome.COMPLETED
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            if (key == REMOTE_FOLDER_URI_KEY) graph.settings.edit { this[LAST_SYNC_FAILED_KEY] = true }
            BackupSyncOutcome.TRANSIENT_FAILURE
        }
    }
    fun start() {
        job = graph.scope.launch {
            while (isActive) {
                val prefs = graph.settings.snapshot()
                val now = iosNowMillis()
                if (prefs[ScheduledBackupRepository_ENABLED_KEY] == true && prefs[LastFolderBackup] == null)
                    graph.settings.edit { this[LastFolderBackup] = now }
                if (prefs[GoogleDriveSyncRepository_ENABLED_KEY] == true && prefs[LAST_SYNC_MS_KEY] == null)
                    graph.settings.edit { this[LAST_SYNC_MS_KEY] = now }
                if (prefs[ScheduledBackupRepository_ENABLED_KEY] == true && due(prefs,
                    ScheduledBackupRepository_FREQUENCY_KEY, ScheduledBackupRepository_CUSTOM_DATE_KEY, prefs[LastFolderBackup] ?: now, now)) {
                    if (writeFolder(DIRECTORY_URI_KEY, ScheduledBackupRepository_OVERWRITE_KEY) == BackupSyncOutcome.COMPLETED)
                        graph.settings.edit { this[LastFolderBackup] = now }
                }
                if (prefs[GoogleDriveSyncRepository_ENABLED_KEY] == true && due(prefs,
                    GoogleDriveSyncRepository_FREQUENCY_KEY, GoogleDriveSyncRepository_CUSTOM_DATE_KEY, prefs[LAST_SYNC_MS_KEY] ?: now, now))
                    syncGoogleDriveNow()
                delay(30_000L)
            }
        }
    }
    private fun due(prefs: PreferenceSnapshot, frequency: StringPreferenceKey, custom: LongPreferenceKey, last: Long, now: Long): Boolean {
        val day = (now + iosOffsetMsAt(now)) / 86_400_000L
        val target = day * 86_400_000L + 7_200_000L - iosOffsetMsAt(now)
        if (now < target || last >= target) return false
        return when (prefs[frequency] ?: "WEEKLY") {
            "DAILY" -> true
            "WEEKLY" -> (day + 3) % 7 == 0L
            "MONTHLY" -> moe.rukamori.archivetune.shared.ui.host.civilFromDays(day).third == 1
            "CUSTOM" -> prefs[custom] == day
            else -> false
        }
    }
    fun close() { job?.cancel(); dateRequest?.cancel(); candidates.clear() }

    override suspend fun pickPlaylistFile(format: PlaylistFileFormat): PlaylistFileSelection {
        val handle = host.files.pickDocument(host.viewController) ?: return PlaylistFileSelection.Cancelled
        return try {
            val raw = host.files.read(handle, 4L * 1024 * 1024).decodeToString()
            val parsed = importer.parse(PlaylistImportFormat.valueOf(format.name), raw)
            val id = NSUUID().UUIDString
            candidates[id] = parsed
            PlaylistFileSelection.Ready(PlaylistFileCandidate(id, parsed.songCount))
        } catch (_: Exception) { PlaylistFileSelection.Rejected(PlaylistFileProblem.UNREADABLE) }
    }
    override suspend fun discardPlaylistFile(handle: String) { candidates.remove(handle) }
    override suspend fun loadPlaylistImportTargets(): List<PlaylistChoice> = graph.libraryContent.playlistRows().first()
        .filter { it.editable }.map { PlaylistChoice(it.item.ref.id, it.item.title, it.songCount) }
    override suspend fun loadExportablePlaylists(): List<PlaylistChoice> = graph.playlistExport.observeExportable().first()
        .map { PlaylistChoice(it.ref.id, it.name, it.songCount) }
    override suspend fun importPlaylistFile(handle: String, target: PlaylistImportTarget,
        onProgress: (PlaylistImportProgress) -> Unit): PlaylistImportResult {
        val parsed = candidates.remove(handle) ?: error("Playlist selection expired")
        val destination = when (target) {
            is PlaylistImportTarget.Existing -> moe.rukamori.archivetune.shared.domain.playlistimport.PlaylistImportTarget.Existing(target.playlistId)
            is PlaylistImportTarget.Created -> moe.rukamori.archivetune.shared.domain.playlistimport.PlaylistImportTarget.Created(target.name)
            PlaylistImportTarget.LikedSongs -> moe.rukamori.archivetune.shared.domain.playlistimport.PlaylistImportTarget.LikedSongs
        }
        val result = importer.importInto(parsed, destination) { onProgress(PlaylistImportProgress(it.processed, it.total, it.failed)) }
        return PlaylistImportResult(result.total, result.imported, result.failedTitles)
    }
    override suspend fun exportPlaylistCsv(playlistId: String): PlaylistExportOutcome {
        var count = 0
        val saved = iosExportStagedFile(host, "$playlistId.csv") { path ->
            graph.playlistExport.exportCsv(ContentRef(ContentProvider.LOCAL.id, ContentKind.PLAYLIST, playlistId),
                object : PlaylistExportDestination { override suspend fun write(bytes: ByteArray) { iosWriteBytes(path, bytes) } })
            count = loadExportablePlaylists().first { it.id == playlistId }.trackCount
        }
        return if (saved == true) PlaylistExportOutcome.Done(count) else PlaylistExportOutcome.Cancelled
    }
    override fun formatEpochDay(epochDay: Long): String = formatEpochMillis(epochDay * 86_400_000L)
    override fun formatEpochMillis(epochMillis: Long): String = NSDateFormatter().apply { dateStyle = NSDateFormatterMediumStyle }
        .stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IosBackupSyncDateDialog(sync: IosBackupSync) {
    sync.dateRequest?.let { request ->
        val state = rememberDatePickerState(initialSelectedDateMillis = sync.initialDay?.times(86_400_000L))
        DatePickerDialog(onDismissRequest = { sync.selectDay(null) },
            confirmButton = { TextButton(enabled = state.selectedDateMillis?.div(86_400_000L)?.let { it >= iosNowMillis() / 86_400_000L } == true, onClick = { sync.selectDay(state.selectedDateMillis?.div(86_400_000L)) }) {
                Text(stringResource(Res.string.ios_lyrics_save))
            } }, dismissButton = { TextButton(onClick = { sync.selectDay(null) }) { Text(stringResource(Res.string.m6_cancel)) } }) {
            DatePicker(state)
        }
    }
}
