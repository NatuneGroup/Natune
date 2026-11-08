@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.modules

import moe.rukamori.archivetune.shared.data.backup.readPortableFileBytes
import moe.rukamori.archivetune.shared.data.backup.writePortableFileBytes
import platform.Foundation.NSFileManager
import platform.posix.rename

/** Small, atomic manager state; package descriptors and Keychain settings stay separate. */
internal class IosModuleOrderStore(private val root: String) {
    private val target = "$root/order.txt"
    fun read(): List<String> = try {
        readPortableFileBytes(target, 8192).decodeToString(throwOnInvalidSequence = true)
            .lineSequence().filter { it.matches(Regex("[a-z0-9][a-z0-9._-]{0,127}")) }
            .distinct().take(32).toList()
    } catch (_: Exception) { emptyList() }

    fun write(ids: List<String>) {
        moduleRequire(ids.size <= 32 && ids.distinct().size == ids.size &&
            ids.all { it.matches(Regex("[a-z0-9][a-z0-9._-]{0,127}")) })
        val manager = NSFileManager.defaultManager
        manager.createDirectoryAtPath(root, true, null, null)
        val temporary = "$target.tmp"
        try {
            writePortableFileBytes(temporary, ids.joinToString("\n").encodeToByteArray())
            check(rename(temporary, target) == 0) { "Cannot commit module order" }
        } finally { manager.removeItemAtPath(temporary, null) }
    }
}
