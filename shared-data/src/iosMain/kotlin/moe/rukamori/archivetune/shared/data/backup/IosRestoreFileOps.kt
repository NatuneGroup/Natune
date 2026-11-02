@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.backup

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.DT_DIR
import platform.posix.ENOENT
import platform.posix.ENOTDIR
import platform.posix.F_OK
import platform.posix.access
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.ferror
import platform.posix.fopen
import platform.posix.fread
import platform.posix.opendir
import platform.posix.posix_errno
import platform.posix.readdir
import platform.posix.rename
import platform.posix.unlink

/**
 * POSIX file primitives for the restore swap on iOS. Every rename — [atomicRename] itself and the one that
 * publishes a [writeAtomically] temp file — fsyncs the directory that recorded it, so a crash cannot resurrect
 * a name the journal already moved past. Staging reuses [writePortableFileBytes], which reports fwrite, fflush,
 * fsync and fclose individually, and reads keep the same checked-loop shape as [readPortableFileBytes].
 */
class IosRestoreFileOps : RestoreFileOps {

    override fun exists(path: String): Boolean = access(path, F_OK) == 0

    override fun atomicRename(from: String, to: String) {
        if (rename(from, to) != 0) throw ioFailure("rename $from -> $to")
        syncPortableDirectory(parentOf(to))
    }

    /** A missing file is already deleted; only a real failure is worth reporting. */
    override fun delete(path: String) {
        if (unlink(path) != 0 && posix_errno() != ENOENT) throw ioFailure("unlink $path")
    }

    override fun writeAtomically(path: String, bytes: ByteArray) {
        // The temp file sits next to its target, so the directory sync inside atomicRename covers both names.
        val tempPath = "$path.tmp"
        try {
            writePortableFileBytes(tempPath, bytes)
            atomicRename(tempPath, path)
        } catch (failure: Throwable) {
            unlink(tempPath)
            throw failure
        }
    }

    override fun readBytes(path: String): ByteArray? {
        // A missing journal or sidecar is normal; any other open failure is not absence and must report.
        val file = fopen(path, "rb") ?: run {
            if (posix_errno() == ENOENT) return null
            throw ioFailure("fopen $path")
        }
        try {
            val chunks = ArrayList<ByteArray>()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            buffer.usePinned { pinned ->
                while (true) {
                    val read = fread(pinned.addressOf(0), 1u, buffer.size.toULong(), file)
                    if (read.toLong() == 0L) break
                    total += read.toLong()
                    // A ByteArray cannot hold more, so an oversized snapshot fails instead of truncating.
                    require(total <= Int.MAX_VALUE) { "File exceeds the size limit" }
                    chunks += buffer.copyOf(read.toInt())
                }
            }
            // A short read can be end of file or an error; only the error may pass silently.
            check(ferror(file) == 0) { "File read failed" }
            val bytes = ByteArray(total.toInt())
            var offset = 0
            chunks.forEach { chunk ->
                chunk.copyInto(bytes, offset)
                offset += chunk.size
            }
            return bytes
        } finally {
            check(fclose(file) == 0) { "File close failed" }
        }
    }

    /** Full paths, sorted, so the recovery machine can hand each entry straight to a rename. */
    override fun list(directory: String): List<String> {
        val handle = opendir(directory) ?: run {
            val error = posix_errno()
            if (error == ENOENT || error == ENOTDIR) return emptyList()
            throw ioFailure("opendir $directory")
        }
        try {
            val prefix = directory.trimEnd('/')
            val entries = ArrayList<String>()
            while (true) {
                val entry = readdir(handle) ?: break
                val name = entry.pointed.d_name.toKString()
                if (name == "." || name == "..") continue
                if (entry.pointed.d_type.toInt() == DT_DIR) continue
                entries += "$prefix/$name"
            }
            return entries.sorted()
        } finally {
            check(closedir(handle) == 0) { "Directory close failed" }
        }
    }

    /** The directory a rename lands in; a bare filename lives in the process working directory. */
    private fun parentOf(path: String): String {
        val index = path.lastIndexOf('/')
        return when {
            index > 0 -> path.substring(0, index)
            index == 0 -> "/"
            else -> "."
        }
    }

    private fun ioFailure(operation: String): IllegalStateException =
        IllegalStateException("$operation failed (errno ${posix_errno()})")
}
