@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.platform.ios

import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
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
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.remove
import platform.posix.rename

/**
 * The bounded crash-record store under the diagnostics directory. [save] is a plain call because the
 * capture runs on a dying thread: one `<id>.tmp` written, renamed over the final name and the folder
 * pruned to the newest [MAX_CRASH_LOG_RECORDS] files, all with POSIX calls and NSFileManager so
 * nothing suspends mid-capture.
 */
class IosCrashLogStore(private val folder: String) : CrashLogStore {
    private val manager = NSFileManager.defaultManager

    override fun save(record: CrashLogRecord): Boolean = try {
        manager.createDirectoryAtPath(folder, true, null, null)
        val name = crashLogRecordFileName(record.id)
        val temporary = "$folder/$name.tmp"
        val bytes = encodeCrashLogRecord(record).encodeToByteArray()
        val output = fopen(temporary, "wb") ?: return false
        val written = bytes.usePinned {
            fwrite(it.addressOf(0), 1uL, bytes.size.toULong(), output)
        } == bytes.size.toULong()
        val saved = written && fclose(output) == 0 && rename(temporary, "$folder/$name") == 0
        if (saved) prune() else remove(temporary)
        saved
    } catch (_: Throwable) {
        false
    }

    override suspend fun list(): List<CrashLogRecord> = withContext(Dispatchers.Default) {
        recordFiles().mapNotNull { name -> readFile("$folder/$name") }
            .sortedByDescending { it.timestampMs }
    }

    override suspend fun read(id: String): CrashLogRecord? = withContext(Dispatchers.Default) {
        if (!isValidCrashLogRecordId(id)) return@withContext null
        readFile("$folder/${crashLogRecordFileName(id)}")
    }

    override suspend fun clear(): Boolean = withContext(Dispatchers.Default) {
        var cleared = true
        recordFiles().forEach { name -> if (remove("$folder/$name") != 0) cleared = false }
        cleared
    }

    /** The record files in [folder]; a folder that does not exist yet reads as empty. */
    private fun recordFiles(): List<String> {
        val names = memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            error.value = null
            manager.contentsOfDirectoryAtPath(folder, error.ptr)
        } ?: return emptyList()
        return names.filterIsInstance<String>().filter(::isCrashLogRecordFileName)
    }

    /** The newest [MAX_CRASH_LOG_RECORDS] stay; a file whose name carries no timestamp is pruned first. */
    private fun prune() {
        recordFiles()
            .sortedByDescending { crashLogRecordFileTimestamp(it) ?: 0L }
            .drop(MAX_CRASH_LOG_RECORDS)
            .forEach { remove("$folder/$it") }
    }

    private fun readFile(path: String): CrashLogRecord? {
        val text = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) ?: return null
        return decodeCrashLogRecord(text)
    }
}
