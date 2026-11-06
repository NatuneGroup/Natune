package moe.rukamori.archivetune.backup

import android.system.Os
import java.io.File
import java.io.FileOutputStream
import moe.rukamori.archivetune.shared.data.backup.RestoreFileOps

/**
 * Android file primitives for [moe.rukamori.archivetune.shared.data.backup.RestoreTransaction].
 *
 * `Os.rename` is the platform's POSIX rename and is available below the app's API 26 NIO floor. Every
 * path used by the restore coordinator is app-private and staged beside its live target; these
 * methods do not accept user-supplied archive paths.
 */
internal class AndroidRestoreFileOps : RestoreFileOps {
    override fun exists(path: String): Boolean = File(checked(path)).exists()

    override fun atomicRename(from: String, to: String) {
        val source = File(checked(from))
        val target = File(checked(to))
        target.parentFile?.let { parent ->
            require(parent.isDirectory || parent.mkdirs()) { "Restore target parent unavailable" }
        }
        Os.rename(source.absolutePath, target.absolutePath)
    }

    override fun delete(path: String) {
        val file = File(checked(path))
        if (file.exists() && !file.delete()) {
            throw IllegalStateException("Restore file could not be removed")
        }
    }

    override fun writeAtomically(path: String, bytes: ByteArray) {
        val target = File(checked(path))
        val parent = target.parentFile
            ?: throw IllegalArgumentException("Restore path has no parent")
        require(parent.isDirectory || parent.mkdirs()) { "Restore target parent unavailable" }
        val temporary = File.createTempFile(".restore-", ".part", parent)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            Os.rename(temporary.absolutePath, target.absolutePath)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    override fun readBytes(path: String): ByteArray? =
        File(checked(path)).takeIf { it.exists() }?.readBytes()

    override fun list(directory: String): List<String> =
        File(checked(directory))
            .listFiles()
            ?.filter { it.isFile }
            ?.map { it.absolutePath }
            .orEmpty()

    private fun checked(path: String): String {
        require(path.isNotBlank() && '\u0000' !in path) { "Invalid restore path" }
        return path
    }
}
