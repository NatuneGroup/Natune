/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package moe.rukamori.archivetune.shared.data.platform.ios

import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import moe.rukamori.archivetune.shared.data.bytesource.AdmittedTelegramPartialFilePort
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceCancelledException
import moe.rukamori.archivetune.shared.data.bytesource.PartialFileState
import moe.rukamori.archivetune.shared.data.bytesource.TelegramLocalPartialFileSource
import moe.rukamori.archivetune.shared.data.bytesource.TelegramPartialFilePort
import moe.rukamori.archivetune.shared.data.bytesource.TelegramRandomAccessSource
import moe.rukamori.archivetune.shared.data.bytesource.telegramFileLease
import moe.rukamori.archivetune.shared.domain.platform.AcceptedAccountReader
import moe.rukamori.archivetune.shared.domain.telegram.TelegramTrackRef
import platform.Foundation.*

/** The message for a track whose account the store does not accept. It names the one action that fixes it. */
private const val TELEGRAM_NOT_SIGNED_IN = "No Telegram account is accepted; sign in to Telegram before playing"

/** The message for a track whose file cannot be opened. The track is refused, never replaced by another source. */
private const val TELEGRAM_TRACK_UNAVAILABLE = "Telegram could not open this track; prepare it again"

/** The size that a lease reports when TDLib has not learned the file length. The engine treats a negative size as unknown. */
private const val TELEGRAM_UNKNOWN_SIZE = -1L

/** The download scope of one stream. It is the only transfer that matters while a track plays, so TDLib serves it first. */
private val telegramRetireScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * A refused Telegram call. Its message names the operation and, where TDLib gave one, its error code. It never carries a
 * code, a phone number, a password or the api hash, because those are not part of any reply that is read here.
 */
class IosTelegramRequestException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** One TDLib file as the Telegram port reads it. A zero size means TDLib has not learned the length yet. */
internal data class IosTelegramFileRecord(
    val sizeBytes: Long,
    val expectedSizeBytes: Long,
    val downloadOffset: Long,
    val downloadedPrefixBytes: Long,
    val isDownloadingActive: Boolean,
    val isDownloadingCompleted: Boolean,
    /** The content id that TDLib keeps across databases. It tells a stale file id from the track it was saved for. */
    val remoteUniqueId: String = "",
)

/**
 * The TDLib file calls that one Telegram track needs. A call that TDLib refuses throws, so a failed call can never be
 * read as an empty file, and a stream never plays bytes it did not receive.
 */
internal interface IosTelegramFileOperations {
    suspend fun getFile(fileId: Int): IosTelegramFileRecord

    suspend fun startDownload(fileId: Int, offset: Long)

    suspend fun cancelDownload(fileId: Int)

    suspend fun readFilePart(fileId: Int, offset: Long, count: Int): ByteArray

    suspend fun findTrackFileId(chatId: Long, messageId: Long): Int
}

/**
 * The TDLib file calls, sent as JSON requests through [request], which answers each one with the TDLib object of the
 * matching type. An answer of any other type is a refusal, so a broken reply can never look like an empty file.
 */
