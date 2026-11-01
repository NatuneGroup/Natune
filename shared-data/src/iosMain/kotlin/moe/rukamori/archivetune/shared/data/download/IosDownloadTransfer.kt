@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.download

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import moe.rukamori.archivetune.shared.data.persistence.StoredSongIndex
import moe.rukamori.archivetune.shared.domain.download.DownloadTask
import moe.rukamori.archivetune.shared.domain.download.DownloadTransfer
import moe.rukamori.archivetune.shared.domain.download.DownloadedFileLocator
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.domain.source.DirectStream
import platform.Foundation.NSFileManager
import platform.posix.*

/** The staged file of one task. Only a commit or a removal deletes it, so a paused transfer resumes from it. */
private const val STAGING_FILE = "download.part"

/** One read at a time from the provider stream; the engine's own buffer size, so no chunk is dropped. */
private const val TRANSFER_BUFFER_BYTES = 64 * 1024

/** A download larger than this is refused rather than filling the app container. */
private const val MAX_DOWNLOAD_BYTES = 2L * 1024 * 1024 * 1024

/**
 * The iOS transfer, the same flow as the desktop one: stage under a task-owned path, resume through an HTTP range
 * request (a 206 appends, a 200 restarts, a 416 commits the staged bytes), then move the file into the committed
 * downloads area atomically and list the row in the stored-song index. Cancellation and failure leave the staged file
 * for the next resume; only removal or a commit deletes it.
 *
 * The index supplies the row the library, the downloads page and the offline locator read. A row is written before
 * the bytes move, and a failed move restores whatever listing it displaced, so no row ever names a missing file.
 */
class IosDownloadTransfer(
    private val client: HttpClient,
    private val files: IosDownloadFiles,
    /** Lists a committed download when the song is known; null keeps the file unlisted rather than invented. */
    private val index: StoredSongIndex? = null,
    private val nowMs: () -> Long = { 0L },
    private val maxBytes: Long = MAX_DOWNLOAD_BYTES,
) : DownloadTransfer {
    override suspend fun transfer(
        task: DownloadTask,
        stream: DirectStream,
        resumeFromBytes: Long,
        song: ContentItem?,
        progress: suspend (bytesDone: Long, totalBytes: Long?) -> Unit,
    ): Long = withContext(Dispatchers.Default) {
        require(maxBytes > 0L) { "A download needs a positive size limit" }
        files.ensureDirectories(task.id)
        val staging = files.stagingPath(task.id)
        val destination = files.destinationPath(task, stream.source)
        val staged = files.sizeOf(staging)
        var append = resumeFromBytes > 0 && staged > 0
        val offset = if (append) minOf(staged, resumeFromBytes) else 0L
        var committed = 0L
        client.prepareGet(stream.uri) {
            stream.headers.reveal().forEach { (name, value) -> header(name, value) }
            if (append) header(HttpHeaders.Range, "bytes=$offset-")
        }.execute { response ->
            val status = response.status.value
            val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (append && status == 416) {
                // The staged bytes already hold the whole body, so the transfer commits them without another read.
                committed = offset
                return@execute
            }
            if (append && status != 206) append = false
            if (status !in 200..299) error("Download HTTP $status")
            val start = if (append) offset else 0L
            val expectedTotal = declared?.let { it + start }
            val mode = if (append) "ab" else "wb"
            val file = fopen(staging, mode) ?: error("Cannot stage download")
            try {
                val channel = response.bodyAsChannel()
                memScoped {
                    val buffer = allocArray<ByteVar>(TRANSFER_BUFFER_BYTES)
                    var total = start
                    progress(total, expectedTotal)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = channel.readAvailable(buffer, 0, TRANSFER_BUFFER_BYTES)
                        if (count < 0) break
                        if (count == 0) { yield(); continue }
                        total += count
                        require(total <= maxBytes) { "Download exceeds the size limit" }
                        check(fwrite(buffer, 1u, count.toULong(), file) == count.toULong()) { "Download write failed" }
                        progress(total, expectedTotal)
                    }
                    committed = total
                }
            } finally {
                fclose(file)
            }
        }
        require(committed > 0L) { "Empty download" }
        val placeFile: suspend () -> Unit = { files.commit(task.id, destination) }
        val listed = index
        if (listed != null && song != null) {
            // The removal looks the row up by this exact quality, so a blank one would commit a row nothing can drop.
            require(task.quality.isNotBlank()) { "A listed download names its quality" }
            val displaced = listed.commitDownload(song, stream.source, task.quality, committed, nowMs(), placeFile)
            // An earlier download in another file is no longer listed, so its bytes are removed once the commit has landed.
            displaced?.let {
                val earlier = files.destinationPath(task.provider, task.mediaId, it.source, it.quality)
                if (earlier != destination) files.removeCommitted(earlier)
            }
        } else {
            placeFile()
        }
        committed
    }

    /** Drops a task's staged bytes without committing them. */
    suspend fun discard(task: DownloadTask) = files.discard(task.id)
}

