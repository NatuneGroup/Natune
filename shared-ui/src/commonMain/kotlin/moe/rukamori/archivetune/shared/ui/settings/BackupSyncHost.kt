package moe.rukamori.archivetune.shared.ui.settings

/**
 * A folder the host picked. The page stores only this URI string and its display name, never any file contents. The host
 * takes the persistable URI permission before it returns the choice, and releases the grant of a replaced or cleared folder.
 */
data class BackupFolderChoice(val uri: String, val displayName: String)

/**
 * How a host Drive sync run finished. [COMPLETED] is the only outcome reported as an upload. Every other outcome has its
 * own notice, so a missing folder, a signed-out account or a failed upload is never shown as a completed backup.
 */
enum class BackupSyncOutcome { COMPLETED, CANCELLED, NO_FOLDER, NOT_SIGNED_IN, TRANSIENT_FAILURE, PERMANENT_FAILURE }

/** The playlist file formats the page imports. The host runs the M7 codec for the chosen format. */
enum class PlaylistFileFormat { CSV, M3U }

/** Why a chosen playlist file cannot be imported. The host maps its codec validation into these. */
enum class PlaylistFileProblem { NOT_A_PLAYLIST, EMPTY, UNREADABLE }

/**
 * A parsed playlist file the host keeps in memory until the user picks a target or backs out. The page carries only the
 * [handle] and the [songCount], never the parsed song text.
 */
data class PlaylistFileCandidate(val handle: String, val songCount: Int)

/** The result of opening a playlist file. Only [Ready] leads to a target choice. */
sealed interface PlaylistFileSelection {
    data class Ready(val candidate: PlaylistFileCandidate) : PlaylistFileSelection

    /** The user backed out of the file picker. */
    data object Cancelled : PlaylistFileSelection

    data class Rejected(val problem: PlaylistFileProblem) : PlaylistFileSelection
}

/**
 * Where imported songs go. [Created] makes a new playlist with the name the user typed. [LikedSongs] adds the songs to
 * the liked list, the same target the baseline offers.
 */
sealed interface PlaylistImportTarget {
    data class Existing(val playlistId: String) : PlaylistImportTarget

    data class Created(val name: String) : PlaylistImportTarget

    data object LikedSongs : PlaylistImportTarget
}

/** A playlist offered as an import target or an export source. */
data class PlaylistChoice(val id: String, val title: String, val trackCount: Int)

/** Progress while the host matches and writes songs. [processed] counts finished songs out of [total]. */
data class PlaylistImportProgress(val processed: Int, val total: Int, val failed: Int)

/**
 * The import result. [imported] is what was actually written, and [failedTitles] names every song that could not be
 * matched, so a partial import is never reported as complete.
 */
data class PlaylistImportResult(val total: Int, val imported: Int, val failedTitles: List<String>)

/** The result of one CSV export. [Cancelled] means the user backed out of the destination picker. */
sealed interface PlaylistExportOutcome {
    data class Done(val count: Int) : PlaylistExportOutcome

    data object Cancelled : PlaylistExportOutcome
}

/**
 * The host transports behind the scheduled-backup, Drive-sync and playlist-file rows. Each call returns only after the
 * host has finished or been cancelled. A thrown exception becomes a failure notice on the page, so the page never
 * invents a folder, a sign-in or a successful write. The rows appear only when a host is supplied.
 *
 * The page stores the schedule choices, folder URIs and overwrite flags itself through the settings controller, using the
 * persisted keys the scheduler reads. The host reschedules from those keys and never receives the page's state. The host
 * also writes the last-sync keys after every Drive run, whether the run was manual or scheduled.
 */
interface BackupSyncHost {
    /** The system folder picker for scheduled backups. Null when the user backed out. */
    suspend fun pickScheduledBackupFolder(): BackupFolderChoice?

    /** The folder picker for the Drive sync target. Null when the user backed out. */
    suspend fun pickGoogleDriveFolder(): BackupFolderChoice?

    /** The host date picker, seeded with [current] as an epoch day. It must refuse past days. Null when the user backed out. */
    suspend fun pickBackupDay(current: Long?): Long?

    /** Runs one Drive upload now to the stored folder. A missing account is an outcome, not an exception. */
    suspend fun syncGoogleDriveNow(): BackupSyncOutcome

    /** Opens the file picker for [format] and parses the chosen file with the M7 codec. */
    suspend fun pickPlaylistFile(format: PlaylistFileFormat): PlaylistFileSelection

    /** Releases a parsed candidate the user backed out of, so its songs do not stay in memory. */
    suspend fun discardPlaylistFile(handle: String)

    /** Every playlist the user can import into. */
    suspend fun loadPlaylistImportTargets(): List<PlaylistChoice>

    /**
     * Matches each song of the candidate online, writes the matches to [target] and reports progress on the calling
     * coroutine. A candidate is consumed when this returns or throws, and the host discards it itself on failure.
     */
    suspend fun importPlaylistFile(
        handle: String,
        target: PlaylistImportTarget,
        onProgress: (PlaylistImportProgress) -> Unit,
    ): PlaylistImportResult

    /** The playlists that have songs and can be exported. An empty list means nothing can be exported. */
    suspend fun loadExportablePlaylists(): List<PlaylistChoice>

    /** Writes [playlistId] as a CSV file to a destination the user picks. */
    suspend fun exportPlaylistCsv(playlistId: String): PlaylistExportOutcome

    /** Renders an epoch day in the host locale. It is pure and persists nothing. */
    fun formatEpochDay(epochDay: Long): String

    /** Renders an epoch-millisecond timestamp in the host locale. It is pure and persists nothing. */
    fun formatEpochMillis(epochMillis: Long): String
}