internal class IosTelegramTdlibFileOperations(
    private val request: suspend (operation: String, query: JsonObject, timeoutMs: Long) -> JsonObject,
) : IosTelegramFileOperations {
    override suspend fun getFile(fileId: Int): IosTelegramFileRecord {
        val file = request(
            "getFile",
            buildJsonObject {
                put("@type", "getFile")
                put("file_id", fileId)
            },
            REQUEST_TIMEOUT_MS,
        )
        if (file.telegramText("@type") != "file") {
            throw IosTelegramRequestException("getFile returned no file record for file $fileId")
        }
        // A missing local record would read as "nothing downloaded" and send the stream down the wrong branch, so it is an
        // error rather than a zeroed record.
        val local = file.telegramObject("local")
            ?: throw IosTelegramRequestException("getFile returned no local record for file $fileId")
        return IosTelegramFileRecord(
            sizeBytes = file.telegramLong("size") ?: 0L,
            expectedSizeBytes = file.telegramLong("expected_size") ?: 0L,
            downloadOffset = local.telegramLong("download_offset") ?: 0L,
            downloadedPrefixBytes = local.telegramLong("downloaded_prefix_size") ?: 0L,
            isDownloadingActive = local.telegramFlag("is_downloading_active"),
            isDownloadingCompleted = local.telegramFlag("is_downloading_completed"),
            // The unique id is what tells a stale file id from the track it was saved for, so it is always copied.
            remoteUniqueId = file.telegramObject("remote")?.telegramText("unique_id") ?: "",
        )
    }

    override suspend fun startDownload(fileId: Int, offset: Long) {
        // Not synchronous, so the call returns the file state at once and the transfer continues inside TDLib. The limit of
        // 0 leaves the transfer open: the stream, not TDLib, decides when it stops, through cancelDownload.
        request(
            "downloadFile",
            buildJsonObject {
                put("@type", "downloadFile")
                put("file_id", fileId)
                put("priority", STREAM_DOWNLOAD_PRIORITY)
                put("offset", offset)
                put("limit", 0)
                put("synchronous", false)
            },
            REQUEST_TIMEOUT_MS,
        )
    }

    override suspend fun cancelDownload(fileId: Int) {
        // onlyIfPending = false: a transfer that already started must stop too, or it keeps spending bandwidth.
        request(
            "cancelDownloadFile",
            buildJsonObject {
                put("@type", "cancelDownloadFile")
                put("file_id", fileId)
                put("only_if_pending", false)
            },
            REQUEST_TIMEOUT_MS,
        )
    }

    override suspend fun readFilePart(fileId: Int, offset: Long, count: Int): ByteArray {
        val part = request(
            "readFilePart",
            buildJsonObject {
                put("@type", "readFilePart")
                put("file_id", fileId)
                put("offset", offset)
                put("count", count)
            },
            REQUEST_TIMEOUT_MS,
        )
        if (part.telegramText("@type") != "filePart") {
            throw IosTelegramRequestException("readFilePart returned no data record for file $fileId")
        }
        // TDLib writes bytes in JSON as base64. A missing field is a refusal, never an empty read.
        val encoded = part.telegramText("data")
            ?: throw IosTelegramRequestException("readFilePart returned no bytes for file $fileId")
        val bytes = Base64.Default.decode(encoded)
        // TDLib returns at most the count it was asked for. A larger array would bypass the port's read cap.
        if (bytes.size > count) {
            throw IosTelegramRequestException("readFilePart returned ${bytes.size} bytes for a count of $count")
        }
        return bytes
    }

    override suspend fun findTrackFileId(chatId: Long, messageId: Long): Int {
        // TDLib looks a message up only in a chat it has loaded, so the chat is loaded first. The load is best effort,
        // because a chat that cannot be loaded makes the message request below fail with the real reason.
        try {
            request(
                "getChat",
                buildJsonObject {
                    put("@type", "getChat")
                    put("chat_id", chatId)
                },
                MESSAGE_LOOKUP_TIMEOUT_MS,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unloaded: Exception) {
            // Deliberately ignored, see above.
        }
        val message = request(
            "getMessage",
            buildJsonObject {
                put("@type", "getMessage")
                put("chat_id", chatId)
                put("message_id", messageId)
            },
            MESSAGE_LOOKUP_TIMEOUT_MS,
        )
        if (message.telegramText("@type") != "message") {
            throw IosTelegramRequestException(
                "getMessage returned no message record for message $messageId in chat $chatId",
            )
        }
        // Only audio and document messages carry a track file. These are the content types the Android client reads.
        val content = message.telegramObject("content")
        val file: JsonObject = content?.let { body ->
            when (body.telegramText("@type")) {
                "messageAudio" -> body.telegramObject("audio")?.telegramObject("audio")
                "messageDocument" -> body.telegramObject("document")?.telegramObject("document")
                else -> null
            }
        } ?: throw IosTelegramRequestException(
            "message $messageId in chat $chatId carries no audio or document file",
        )
        // A zero or negative id names no file. Returning it would let the port read an unknown file as if it were empty.
        val fileId = file.telegramInt("id") ?: 0
        if (fileId <= 0) {
            throw IosTelegramRequestException("message $messageId in chat $chatId names no TDLib file id")
        }
        return fileId
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 10_000L
        const val MESSAGE_LOOKUP_TIMEOUT_MS = 30_000L
        const val STREAM_DOWNLOAD_PRIORITY = 32
    }
}

/**
 * One Telegram track's TDLib file, read through [operations]. The file id is resolved once and then kept. A stored id is
 * used only while it still names this track, and otherwise the message that holds the track is looked up again.
 */
internal class IosTelegramPartialFilePort(
    private val track: TelegramTrackRef,
    private val operations: IosTelegramFileOperations,
) : TelegramPartialFilePort {
    // The reader and the closer run on different threads, so the id is guarded. The lock is never held across a suspension.
    private val fileIdLock = NSLock()
    private var activeFileId: Int? = null
    private val resolution = Mutex()

    override suspend fun state(): PartialFileState {
        val record = operations.getFile(fileIdForUse())
        return PartialFileState(
            // TDLib reports the size once it knows it, and the expected size before that. A zero size means unknown.
            expectedSizeBytes = when {
                record.sizeBytes > 0L -> record.sizeBytes
                record.expectedSizeBytes > 0L -> record.expectedSizeBytes
                else -> null
            },
            downloadOffset = record.downloadOffset,
            downloadedPrefixBytes = record.downloadedPrefixBytes,
            isDownloadingActive = record.isDownloadingActive,
            isDownloadingCompleted = record.isDownloadingCompleted,
        )
    }

    override suspend fun startDownload(offset: Long) {
        require(offset >= 0L) { "a Telegram download starts at a non-negative offset" }
        operations.startDownload(fileIdForUse(), offset)
    }

    override suspend fun readPart(offset: Long, count: Int): ByteArray {
        require(offset >= 0L && count >= 0) { "a Telegram read has a non-negative offset and count" }
        if (count == 0) return ByteArray(0)
        return operations.readFilePart(fileIdForUse(), offset, minOf(count, MAX_PART_BYTES))
    }

    /**
     * Stops the transfer of this track's file, when one was started. A failure is ignored, because the stream that asked
     * for the stop is already gone, and TDLib's transfer ends with the session in any case.
     */
    suspend fun cancelDownload() {
        val fileId = knownFileId() ?: return
        try {
            operations.cancelDownload(fileId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Nothing to retry: the transfer may have ended already, or the session may have closed.
        }
    }

    private suspend fun fileIdForUse(): Int {
        knownFileId()?.let { return it }
        return resolution.withLock {
            knownFileId() ?: resolveFileId().also { rememberFileId(it) }
        }
    }

    private suspend fun resolveFileId(): Int {
        // A stored id is kept only while it still names this track, so a stale id is never read as another file.
        if (track.fileId > 0 && storedIdNamesTrack()) return track.fileId
        return operations.findTrackFileId(track.chatId, track.messageId)
    }

    private suspend fun storedIdNamesTrack(): Boolean = try {
        val record = operations.getFile(track.fileId)
        track.fileUniqueId.isEmpty() || record.remoteUniqueId == track.fileUniqueId
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        false
    }

    private fun knownFileId(): Int? = fileIdLock.locked { activeFileId }

    private fun rememberFileId(fileId: Int) {
        fileIdLock.locked { activeFileId = fileId }
    }

    private companion object {
        /** The largest part one read asks TDLib for. A larger read is answered in pieces, so each reply stays small. */
        const val MAX_PART_BYTES = 32 * 1024
    }
}

/**
 * The host lease of one Telegram track. It reads through the shared partial-file policy, so the engine sees only the bytes
 * that TDLib has written, and a zero read means retry later. Cancelling the lifetime wakes a read that is blocked. Reads
 * run on the engine's producer thread, and only [interrupt] and [close] run elsewhere, as the lease contract requires.
 */
internal class IosTelegramStreamSource(
    private val source: TelegramRandomAccessSource,
    private val port: IosTelegramPartialFilePort,
    private val expectedSizeBytes: Long,
) : IosLeaseSource {
    private val lifetime = SupervisorJob()
    private val retireLock = NSLock()
    private var retired = false

    override fun probe(): IosHttpRangeSource.Probe =
        IosHttpRangeSource.Probe(sizeBytes = expectedSizeBytes, seekable = true)

    override fun readBlocking(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (!lifetime.isActive) throw ByteSourceCancelledException()
        // The read is a child of the lifetime, so an interrupt or a close wakes it at once instead of after its download.
        return runBlocking(lifetime) { source.readAt(position, buffer, offset, length) }
    }

    override fun interrupt() {
        lifetime.cancel()
    }

    override fun close() {
        lifetime.cancel()
        // The engine and the playback port may both close the lease, so only the first close stops the transfer.
        val first = retireLock.locked {
            val wasRetired = retired
            retired = true
            !wasRetired
        }
        if (first) telegramRetireScope.launch { port.cancelDownload() }
    }
}

/** What one Telegram track resolves to before it plays: a source that is admitted now, or the reason it cannot be read. */
internal sealed interface IosTelegramTrackOutcome {
    class Ready(val source: IosTelegramStreamSource) : IosTelegramTrackOutcome

    class Refused(val reason: String) : IosTelegramTrackOutcome
}

/**
 * Admits one Telegram track and builds its source. The session must be ready and the store must accept the account. Both
 * are checked again before every read, so a sign-out ends the stream. A track whose file cannot be found is refused with
 * its reason, never guessed or handed to another source.
 */
internal suspend fun resolveTelegramTrack(
    session: IosTelegramClient,
    accounts: AcceptedAccountReader,
    accountScope: String,
    track: TelegramTrackRef,
): IosTelegramTrackOutcome {
    val operations = session.fileOperations()
        ?: return IosTelegramTrackOutcome.Refused(TELEGRAM_NOT_SIGNED_IN)
    try {
        val admission = accounts.admit(IOS_TELEGRAM_PROVIDER, accountScope)
            ?: return IosTelegramTrackOutcome.Refused(TELEGRAM_NOT_SIGNED_IN)
        val port = IosTelegramPartialFilePort(track, operations)
        val guarded = AdmittedTelegramPartialFilePort(admission, accounts, port)
        val expectedSize = guarded.state().expectedSizeBytes ?: TELEGRAM_UNKNOWN_SIZE
        val lease = telegramFileLease(track, null, admission)
        return IosTelegramTrackOutcome.Ready(
            IosTelegramStreamSource(TelegramLocalPartialFileSource(lease, guarded), port, expectedSize),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        return IosTelegramTrackOutcome.Refused(failure.message ?: TELEGRAM_TRACK_UNAVAILABLE)
    }
}

private inline fun <T> NSLock.locked(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