/**
 * The filesystem the transfer and the offline locator share, rooted in the app's container. Each download is
 * `provider/source/mediaId_quality.media` under [root], so two providers naming one media id never share a file.
 */
class IosDownloadFiles(private val root: String) {
    fun stagingPath(taskId: String): String =
        "$root/staging/" + sanitizeDownloadComponent(taskId) + "/" + STAGING_FILE

    fun destinationPath(provider: String, mediaId: String, source: AudioSourceType, quality: String): String =
        downloadAbsolutePath(root, downloadRelativePath(provider, mediaId, source, quality))

    fun destinationPath(task: DownloadTask, source: AudioSourceType): String =
        destinationPath(task.provider, task.mediaId, source, task.quality)

    fun ensureDirectories(taskId: String) {
        NSFileManager.defaultManager.createDirectoryAtPath(
            "$root/staging/" + sanitizeDownloadComponent(taskId), true, null, null,
        )
    }

    fun sizeOf(path: String): Long {
        memScoped {
            val info = alloc<stat>()
            return if (stat(path, info.ptr) == 0) info.st_size else 0L
        }
    }

    fun exists(path: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)

    /** Moves the staged file into place and drops the task's staging folder. */
    fun commit(taskId: String, destination: String) {
        val manager = NSFileManager.defaultManager
        manager.createDirectoryAtPath(destination.substringBeforeLast('/'), true, null, null)
        manager.removeItemAtPath(destination, null)
        val staged = stagingPath(taskId)
        check(manager.moveItemAtPath(staged, destination, null)) { "Download commit failed" }
        manager.removeItemAtPath(staged.substringBeforeLast('/'), null)
    }

    fun discard(taskId: String) {
        NSFileManager.defaultManager.removeItemAtPath(stagingPath(taskId).substringBeforeLast('/'), null)
    }

    fun removeCommitted(path: String) {
        NSFileManager.defaultManager.removeItemAtPath(path, null)
    }

    /** Drops the whole staging tree; the clear-all path removes every download's staged bytes at once. */
    fun removeStagingRoot() {
        NSFileManager.defaultManager.removeItemAtPath("$root/staging", null)
    }
}

/**
 * The committed file of [task] for offline playback, or null when it is not a listed download. A row whose file
 * is missing or does not hold the committed byte count names no file, so the player resolves the song normally.
 */
class IosDownloadedFileLocator(private val files: IosDownloadFiles) : DownloadedFileLocator {
    override suspend fun fileOf(task: DownloadTask): String? {
        val source = task.source ?: return null
        val committed = task.totalBytes?.takeIf { it > 0L } ?: return null
        val path = files.destinationPath(task, source)
        if (!files.exists(path)) return null
        return path.takeIf { files.sizeOf(it) == committed }
    }
}
