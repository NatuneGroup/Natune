package moe.rukamori.archivetune.shared.data.persistence

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.domain.platform.FileHandle
import moe.rukamori.archivetune.shared.domain.platform.PlatformFiles

/** Root-confined app-private files. User-selected external documents use the host's own adapter. */
class JvmPlatformFiles(root: Path) : PlatformFiles {
    private val root = Files.createDirectories(root.toAbsolutePath().normalize()).toRealPath()

    override suspend fun read(file: FileHandle, maxBytes: Long): ByteArray = withContext(Dispatchers.IO) {
        require(maxBytes in 0..Int.MAX_VALUE.toLong())
        Files.newInputStream(resolve(file)).use { input ->
            val bytes = input.readNBytes((maxBytes + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            require(bytes.size.toLong() <= maxBytes) { "File exceeds read limit" }
            if (maxBytes == Int.MAX_VALUE.toLong() && bytes.size == Int.MAX_VALUE)
                require(input.read() == -1) { "File exceeds read limit" }
            bytes
        }
    }
    override suspend fun writeAtomic(file: FileHandle, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val target = resolve(file)
        val staging = Files.createTempFile(target.parent, ".archivetune-", ".part")
        try {
            FileChannel.open(staging, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) {
                    currentCoroutineContext().ensureActive()
                    channel.write(buffer)
                }
                channel.force(true)
            }
            currentCoroutineContext().ensureActive()
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            Unit
        } finally { Files.deleteIfExists(staging) }
    }
    override suspend fun delete(file: FileHandle) = withContext(Dispatchers.IO) {
        Files.deleteIfExists(resolve(file)); Unit
    }
    private fun resolve(file: FileHandle): Path {
        val target = root.resolve(file.value).normalize()
        require(target.startsWith(root) && target != root) { "File handle escapes app storage" }
        // Refuse link traversal even for existing children of the app-private directory.
        var cursor = root
        for (part in root.relativize(target)) {
            cursor = cursor.resolve(part)
            require(!Files.isSymbolicLink(cursor)) { "File handle traverses a symbolic link" }
        }
        require(Files.isDirectory(target.parent, LinkOption.NOFOLLOW_LINKS)) { "File parent does not exist" }
        return target
    }
}
