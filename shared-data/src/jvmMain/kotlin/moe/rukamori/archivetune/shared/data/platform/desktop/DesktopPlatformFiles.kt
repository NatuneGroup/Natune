package moe.rukamori.archivetune.shared.data.platform.desktop

import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.domain.platform.FileHandle
import moe.rukamori.archivetune.shared.domain.platform.PlatformFiles
import kotlin.coroutines.coroutineContext

class DesktopPlatformFiles : PlatformFiles {
    override suspend fun read(file: FileHandle, maxBytes: Long): ByteArray = withContext(Dispatchers.IO) {
        require(maxBytes in 0..Int.MAX_VALUE.toLong())
        Files.newInputStream(path(file)).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var count = 0L
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                count += read
                require(count <= maxBytes) { "File exceeds permitted size" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }
    override suspend fun writeAtomic(file: FileHandle, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val target = path(file)
        val temporary = Files.createTempFile(target.parent, ".archivetune-", ".pending")
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) { coroutineContext.ensureActive(); channel.write(buffer) }
                channel.force(true)
            }
            coroutineContext.ensureActive()
            // An unsupported atomic rename is an error, never a delete-old-then-copy fallback.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            Unit
        } finally { Files.deleteIfExists(temporary) }
    }
    override suspend fun delete(file: FileHandle) = withContext(Dispatchers.IO) {
        Files.deleteIfExists(path(file)); Unit
    }
    private fun path(file: FileHandle): Path {
        val value = if (file.value.startsWith("file:")) Path.of(URI(file.value)) else Path.of(file.value)
        require(value.isAbsolute) { "Desktop file handle must be an absolute path or file URI" }
        return value.normalize()
    }
}
