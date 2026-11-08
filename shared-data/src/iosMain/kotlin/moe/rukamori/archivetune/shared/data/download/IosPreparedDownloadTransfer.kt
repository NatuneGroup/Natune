@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.download

import io.ktor.client.HttpClient
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import moe.rukamori.archivetune.shared.data.bytesource.ScopedCookieSource
import moe.rukamori.archivetune.shared.data.persistence.StoredSongIndex
import moe.rukamori.archivetune.shared.data.platform.ios.IosNativeAudioRoute
import moe.rukamori.archivetune.shared.data.stream.nativePlaintext
import moe.rukamori.archivetune.shared.data.stream.hls.isHlsPlaylistUrl
import moe.rukamori.archivetune.shared.domain.download.*
import moe.rukamori.archivetune.shared.domain.platform.AcceptedAccountReader
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.source.DirectStream
import platform.posix.*
import platform.Foundation.NSLock

/** Stores the same validated plaintext playback consumes, under the task's existing commit transaction. */
class IosPreparedDownloadTransfer(
    client: HttpClient,
    private val files: IosDownloadFiles,
    private val index: StoredSongIndex,
    private val nowMs: () -> Long,
    accounts: AcceptedAccountReader,
    cookies: ScopedCookieSource,
) : PreparedDownloadTransfer {
    private val progressive = IosDownloadTransfer(client, files, index, nowMs)
    private val route = IosNativeAudioRoute(accounts, cookies)
    private val lock = NSLock()
    private val preparations = mutableMapOf<String, IosNativeAudioRoute.Outcome.Ready>()

    suspend fun prepare(task: DownloadTask, audio: PreparedAudio): Boolean {
        releasePreparation(task.id)
        if (audio is PreparedAudio.Progressive && !isHlsPlaylistUrl(audio.stream.uri)) return true
        return when (val result = route.prepare(audio)) {
            is IosNativeAudioRoute.Outcome.Ready -> { locked { preparations[task.id] = result }; true }
            else -> false
        }
    }
    override fun releasePreparation(taskId: String) { locked { preparations.remove(taskId) }?.transport?.close() }

    override suspend fun transfer(task: DownloadTask, stream: DirectStream, resumeFromBytes: Long, song: ContentItem?,
        progress: suspend (Long, Long?) -> Unit): Long = progressive.transfer(task, stream, resumeFromBytes, song, progress)

    override suspend fun transferPrepared(task: DownloadTask, audio: PreparedAudio, resumeFromBytes: Long, song: ContentItem?,
        progress: suspend (Long, Long?) -> Unit): Long = withContext(Dispatchers.Default) {
        val ready = locked { preparations.remove(task.id) }
        if (ready == null) {
            val stream = (audio as? PreparedAudio.Progressive)?.stream ?: error("No validated download preparation")
            return@withContext progressive.transfer(task, stream, resumeFromBytes, song, progress)
        }
        try {
            val plaintext = requireNotNull(ready.handle.nativePlaintext())
            files.ensureDirectories(task.id)
            val staging = files.stagingPath(task.id)
            val start = minOf(files.sizeOf(staging), resumeFromBytes).coerceAtLeast(0)
            val file = fopen(staging, if (start > 0) "r+b" else "wb") ?: error("Cannot stage download")
            var done = start
            val buffer = ByteArray(64 * 1024)
            try {
                if (start > 0) check(ftruncate(fileno(file), start) == 0 && fseek(file, start, SEEK_SET) == 0)
                progress(done, plaintext.length)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    ready.handle.expiresAtEpochMs?.let { check(nowMs() < it) { "Prepared download expired" } }
                    val read = plaintext.source.readAt(done, buffer, 0, buffer.size)
                    if (read < 0) break
                    if (read == 0) { yield(); continue }
                    require(done <= 2L * 1024 * 1024 * 1024 - read) { "Download exceeds size limit" }
                    buffer.usePinned { check(fwrite(it.addressOf(0), 1u, read.toULong(), file) == read.toULong()) }
                    done += read
                    progress(done, plaintext.length)
                }
                check(fflush(file) == 0)
                check(fsync(fileno(file)) == 0)
            } finally { fclose(file) }
            require(done > 0 && (plaintext.length == null || done == plaintext.length)) { "Incomplete prepared download" }
            val destination = files.destinationPath(task, audio.source)
            val place: suspend () -> Unit = { files.commit(task.id, destination) }
            if (song == null) place() else {
                val displaced = index.commitDownload(song, audio.source, task.quality, done, nowMs(), place)
                displaced?.let {
                    val previous = files.destinationPath(task.provider, task.mediaId, it.source, it.quality)
                    if (previous != destination) files.removeCommitted(previous)
                }
            }
            done
        } finally { ready.transport.close() }
    }
    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try { return block() } finally { lock.unlock() }
    }

}
