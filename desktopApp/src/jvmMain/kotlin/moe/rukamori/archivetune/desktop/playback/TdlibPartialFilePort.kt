package moe.rukamori.archivetune.desktop.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.shared.data.bytesource.PartialFileState
import moe.rukamori.archivetune.shared.data.bytesource.TelegramPartialFilePort
import moe.rukamori.archivetune.shared.domain.telegram.TelegramTrackRef
import kotlin.math.min

/**
 * TDLib's view of one file: its sizes and the contiguous partial run already on disk. The fields mirror the TDLib
 * `file` and `localFile` records, so a binding copies them without interpreting them.
 */
internal data class TdlibFileRecord(
    /** TDLib `size`, which is 0 until it is known. */
    val sizeBytes: Long,
    /** TDLib `expectedSize`, used only while [sizeBytes] is 0. */
    val expectedSizeBytes: Long,
    val downloadOffset: Long,
    val downloadedPrefixBytes: Long,
    val isDownloadingActive: Boolean,
    val isDownloadingCompleted: Boolean,
    /** TDLib `remote.uniqueId`, the identity that names the same file under every database. Empty when TDLib reports none. */
    val remoteUniqueId: String = "",
)

/**
 * The TDLib calls the port needs. A TDLib binding implements this interface, and the port owns the policy on top of it.
 * Nothing in this file names a TDLib class, so the desktop build takes no binary dependency from it.
 */
internal interface TdlibFileOperations {
    /** TDLib's current record for [fileId]. */
    suspend fun getFile(fileId: Int): TdlibFileRecord

    /** Starts, or re-targets, the download of [fileId] so that it fills the run from [offset]. */
    suspend fun startDownload(fileId: Int, offset: Long)

    /** Stops the transfer of [fileId] and keeps what it already wrote, so a later download resumes from that prefix. */
    suspend fun cancelDownload(fileId: Int)

    /** Copies up to [count] bytes at [offset] from TDLib's local copy of [fileId]. */
    suspend fun readFilePart(fileId: Int, offset: Long, count: Int): ByteArray

    /**
     * The TDLib file id that the message at [chatId] and [messageId] carries as its audio or document content. The port
     * asks for it when a stored file id has gone stale. It fails when the message is missing or carries no file, so it
     * never returns an id that names nothing.
     */
    suspend fun findTrackFileId(chatId: Long, messageId: Long): Int
}

/**
 * The desktop [TelegramPartialFilePort] for one Telegram track. It keeps no bytes and no download state. Every state
 * read asks TDLib for the file's current record, so a seek or a finished download is seen on the next read.
 *
 * A TDLib file id is valid only for the database that issued it, so the stored id is checked before the first use.
 * When TDLib no longer confirms it, the track is re-resolved from its message, and the file id found there is used for
 * the rest of the stream. A failed lookup fails the read, so a track is never served as empty.
 *
 * The bytes are read on the engine's producer thread through a lease, which blocks while it waits. They are never read
 * on the audio callback, and one port serves one stream and is dropped with it.
 *
 * The port is read through [moe.rukamori.archivetune.shared.data.bytesource.TelegramLocalPartialFileSource], which needs no
 * http(s) origin. [moe.rukamori.archivetune.shared.data.bytesource.TelegramPartialFileByteSource] is not used, because its
 * lease must name an http(s) origin, and a Telegram file has none. An invented origin would make a local file look like a
 * network grant.
 */
internal class TdlibPartialFilePort(
    private val track: TelegramTrackRef,
    private val operations: TdlibFileOperations,
) : TelegramPartialFilePort {
    /**
     * The file id every call uses. It stays null until the first use, so a port that never reads never asks TDLib about
     * its track. It is volatile because the cancel path reads it from the thread that retires the stream.
     */
    @Volatile
    private var activeFileId: Int? = null

    /** Serialises the first resolution, so two first calls cannot each re-resolve the message. */
    private val resolution = Mutex()

    override suspend fun state(): PartialFileState {
        val record = operations.getFile(fileIdForUse())
        return PartialFileState(
            expectedSizeBytes = record.sizeBytes.takeIf { it > 0L } ?: record.expectedSizeBytes.takeIf { it > 0L },
            downloadOffset = record.downloadOffset,
            downloadedPrefixBytes = record.downloadedPrefixBytes,
            isDownloadingActive = record.isDownloadingActive,
            isDownloadingCompleted = record.isDownloadingCompleted,
        )
    }

    override suspend fun startDownload(offset: Long) {
        require(offset >= 0L) { "a download offset cannot be negative" }
        operations.startDownload(fileIdForUse(), offset)
    }

    override suspend fun readPart(offset: Long, count: Int): ByteArray {
        require(offset >= 0L) { "a read offset cannot be negative" }
        require(count >= 0) { "a read count cannot be negative" }
        if (count == 0) return ByteArray(0)
        // The engine copies through one 32 KiB scratch buffer, so no caller needs more in one call. The cap keeps a
        // larger request from copying an unbounded slice of the file into memory.
        return operations.readFilePart(fileIdForUse(), offset, min(count, MAX_PART_BYTES))
    }

    /**
     * Stops the transfer when the owner retires this stream. Only a file this port has used can have a transfer, so a
     * port that never read makes no call. A failure is ignored, because TDLib keeps the prefix either way and a live
     * transfer only costs bandwidth. Cancellation still propagates, so an owner that was cancelled is not mistaken for
     * one whose stop failed.
     */
    suspend fun cancelDownload() {
        val fileId = activeFileId ?: return
        try {
            operations.cancelDownload(fileId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Best effort by design, see the KDoc above.
        }
    }

    /** The file id for one call. It is resolved and checked on the first use only, and later calls reuse the result. */
    private suspend fun fileIdForUse(): Int =
        activeFileId ?: resolution.withLock {
            activeFileId ?: resolveFileId().also { activeFileId = it }
        }

    /**
     * The stored id is used while TDLib confirms that it names the saved file. Otherwise the track is re-resolved from its
     * message, and the file found there is used as it is, because the message is what identifies the track.
     */
    private suspend fun resolveFileId(): Int {
        if (track.fileId > 0 && storedIdNamesTrack()) return track.fileId
        return operations.findTrackFileId(track.chatId, track.messageId)
    }

    /**
     * Whether TDLib still knows the stored id as the saved file. An empty saved unique id accepts any file that TDLib
     * knows under that id, as the Android client does. A refusal by TDLib counts as stale, so it leads to
     * re-resolution rather than failing the track.
     */
    private suspend fun storedIdNamesTrack(): Boolean =
        try {
            val record = operations.getFile(track.fileId)
            track.fileUniqueId.isEmpty() || record.remoteUniqueId == track.fileUniqueId
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refused: Exception) {
            false
        }

    private companion object {
        const val MAX_PART_BYTES = 32 * 1024
    }
}
