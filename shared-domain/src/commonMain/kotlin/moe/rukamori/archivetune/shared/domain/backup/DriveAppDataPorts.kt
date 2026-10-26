package moe.rukamori.archivetune.shared.domain.backup

/** Limits and constants shared by the Drive owner, the Ktor transport, the host adapters and the archive gateway. */
object DriveAppDataLimits {
    /**
     * Largest compressed backup archive this client creates, uploads, downloads, inspects or restores: 512 MiB, the
     * GMS baseline bound. Cloud and file restores share it, so both accept the same archives. The JVM backup codec
     * must accept the same bound, so an archive is never uploaded or offered for restore that the codec would refuse.
     * Raise or lower it only together with that codec bound.
     */
    const val MAX_ARCHIVE_BYTES: Int = 512 * 1024 * 1024

    /** Run attempts (0-based) a host may retry a transient network failure before it reports the failure. */
    const val NETWORK_RETRY_ATTEMPTS: Int = 3

    /** Name of the single backup file kept in the app data folder. */
    const val FILE_NAME: String = "ArchiveTune.backup"

    /**
     * OAuth scope for the hidden app data folder. Host authorization adds whatever prefix its account API
     * requires; the scope itself is not changed.
     */
    const val SCOPE: String = "https://www.googleapis.com/auth/drive.appdata"
}

/**
 * Drive REST operations on the app data folder. Implementations own HTTP, JSON parsing, the resumable
 * upload handshake and integrity checks. Every failure surfaces as [DriveAppDataBackupException].
 */
interface DriveAppDataTransport {
    /** The newest backup in the app data folder, or null when none exists. */
    suspend fun latest(token: String): DriveAppDataBackupMetadata?

    /**
     * Uploads [archive] as the single backup, replacing [existing] when it is given. Returns the metadata
     * Drive confirmed. Throws INVALID when Drive reports a size or checksum that differs from [archive].
     */
    suspend fun upload(
        token: String,
        existing: DriveAppDataBackupMetadata?,
        archive: ByteArray,
    ): DriveAppDataBackupMetadata

    /**
     * Downloads [backup] and verifies its size and MD5 before returning. [onProgress] receives a percentage
     * only when it changes. Cancelling the calling coroutine stops the transfer.
     */
    suspend fun download(
        token: String,
        backup: DriveAppDataBackupMetadata,
        onProgress: (Int) -> Unit,
    ): ByteArray
}

/** Access tokens for Google accounts. Hosts implement it with the platform account API. */
interface DriveAppDataAuthorization {
    /** Returns an access token for [accountName], or throws AUTHORIZATION when access is not granted. */
    suspend fun token(accountName: String): String

    /** Drops [token] after Drive rejected it, so the next request asks for a fresh one. */
    suspend fun invalidate(token: String)
}

/** Applies the periodic schedule and manual runs through the host job system. */
interface DriveAppDataScheduler {
    /** Replaces the periodic upload with [schedule]. [DriveAppDataSchedule.Off] removes it. */
    suspend fun apply(schedule: DriveAppDataSchedule)

    /** Queues one manual upload for [accountName]. A request that is already queued is kept. */
    suspend fun enqueueNow(accountName: String)

    /** Cancels queued and running uploads, including the periodic one. */
    suspend fun cancelAll()
}

/** Persists [DriveAppDataSettings] across restarts. A missing preference reads as the default settings. */
interface DriveAppDataSettingsStore {
    suspend fun read(): DriveAppDataSettings

    suspend fun write(settings: DriveAppDataSettings)
}
