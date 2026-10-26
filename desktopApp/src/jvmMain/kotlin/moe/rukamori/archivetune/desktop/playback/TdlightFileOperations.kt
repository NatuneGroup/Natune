package moe.rukamori.archivetune.desktop.playback

import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.IOException

/**
 * The TDLight binding of [TdlibFileOperations], over one TDLib [Client] that the session owner created and will close.
 *
 * Each call is one TDLib request, except the message lookup, which loads the chat first. TDLib answers on its own
 * receiver thread, and that answer resumes the waiting coroutine exactly once. The answer to a request that was cancelled
 * or timed out is dropped. A request that TDLib never answers, such as one sent to a client that has already been
 * closed, ends at its timeout with an [IOException], so a stream never waits forever on a dead session.
 *
 * The adapter neither creates nor closes the client. Its update handlers, parameters and `TdApi.Close` belong to the
 * session owner, which must load the native library through [TdlightNativeLibrary] before creating the client.
 */
internal class TdlightFileOperations(
    private val client: Client,
    private val downloadPriority: Int = DEFAULT_DOWNLOAD_PRIORITY,
) : TdlibFileOperations {
    init {
        require(downloadPriority in 1..MAX_DOWNLOAD_PRIORITY) { "a TDLib download priority is 1 to 32" }
    }

    override suspend fun getFile(fileId: Int): TdlibFileRecord {
        val file = request("getFile", TdApi.GetFile(fileId)) as? TdApi.File
            ?: throw TdlightRequestException("getFile returned no file record for file $fileId")
        // A missing local record would read as "nothing downloaded" and send the stream down the wrong branch, so it is
        // an error rather than a zeroed record.
        val local = file.local ?: throw TdlightRequestException("getFile returned no local record for file $fileId")
        return TdlibFileRecord(
            sizeBytes = file.size,
            expectedSizeBytes = file.expectedSize,
            downloadOffset = local.downloadOffset,
            downloadedPrefixBytes = local.downloadedPrefixSize,
            isDownloadingActive = local.isDownloadingActive,
            isDownloadingCompleted = local.isDownloadingCompleted,
            // The unique id is what tells a stale file id from the track it was saved for, so it is always copied.
            remoteUniqueId = file.remote?.uniqueId.orEmpty(),
        )
    }

    override suspend fun startDownload(fileId: Int, offset: Long) {
        // Not synchronous, so the call returns the file state at once and the transfer continues inside TDLib. The limit
        // of 0 leaves the transfer open: the stream, not TDLib, decides when it stops, through cancelDownload.
        request("downloadFile", TdApi.DownloadFile(fileId, downloadPriority, offset, 0L, false))
    }

    override suspend fun cancelDownload(fileId: Int) {
        // onlyIfPending = false: a transfer that already started must stop too, or it keeps spending bandwidth.
        request("cancelDownloadFile", TdApi.CancelDownloadFile(fileId, false))
    }

    override suspend fun readFilePart(fileId: Int, offset: Long, count: Int): ByteArray {
        val part = request("readFilePart", TdApi.ReadFilePart(fileId, offset, count.toLong())) as? TdApi.Data
            ?: throw TdlightRequestException("readFilePart returned no data record for file $fileId")
        val bytes = part.data ?: throw TdlightRequestException("readFilePart returned no bytes for file $fileId")
        // TDLib returns at most the count it was asked for. A larger array would bypass the port's read cap.
        if (bytes.size > count) {
            throw TdlightRequestException("readFilePart returned ${bytes.size} bytes for a count of $count")
        }
        return bytes
    }

    override suspend fun findTrackFileId(chatId: Long, messageId: Long): Int {
        // TDLib looks a message up only in a chat it has loaded, so the chat is loaded first. The load is best effort,
        // because a chat that cannot be loaded makes the message request below fail with the real reason.
        try {
            request("getChat", TdApi.GetChat(chatId), MESSAGE_LOOKUP_TIMEOUT_MS)
        } catch (unloaded: TdlightRequestException) {
            // Deliberately ignored, see above.
        }
        val reply = request("getMessage", TdApi.GetMessage(chatId, messageId), MESSAGE_LOOKUP_TIMEOUT_MS)
        val message = reply as? TdApi.Message
            ?: throw TdlightRequestException(
                "getMessage returned no message record for message $messageId in chat $chatId",
            )
        // Only audio and document messages carry a track file. These are the content types the Android client reads.
        val file = when (val content = message.content) {
            is TdApi.MessageAudio -> content.audio?.audio
            is TdApi.MessageDocument -> content.document?.document
            else -> null
        } ?: throw TdlightRequestException("message $messageId in chat $chatId carries no audio or document file")
        // A zero or negative id names no file. Returning it would let the port read an unknown file as if it were empty.
        if (file.id <= 0) throw TdlightRequestException("message $messageId in chat $chatId names no TDLib file id")
        return file.id
    }

    private suspend fun request(
        operation: String,
        query: TdApi.Function<*>,
        timeoutMs: Long = REQUEST_TIMEOUT_MS,
    ): TdApi.Object = client.requestOk(operation, query, timeoutMs)

    private companion object {
        /** TDLib accepts priorities 1 to 32. A playing stream takes the top so that user downloads cannot starve it. */
        const val DEFAULT_DOWNLOAD_PRIORITY = 32
        const val MAX_DOWNLOAD_PRIORITY = 32

        /** Local TDLib calls answer within milliseconds. The bound exists for a dead client, not for slow I/O. */
        const val REQUEST_TIMEOUT_MS = 10_000L

        /**
         * A message that TDLib does not hold yet is fetched from Telegram's servers, which a local file call never does. The
         * longer bound allows for that, and it still stops a dead client from holding the stream.
         */
        const val MESSAGE_LOOKUP_TIMEOUT_MS = 30_000L
    }
}

/**
 * A TDLib request that failed or never answered. It is an [IOException] because the port serves a byte read, and a read
 * that cannot complete is an I/O failure to the engine rather than a programming error.
 */
internal class TdlightRequestException(message: String, cause: Throwable? = null) : IOException(message, cause)
