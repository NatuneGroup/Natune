/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.data.bytesource

/**
 * Snapshot of a Telegram partial download. [downloadOffset] and [downloadedPrefixBytes] describe
 * the contiguous run that is already on disk; [expectedSizeBytes] null or 0 means unknown.
 */
data class PartialFileState(
    val expectedSizeBytes: Long?,
    val downloadOffset: Long,
    val downloadedPrefixBytes: Long,
    val isDownloadingActive: Boolean,
    val isDownloadingCompleted: Boolean,
)

/** Platform port over the TDLib file: reads bytes from the downloaded run and requests seeks. */
interface TelegramPartialFilePort {
    suspend fun state(): PartialFileState

    suspend fun startDownload(offset: Long)

    /** Only bytes inside the downloaded run (or any byte once completed). */
    suspend fun readPart(
        offset: Long,
        count: Int,
    ): ByteArray
}

/**
 * Reads a Telegram file that may still be downloading. It never blocks: a position outside the
 * downloaded run triggers a seek and reports 0 so the caller retries later. The read policy lives in
 * [readTelegramPartialRun], which the non-HTTP [TelegramLocalPartialFileSource] shares, so the two
 * readers cannot drift apart.
 */
class TelegramPartialFileByteSource(
    override val lease: ByteSourceLease,
    private val port: TelegramPartialFilePort,
) : RandomAccessByteSource {
    init {
        if (lease.account.provider != PROVIDER) {
            throw ByteSourceException("TelegramPartialFileByteSource requires a telegram lease")
        }
    }

    override suspend fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = readTelegramPartialRun(port, position, buffer, offset, length)

    private companion object {
        const val PROVIDER = "telegram"
    }
}
