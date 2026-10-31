package moe.rukamori.archivetune.desktop

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.domain.mediainfo.AudioContainer

internal data class DesktopCompletedDownload(val path: Path, val bytes: Long)

/** Hashed storage names carry no format. Unknown headers export as binary rather than inventing an audio type. */
internal suspend fun desktopDownloadExtension(file: Path): String = withContext(Dispatchers.IO) {
    Files.newInputStream(file).use { input ->
        AudioContainer.detect(input.readNBytes(AudioContainer.PROBE_BYTES))?.extension ?: "bin"
    }
}

/** Staging beside the destination keeps cancellation, a lost source or a size mismatch from truncating an old export. */
internal suspend fun copyDesktopDownload(download: DesktopCompletedDownload, destination: Path) {
    withContext(Dispatchers.IO) {
        val source = download.path.toAbsolutePath().normalize()
        val target = destination.toAbsolutePath().normalize()
        require(download.bytes > 0L && Files.isRegularFile(source)) { "The completed download is missing" }
        require(source != target && (!Files.exists(target) || !Files.isSameFile(source, target))) {
            "An export must not overwrite its stored download"
        }
        currentCoroutineContext().ensureActive()
        val staging = Files.createTempFile(target.parent, ".natune-export-", ".part")
        try {
            var copied = 0L
            Files.newInputStream(source).use { input ->
                Files.newOutputStream(staging).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        check(copied <= download.bytes) { "The stored download changed while exporting" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            check(copied == download.bytes) { "The stored download changed while exporting" }
            FileChannel.open(staging, StandardOpenOption.WRITE).use { it.force(true) }
            currentCoroutineContext().ensureActive()
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            withContext(NonCancellable) { Files.deleteIfExists(staging) }
        }
    }
}
