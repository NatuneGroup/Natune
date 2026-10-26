package moe.rukamori.archivetune.shared.data.diagnostics

import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The log directory of a desktop instance. Each append opens, writes and closes the part, so a process that dies holds no
 * buffered lines and no open handle; the log batches its writes, so this costs one open per burst, not per line.
 */
class JvmDiagnosticLogFiles(directory: Path) : DiagnosticLogFiles {
    private val root: Path = directory.toAbsolutePath().normalize()

    override suspend fun list(): List<DiagnosticLogFile> = withContext(Dispatchers.IO) {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return@withContext emptyList()
        root.listDirectoryEntries().filter { it.isRegularFile(LinkOption.NOFOLLOW_LINKS) }
            .map { DiagnosticLogFile(it.name, Files.size(it)) }
    }

    override suspend fun append(name: String, text: String): Boolean = withContext(Dispatchers.IO) {
        Files.createDirectories(root)
        Files.write(fileOf(name), text.toByteArray(Charsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        true
    }

    override suspend fun readTail(name: String, maxBytes: Long): ByteArray = withContext(Dispatchers.IO) {
        RandomAccessFile(fileOf(name).toFile(), "r").use { access ->
            val length = access.length()
            val take = minOf(length, maxBytes).coerceAtLeast(0L)
            val bytes = ByteArray(take.toInt())
            access.seek(length - take)
            access.readFully(bytes)
            bytes
        }
    }

    override suspend fun delete(name: String) {
        withContext(Dispatchers.IO) { Files.deleteIfExists(fileOf(name)) }
    }

    private fun fileOf(name: String): Path {
        require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name) {
            "Log file name must be a plain file name"
        }
        return root.resolve(name)
    }
}
