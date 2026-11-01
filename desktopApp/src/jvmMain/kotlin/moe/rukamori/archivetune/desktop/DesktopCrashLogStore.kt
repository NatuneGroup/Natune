package moe.rukamori.archivetune.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.domain.diagnostics.CrashLogRecord
import moe.rukamori.archivetune.shared.domain.diagnostics.CrashLogStore
import moe.rukamori.archivetune.shared.domain.diagnostics.MAX_CRASH_LOG_RECORDS
import moe.rukamori.archivetune.shared.domain.diagnostics.crashLogRecordFileName
import moe.rukamori.archivetune.shared.domain.diagnostics.crashLogRecordFileTimestamp
import moe.rukamori.archivetune.shared.domain.diagnostics.decodeCrashLogRecord
import moe.rukamori.archivetune.shared.domain.diagnostics.encodeCrashLogRecord
import moe.rukamori.archivetune.shared.domain.diagnostics.isCrashLogRecordFileName
import moe.rukamori.archivetune.shared.domain.diagnostics.isValidCrashLogRecordId

/**
 * The bounded crash-record store under `<instance>/feedback/records`. [save] is a plain call because
 * the capture runs on a dying thread: one `<id>.tmp` written, moved over the final name and the
 * folder pruned to the newest [MAX_CRASH_LOG_RECORDS] files.
 */
internal class DesktopCrashLogStore(private val folder: Path) : CrashLogStore {
    override fun save(record: CrashLogRecord): Boolean = try {
        Files.createDirectories(folder)
        val name = crashLogRecordFileName(record.id)
        val target = folder.resolve(name)
        val temporary = folder.resolve("$name.tmp")
        Files.writeString(temporary, encodeCrashLogRecord(record))
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (atomicFailure: Exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
        prune()
        true
    } catch (_: Throwable) {
        false
    }

    override suspend fun list(): List<CrashLogRecord> = withContext(Dispatchers.IO) {
        recordFiles().mapNotNull(::readFile).sortedByDescending { it.timestampMs }
    }

    override suspend fun read(id: String): CrashLogRecord? = withContext(Dispatchers.IO) {
        if (!isValidCrashLogRecordId(id)) return@withContext null
        readFile(crashLogRecordFileName(id))
    }

    override suspend fun clear(): Boolean = withContext(Dispatchers.IO) {
        var cleared = true
        recordFiles().forEach { name ->
            try {
                Files.deleteIfExists(folder.resolve(name))
            } catch (failure: Exception) {
                cleared = false
            }
        }
        cleared
    }

    /** The record files in [folder]; a folder that does not exist yet reads as empty. */
    private fun recordFiles(): List<String> = try {
        Files.list(folder).use { stream ->
            stream.map { it.fileName.toString() }.filter(::isCrashLogRecordFileName).toList()
        }
    } catch (failure: Exception) {
        emptyList()
    }

    /** The newest [MAX_CRASH_LOG_RECORDS] stay; a file whose name carries no timestamp is pruned first. */
    private fun prune() {
        recordFiles()
            .sortedByDescending { crashLogRecordFileTimestamp(it) ?: 0L }
            .drop(MAX_CRASH_LOG_RECORDS)
            .forEach { name ->
                try {
                    Files.deleteIfExists(folder.resolve(name))
                } catch (failure: Exception) {
                    // A file that survives pruning is still listed; it is never a save failure.
                }
            }
    }

    private fun readFile(name: String): CrashLogRecord? = try {
        decodeCrashLogRecord(Files.readString(folder.resolve(name)))
    } catch (failure: Exception) {
        null
    }
}
