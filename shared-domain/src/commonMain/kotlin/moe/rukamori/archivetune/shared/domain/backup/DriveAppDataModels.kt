/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.domain.backup

/** Cadence of scheduled Drive uploads in days. [MANUAL] runs no periodic upload. */
enum class DriveAppDataFrequency(val days: Int) {
    MANUAL(0),
    DAILY(1),
    WEEKLY(7),
    MONTHLY(30),
}

/** Classified Drive failures. Hosts map them to localized text, so common code carries no strings. */
enum class DriveAppDataBackupFailure {
    AUTHORIZATION,
    CONFIGURATION,
    NETWORK,
    QUOTA,
    MISSING,
    INVALID,
    STORAGE,
    UNKNOWN,
}

/**
 * The Drive failure that a typed archive problem reports. A size limit stays STORAGE. An archive that is not a backup,
 * has no restorable data or is corrupted is INVALID. An operational refusal (an unknown, applied or busy prepared
 * archive) is UNKNOWN, so a refusal is never reported as success.
 */
fun BackupArchiveProblem.toDriveFailure(): DriveAppDataBackupFailure = when (this) {
    BackupArchiveProblem.INSUFFICIENT_STORAGE -> DriveAppDataBackupFailure.STORAGE
    BackupArchiveProblem.NOT_A_BACKUP, BackupArchiveProblem.NO_RESTORABLE_DATA, BackupArchiveProblem.CORRUPTED ->
        DriveAppDataBackupFailure.INVALID
    BackupArchiveProblem.UNAVAILABLE -> DriveAppDataBackupFailure.UNKNOWN
}

/** What the owner is doing right now, for progress labels. */
enum class DriveAppDataActivity { CHECKING, PREPARING, UPLOADING, DOWNLOADING }

/** Network requirement for scheduled and manual uploads. */
enum class DriveAppDataNetwork { UNMETERED, CONNECTED }

/** The newest backup in appDataFolder, as Drive reports it. */
data class DriveAppDataBackupMetadata(
    val id: String,
    val modifiedAtMillis: Long,
    val sizeBytes: Long,
    val md5Hex: String,
)

/** Persisted Drive preferences and the last known backup for the connected account. */
data class DriveAppDataSettings(
    val accountName: String? = null,
    val frequency: DriveAppDataFrequency = DriveAppDataFrequency.MANUAL,
    val wifiOnly: Boolean = true,
    val backup: DriveAppDataBackupMetadata? = null,
    val lastFailure: DriveAppDataBackupFailure? = null,
    val lastFailureAtMillis: Long = 0L,
)

/**
 * Observable owner state. [operationFailure] is the outcome of the last user action and is cleared when the
 * next action starts. [DriveAppDataSettings.lastFailure] keeps the outcome of the last scheduled or manual upload.
 */
data class DriveAppDataState(
    val settings: DriveAppDataSettings = DriveAppDataSettings(),
    val activity: DriveAppDataActivity? = null,
    val progressPercent: Int? = null,
    val operationFailure: DriveAppDataBackupFailure? = null,
)

/** Periodic schedule that the scheduler port applies. [Off] removes the periodic work. */
sealed interface DriveAppDataSchedule {
    data object Off : DriveAppDataSchedule

    data class Periodic(
        val days: Int,
        val network: DriveAppDataNetwork,
    ) : DriveAppDataSchedule
}

/** Outcome of one scheduled or manual upload run. The host maps it onto its own job result. */
sealed interface DriveAppDataJobResult {
    data object Succeeded : DriveAppDataJobResult

    /** A transient network failure inside the retry budget. The host should ask its scheduler to retry. */
    data object RetryLater : DriveAppDataJobResult

    data class Failed(val failure: DriveAppDataBackupFailure) : DriveAppDataJobResult

    /**
     * Stopped without an error: through the owner's cancel operation, or because the connected account changed
     * before the run started. The host ends the run without reporting a failure.
     */
    data object Cancelled : DriveAppDataJobResult
}

/** Outcome of preparing the newest Drive backup for restore. */
sealed interface CloudRestorePreparation {
    data class Ready(
        val handle: String,
        val categories: Set<BackupDataCategory>,
    ) : CloudRestorePreparation

    data object Cancelled : CloudRestorePreparation

    data class Rejected(val problem: BackupArchiveProblem) : CloudRestorePreparation

    data class Failed(val failure: DriveAppDataBackupFailure) : CloudRestorePreparation
}

/**
 * A Drive operation failed with a classified reason. Owner operations report it through state, so UI
 * callers rarely see it; hosts still catch it at the transport boundary.
 */
class DriveAppDataBackupException(
    val failure: DriveAppDataBackupFailure,
    cause: Throwable? = null,
) : Exception(failure.name, cause)
