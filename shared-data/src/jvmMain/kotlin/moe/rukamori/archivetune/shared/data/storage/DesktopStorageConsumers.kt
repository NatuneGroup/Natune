package moe.rukamori.archivetune.shared.data.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import moe.rukamori.archivetune.shared.domain.storage.StorageArea
import moe.rukamori.archivetune.shared.domain.storage.StorageConsumers

/**
 * The desktop storage consumers over the instance's cache directories. Each area names one directory; clearing
 * deletes its contents but keeps the directory, and the limit pass evicts least-recently-modified files first, the
 * same rule the retained stream cache uses. [downloadsRoot] moves with the cache areas on a folder commit, and the
 * published index paths are content-addressed, so a moved tree needs no row rewrite.
 */
class DesktopStorageConsumers(
    private val areas: Map<StorageArea, () -> Path>,
    private val labelOf: (Path) -> String,
) : StorageConsumers {
    private val used = MutableStateFlow<Map<StorageArea, Long>>(emptyMap())

    override suspend fun usedBytes(): Map<StorageArea, Long> = withContext(Dispatchers.IO) {
        val measured = areas.mapValues { (_, dir) -> dir().let { if (Files.isDirectory(it)) sizeOf(it) else 0L } }
        used.value = measured
        measured
    }

    override fun observeUsedBytes(): Flow<Map<StorageArea, Long>> = used.asStateFlow()

    override suspend fun clearCache(area: StorageArea) {
        require(area != StorageArea.DOWNLOADS) { "Downloads are removed explicitly, never cache-cleared" }
        val dir = areas[area]?.invoke() ?: return
        withContext(Dispatchers.IO) {
            if (!Files.isDirectory(dir)) return@withContext
            Files.newDirectoryStream(dir).use { entries ->
                for (entry in entries) deleteRecursively(entry)
            }
        }
        usedBytes()
    }

    override suspend fun applyCacheLimits(limits: Map<StorageArea, Long>) {
        withContext(Dispatchers.IO) {
            for ((area, limit) in limits) {
                if (limit < 0 || area == StorageArea.DOWNLOADS || area == StorageArea.STAGING) continue
                val dir = areas[area]?.invoke() ?: continue
                if (!Files.isDirectory(dir)) continue
                val files = mutableListOf<Path>()
                Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        files += file; return FileVisitResult.CONTINUE
                    }
                })
                var total = files.sumOf { Files.size(it) }
                if (total <= limit) continue
                files.sortBy { Files.getLastModifiedTime(it).toMillis() }
                for (file in files) {
                    if (total <= limit) break
                    val size = Files.size(file)
                    Files.deleteIfExists(file)
                    total -= size
                }
            }
        }
        usedBytes()
    }

    override suspend fun currentStorageLabel(): String? =
        areas[StorageArea.DOWNLOADS]?.invoke()?.let(labelOf)

    override suspend fun commitStorageFolder(newRoot: String): String? = withContext(Dispatchers.IO) {
        val target = Paths.get(newRoot).toAbsolutePath().normalize()
        require(Files.isDirectory(target)) { "Storage folder is not a directory" }
        for ((area, dirOf) in areas) {
            val from = dirOf().toAbsolutePath().normalize()
            if (!Files.exists(from)) continue
            val to = target.resolve(from.fileName.toString())
            currentCoroutineContext().ensureActive()
            Files.createDirectories(to.parent)
            if (Files.exists(to)) {
                moveMerging(from, to)
            } else {
                Files.move(from, to)
            }
        }
        usedBytes()
        labelOf(target)
    }

    private fun moveMerging(from: Path, to: Path) {
        Files.walkFileTree(from, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()))
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.move(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING)
                return FileVisitResult.CONTINUE
            }
        })
        deleteRecursively(from)
    }

    private fun deleteRecursively(path: Path) {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.newDirectoryStream(path).use { for (entry in it) deleteRecursively(entry) }
        }
        Files.deleteIfExists(path)
    }

    private fun sizeOf(dir: Path): Long {
        var total = 0L
        Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                total += attrs.size(); return FileVisitResult.CONTINUE
            }
        })
        return total
    }
}
