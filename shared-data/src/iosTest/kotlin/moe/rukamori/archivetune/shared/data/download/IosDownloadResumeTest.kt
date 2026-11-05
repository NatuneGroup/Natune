@file:OptIn(ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.download

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.shared.domain.download.DownloadTask
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.DownloadPhase
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.domain.source.DirectStream
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

class IosDownloadResumeTest {
    @Test
    fun aResumeDropsTheBytesPastTheRecordedProgress(): Unit = runBlocking {
        withRoot { root ->
            val files = IosDownloadFiles(root)
            val task = task()
            files.ensureDirectories(task.id)
            writeStaged(files.stagingPath(task.id), 100)
            val tail = ByteArray(4) { TAIL_BYTE }
            val ranges = mutableListOf<String?>()
            val transfer = transfer(files, MockEngine { request ->
                ranges += request.headers[HttpHeaders.Range]
                respond(tail, HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentLength, tail.size.toString()))
            })
            val committed = transfer.transfer(task, stream(), resumeFromBytes = 64, song = null, progress = { _, _ -> })
            assertEquals(68L, committed)
            assertEquals(listOf("bytes=64-"), ranges)
            // The 36 stale bytes the crash left past the recorded progress are gone, so the row describes the file.
            assertEquals(68L, files.sizeOf(files.destinationPath(task, AudioSourceType.TIDAL)))
        }
    }

    @Test
    fun aServerThatIgnoresTheRangeRestartsTheStagedFile(): Unit = runBlocking {
        withRoot { root ->
            val files = IosDownloadFiles(root)
            val task = task()
            files.ensureDirectories(task.id)
            writeStaged(files.stagingPath(task.id), 100)
            val body = ByteArray(12) { TAIL_BYTE }
            val transfer = transfer(files, MockEngine {
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, body.size.toString()))
            })
            val committed = transfer.transfer(task, stream(), resumeFromBytes = 64, song = null, progress = { _, _ -> })
            assertEquals(12L, committed)
            assertEquals(12L, files.sizeOf(files.destinationPath(task, AudioSourceType.TIDAL)))
        }
    }

    @Test
    fun anUnsatisfiableRangeCommitsOnlyTheBytesItAdmits(): Unit = runBlocking {
        withRoot { root ->
            val files = IosDownloadFiles(root)
            val task = task()
            files.ensureDirectories(task.id)
            writeStaged(files.stagingPath(task.id), 100)
            val transfer = transfer(files, MockEngine { respond(ByteArray(0), HttpStatusCode.RequestedRangeNotSatisfiable) })
            val committed = transfer.transfer(task, stream(), resumeFromBytes = 64, song = null, progress = { _, _ -> })
            assertEquals(64L, committed)
            assertEquals(64L, files.sizeOf(files.destinationPath(task, AudioSourceType.TIDAL)))
        }
    }

    private suspend fun withRoot(block: suspend (String) -> Unit) {
        val root = NSTemporaryDirectory() + "download-resume-" + Random.nextInt(1, Int.MAX_VALUE) + "/"
        try {
            block(root)
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(root, null)
        }
    }

    private fun transfer(files: IosDownloadFiles, engine: MockEngine) =
        IosDownloadTransfer(HttpClient(engine), files, index = null, nowMs = { 0L })

    private fun task() = DownloadTask(
        id = "request-1",
        ref = ContentRef(provider = "tidal", kind = ContentKind.SONG, id = "42"),
        mediaId = "42",
        provider = "tidal",
        title = "Song",
        source = AudioSourceType.TIDAL,
        quality = "LOSSLESS",
        phase = DownloadPhase.DOWNLOADING,
        bytesDownloaded = 64,
        totalBytes = null,
        resumeBytes = 64,
        failReason = null,
        updateTimeMs = 0L,
    )

    private fun stream() = DirectStream(
        uri = "https://audio.invalid/song",
        mimeType = "audio/flac",
        codecs = "flac",
        contentLength = null,
        label = "Tidal LOSSLESS",
        source = AudioSourceType.TIDAL,
    )

    /** Stages [size] bytes, the way a transfer that was interrupted before its progress row landed leaves them. */
    private fun writeStaged(path: String, size: Int) {
        val bytes = ByteArray(size) { STAGED_BYTE }
        val file = fopen(path, "wb") ?: error("Cannot stage the fixture")
        bytes.usePinned { fwrite(it.addressOf(0), 1u, size.toULong(), file) }
        fclose(file)
    }

    private companion object {
        private const val STAGED_BYTE: Byte = 0x11
        private const val TAIL_BYTE: Byte = 0x7F
    }
}
