/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.settings

import kotlinx.coroutines.flow.StateFlow

/**
 * The Google Drive app-data backup frequencies, with the baseline day counts. The host stores the chosen value in its own
 * Drive backup store, never in the ordinary settings store, so the page cannot write it through a generic edit.
 */
enum class DriveAppDataFrequency(val days: Int) { MANUAL(0), DAILY(1), WEEKLY(7), MONTHLY(30) }

/** Why a Drive app-data operation failed. The host maps its Google authorization and transport errors into these. */
enum class DriveAppDataFailure { AUTHORIZATION, CONFIGURATION, NETWORK, QUOTA, MISSING, INVALID, STORAGE, UNKNOWN }

/** What the running operation is doing, for the status line. The host reports null while nothing runs. */
enum class DriveAppDataActivity { CHECKING, PREPARING, UPLOADING, DOWNLOADING, WAITING }

/**
 * The newest backup in Drive. The host formats [date] and [size] for the current locale, so the page only displays them and
 * never formats times or byte counts itself.
 */
data class DriveAppDataLastBackup(val date: String, val size: String)

/**
 * The live state of the section. [accountName] is null while signed out. [progressPercent] is 0..100 while a download
 * reports progress and null while its length is unknown. [failure] stays visible until the next operation starts, so a
 * background failure is not hidden by a stale success.
 */
data class DriveAppDataSnapshot(
    val accountName: String? = null,
    val frequency: DriveAppDataFrequency = DriveAppDataFrequency.MANUAL,
    val wifiOnly: Boolean = true,
    val lastBackup: DriveAppDataLastBackup? = null,
    val activity: DriveAppDataActivity? = null,
    val progressPercent: Int? = null,
    val failure: DriveAppDataFailure? = null,
)

/**
 * The outcome of fetching the latest Drive backup for restore. [Ready] carries a handle from the same prepared-archive
 * registry as [BackupRestorePreparation], so the page's category dialog, restore and discard calls consume it unchanged.
 * [Failed] means the host has already recorded the reason in [DriveAppDataSnapshot.failure].
 */
sealed interface DriveAppDataRestorePreparation {
    data class Ready(val candidate: BackupRestoreCandidate) : DriveAppDataRestorePreparation

    data object Cancelled : DriveAppDataRestorePreparation

    data class Rejected(val problem: BackupRestoreProblem) : DriveAppDataRestorePreparation

    data object Failed : DriveAppDataRestorePreparation
}

/**
 * The Google Drive app-data backup operations this page needs. The host owns the account chooser and Google authorization
 * launchers (they need an Activity), the appDataFolder transport, the scheduled work and its own preference store.
 *
 * Expected failures are recorded in [snapshot] and never thrown, so a failed upload or download cannot look like a
 * completed one. An exception the host lets escape is reported by the page as a generic failure notice.
 */
interface DriveAppDataBackupHost {
    /** Null until the host has loaded its first state. The page shows a checking row meanwhile. */
    val snapshot: StateFlow<DriveAppDataSnapshot?>

    /**
     * Starts the account chooser and then Google authorization. The chosen account and any launch or authorization
     * failure arrive through [snapshot]; this call returns once the chooser has started or has failed to start.
     */
    suspend fun chooseAccount()

    /** Reloads the account state and the newest backup. The failure row uses this as its retry action. */
    suspend fun refresh()

    /** Uploads a new backup of the archive the M7 codec builds. Progress and any failure arrive through [snapshot]. */
    suspend fun backupNow()

    /**
     * Downloads the newest backup, verifies it and keeps the archive under a handle for the restore dialog. A returned
     * [DriveAppDataRestorePreparation.Ready] must be consumed by the page's restore or discard calls.
     */
    suspend fun prepareLatestRestore(): DriveAppDataRestorePreparation

    /** Forgets the account on this device and stops scheduled backups. The remote backup is kept. */
    suspend fun disconnect()

    /** Stops the running upload, download or wait. The outcome is reported through [snapshot]. */
    suspend fun cancelRunning()

    /** Persists the schedule in the host store and reschedules the work to match. */
    suspend fun setFrequency(frequency: DriveAppDataFrequency)

    /** Persists the network rule (unmetered only, or any connection) and reschedules the work to match. */
    suspend fun setWifiOnly(wifiOnly: Boolean)
}
