@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.storage

import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.posix.*
import moe.rukamori.archivetune.shared.domain.storage.StorageArea
import moe.rukamori.archivetune.shared.domain.storage.StorageConsumers

/** The iOS storage consumers over the app's sandbox roots; identical semantics to the desktop implementation. */
class IosStorageConsumers(
    private val areas: Map<StorageArea, () -> String>,
) : StorageConsumers {
    private val used = MutableStateFlow<Map<StorageArea, Long>>(emptyMap())

    override suspend fun usedBytes(): Map<StorageArea, Long> = withContext(Dispatchers.Default) {
        val measured = areas.mapValues { (_, dir) -> sizeOf(dir()) }
        used.value = measured
        measured
    }

    override fun observeUsedBytes(): Flow<Map<StorageArea, Long>> = used.asStateFlow()

    override suspend fun clearCache(area: StorageArea) {
        require(area != StorageArea.DOWNLOADS) { "Downloads are removed explicitly, never cache-cleared" }
        val dir = areas[area]?.invoke() ?: return
        withContext(Dispatchers.Default) {
            val manager = NSFileManager.defaultManager
            val entries = manager.contentsOfDirectoryAtPath(dir, null) ?: return@withContext
            for (entry in entries) {
                (entry as? String)?.let { manager.removeItemAtPath("$dir/$it", null) }
            }
        }
        usedBytes()
    }

    override suspend fun applyCacheLimits(limits: Map<StorageArea, Long>) {
        withContext(Dispatchers.Default) {
            for ((area, limit) in limits) {
                if (limit < 0 || area == StorageArea.DOWNLOADS || area == StorageArea.STAGING) continue
                val dir = areas[area]?.invoke() ?: continue
                val files = mutableListOf<Pair<String, Long>>() // path to modified epoch ms
                collectFiles(dir, files)
                var total = files.sumOf { sizeOfPath(it.first) }
                if (total <= limit) continue
                files.sortBy { it.second }
                for ((file, _) in files) {
                    if (total <= limit) break
                    val size = sizeOfPath(file)
                    NSFileManager.defaultManager.removeItemAtPath(file, null)
                    total -= size
                }
            }
        }
        usedBytes()
    }

    override suspend fun currentStorageLabel(): String? =
        areas[StorageArea.DOWNLOADS]?.invoke()

    override suspend fun commitStorageFolder(newRoot: String): String? = withContext(Dispatchers.Default) {
        val manager = NSFileManager.defaultManager
        for ((_, dirOf) in areas) {
            val from = dirOf()
            if (!manager.fileExistsAtPath(from)) continue
            val to = "$newRoot/${from.substringAfterLast('/')}"
            currentCoroutineContext().ensureActive()
            if (manager.fileExistsAtPath(to)) {
                mergeInto(from, to)
                manager.removeItemAtPath(from, null)
            } else {
                check(manager.moveItemAtPath(from, to, null)) { "Storage move failed" }
            }
        }
        usedBytes()
        newRoot
    }

    private fun mergeInto(from: String, to: String) {
        val manager = NSFileManager.defaultManager
        val entries = manager.contentsOfDirectoryAtPath(from, null) ?: return
        for (entry in entries) {
            val name = entry as? String ?: continue
            val src = "$from/$name"; val dst = "$to/$name"
            if (manager.fileExistsAtPath(dst)) mergeInto(src, dst) else manager.moveItemAtPath(src, dst, null)
        }
    }

    private fun collectFiles(dir: String, out: MutableList<Pair<String, Long>>) {
        val d = opendir(dir) ?: return
        try {
            while (true) {
                val entry = readdir(d) ?: break
                val name = entry.pointed.d_name.toKString()
                if (name == "." || name == "..") continue
                val path = "$dir/$name"
                memScoped {
                    val info = alloc<stat>()
                    if (stat(path, info.ptr) != 0) return@memScoped
                    if (S_ISDIR(info.st_mode)) collectFiles(path, out)
                    else out += path to info.st_mtimespec.tv_sec * 1000L
                }
            }
        } finally {
            closedir(d)
        }
    }

    private fun sizeOf(dir: String): Long {
        var total = 0L
        val d = opendir(dir) ?: return 0L
        try {
            while (true) {
                val entry = readdir(d) ?: break
                val name = entry.pointed.d_name.toKString()
                if (name == "." || name == "..") continue
                val path = "$dir/$name"
                memScoped {
                    val info = alloc<stat>()
                    if (stat(path, info.ptr) != 0) return@memScoped
                    total += if (S_ISDIR(info.st_mode)) sizeOf(path) else info.st_size
                }
            }
        } finally {
            closedir(d)
        }
        return total
    }

    private fun sizeOfPath(path: String): Long = memScoped {
        val info = alloc<stat>()
        if (stat(path, info.ptr) != 0) 0L else info.st_size
    }
}
