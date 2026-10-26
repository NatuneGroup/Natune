/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.localmedia

import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlinx.coroutines.*

data class ScannedLocalMedia(val path: Path, val size: Long, val modifiedMs: Long, val contentSha256: String)

/** Does not follow symlinks. Exclusions use path components, not ambiguous string prefixes. */
class JvmLocalMediaScanner {
    suspend fun scan(roots: List<Path>, excluded: List<Path> = emptyList(), maxFiles: Int = 100_000): List<ScannedLocalMedia> =
        withContext(Dispatchers.IO) {
            require(maxFiles > 0)
            val context = currentCoroutineContext()
            val exclude = excluded.map { it.toAbsolutePath().normalize() }
            val found = linkedMapOf<Path, ScannedLocalMedia>()
            roots.map { it.toAbsolutePath().normalize() }.distinct().forEach { root ->
                require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) { "Scan root is not an accessible directory" }
                Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        context.ensureActive()
                        return if (exclude.any(dir::startsWith)) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                    }
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        context.ensureActive()
                        if (!attrs.isRegularFile || file in found || !SupportedLocalAudio.isSupported(file.fileName.toString(), null))
                            return FileVisitResult.CONTINUE
                        require(found.size < maxFiles) { "Local media scan exceeds file limit" }
                        val digest = MessageDigest.getInstance("SHA-256")
                        Files.newInputStream(file).use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                context.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                digest.update(buffer, 0, count)
                            }
                        }
                        val after = Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                        // A modified-during-scan file is not an exact rebinding candidate.
                        if (after.size() == attrs.size() && after.lastModifiedTime() == attrs.lastModifiedTime())
                            found[file] = ScannedLocalMedia(file, attrs.size(), attrs.lastModifiedTime().toMillis(),
                                digest.digest().joinToString("") { "%02x".format(it) })
                        return FileVisitResult.CONTINUE
                    }
                })
            }
            found.values.toList()
        }

    fun rebind(missing: ScannedLocalMedia, candidates: List<ScannedLocalMedia>): ScannedLocalMedia? {
        if (Files.exists(missing.path)) return null
        return candidates.filter { it.size == missing.size && it.contentSha256 == missing.contentSha256 }.singleOrNull()
    }
}
