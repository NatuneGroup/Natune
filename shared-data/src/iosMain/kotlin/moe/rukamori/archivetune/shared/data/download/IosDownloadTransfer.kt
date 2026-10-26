@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.download

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.cinterop.*
import platform.Foundation.NSFileManager
import platform.posix.*
import moe.rukamori.archivetune.shared.domain.download.DownloadTask
import moe.rukamori.archivetune.shared.domain.download.DownloadTransfer
import moe.rukamori.archivetune.shared.domain.download.DownloadedFileLocator
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.source.DirectStream

/**
 * The iOS transfer, identical in flow to the desktop one: stage under a task-owned path, resume through an HTTP
 * range request (206 appends, 200 restarts, 416 commits the staged bytes), then atomically rename into the
 * committed downloads area and list the row. Cancellation and failure leave the staged file for the next resume;
 * only removal or a commit deletes it.
 */
class IosDownloadTransfer(
    private val client: HttpClient,
    private val files: IosDownloadFiles,
) : DownloadTransfer {
    override suspend fun transfer(
        task: DownloadTask,
        stream: DirectStream,
        resumeFromBytes: Long,
        song: ContentItem?,
        progress: suspend (bytesDone: Long, totalBytes: Long?) -> Unit,
    ): Long = withContext(Dispatchers.Default) {
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
                committed = offset
                return@execute
            }
            if (append && status != 206) append = false
            if (status !in 200..299) error("Download HTTP $status")
            val mode = if (append) "ab" else "wb"
            val file = fopen(staging, mode) ?: error("Cannot stage download")
            try {
                val channel = response.bodyAsChannel()
                memScoped {
                    val buffer = allocArray<ByteVar>(64 * 1024)
                    var total = offset
                    progress(total, declared?.let { it + offset })
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = channel.readAvailable(buffer, 0, 64 * 1024)
                        if (count < 0) break
                        if (count == 0) { yield(); continue }
                        total += count
                        fwrite(buffer, 1u, count.toULong(), file)
                        progress(total, declared?.let { it + offset })
                    }
                    committed = total
                }
            } finally {
                fclose(file)
            }
        }
        require(committed > 0) { "Empty download" }
        files.commit(task.id, destination, song, stream.source)
        committed
    }

    suspend fun discard(task: DownloadTask) = files.discard(task.id)
}

/** The filesystem the transfer and the offline locator share, rooted in the app's documents area. */
@OptIn(ExperimentalForeignApi::class)
class IosDownloadFiles(private val root: String) {
    fun stagingPath(taskId: String): String = "$root/staging/${sanitize(taskId)}/download.part"

    fun destinationPath(task: DownloadTask, source: moe.rukamori.archivetune.shared.domain.source.AudioSourceType): String =
        "$root/downloads/${source.name.lowercase()}/${sanitize(task.id)}_${sanitize(task.quality)}.media"

    fun ensureDirectories(taskId: String) {
        val manager = NSFileManager.defaultManager
        for (path in listOf("$root/staging/${sanitize(taskId)}", "$root/downloads")) {
            manager.createDirectoryAtPath(path, true, null, null)
        }
    }

    fun sizeOf(path: String): Long {
        memScoped {
            val info = alloc<stat>()
            return if (stat(path, info.ptr) == 0) info.st_size else 0L
        }
    }

    fun exists(path: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)

    fun commit(taskId: String, destination: String, song: ContentItem?,
        source: moe.rukamori.archivetune.shared.domain.source.AudioSourceType) {
        val manager = NSFileManager.defaultManager
        val parent = destination.substringBeforeLast('/')
        manager.createDirectoryAtPath(parent, true, null, null)
        manager.removeItemAtPath(destination, null)
        val staged = stagingPath(taskId)
        check(manager.moveItemAtPath(staged, destination, null)) { "Download commit failed" }
        manager.removeItemAtPath("$root/staging/${sanitize(taskId)}", null)
    }

    fun discard(taskId: String) {
        NSFileManager.defaultManager.removeItemAtPath("$root/staging/${sanitize(taskId)}", null)
    }

    fun removeCommitted(task: DownloadTask, source: moe.rukamori.archivetune.shared.domain.source.AudioSourceType) {
        NSFileManager.defaultManager.removeItemAtPath(destinationPath(task, source), null)
    }

    private fun sanitize(value: String): String =
        value.map { if (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') it else '_' }.joinToString("")
}

class IosDownloadedFileLocator(private val files: IosDownloadFiles) : DownloadedFileLocator {
    override suspend fun fileOf(task: DownloadTask): String? {
        val source = task.source ?: return null
        val path = files.destinationPath(task, source)
        return path.takeIf { files.exists(it) }
    }
}
