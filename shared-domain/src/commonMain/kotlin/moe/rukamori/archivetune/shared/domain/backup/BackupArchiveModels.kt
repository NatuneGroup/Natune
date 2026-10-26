package moe.rukamori.archivetune.shared.domain.backup

/**
 * Sections an archive can carry. Hosts map their own selection types onto these one to one. Which
 * preference keys belong to which section is decided by the codec that writes them, not here.
 */
enum class BackupDataCategory { LIBRARY, ACCOUNT, SETTINGS }

/** Why an archive cannot be restored. Hosts turn these into their own user-facing text. */
enum class BackupArchiveProblem {
    /** The bytes are not a readable archive or carry no archive entries. */
    NOT_A_BACKUP,

    /** A readable archive without any section that could be restored. */
    NO_RESTORABLE_DATA,

    /** Entries break the archive format: names, SQLite header, schema identity or settings root. */
    CORRUPTED,

    /** The expanded size exceeds the limit or the destination has no room for it. */
    INSUFFICIENT_STORAGE,

    /** A prepared handle is unknown, expired, already applied, or belongs to another owner. */
    UNAVAILABLE,
}

/** Raised by the archive gateway with a typed reason. The message repeats the problem name for logs only. */
class BackupArchiveException(
    val problem: BackupArchiveProblem,
    cause: Throwable? = null,
) : Exception(problem.name, cause)

/** Sections a validated archive can restore. */
data class BackupArchiveInspection(val categories: Set<BackupDataCategory>)

/** Result of applying a restore through the transactional path. */
data class BackupRestoreOutcome(val restartRequired: Boolean)

/** Result of validating an archive and registering it for a later restore. */
sealed interface PreparedArchive {
    data class Ready(
        val handle: String,
        val categories: Set<BackupDataCategory>,
    ) : PreparedArchive

    data class Rejected(val problem: BackupArchiveProblem) : PreparedArchive
}

/**
 * The existing archive path, bound by the host to the platform codec and the transactional restore.
 * File IO, database replacement, locks, rollback and restart prompts stay behind this port, so common
 * code never touches them directly.
 */
interface BackupArchiveGateway {
    /** Builds an archive containing the selected sections. */
    suspend fun create(selected: Set<BackupDataCategory>): ByteArray

    /**
     * Validates an archive without applying it and reports the sections it can restore. Throws
     * [BackupArchiveException] for anything that is not a restorable archive.
     */
    suspend fun inspect(archive: ByteArray): BackupArchiveInspection

    /**
     * Applies the selected sections of an archive that was inspected earlier. The implementation validates
     * again and rolls back on failure, so a rejected restore never leaves a partial import behind.
     */
    suspend fun restore(archive: ByteArray, selected: Set<BackupDataCategory>): BackupRestoreOutcome
}
