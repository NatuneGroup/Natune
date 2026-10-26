package moe.rukamori.archivetune.shared.data.localmedia

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import moe.rukamori.archivetune.shared.domain.localmedia.LocalMediaSource
import moe.rukamori.archivetune.shared.domain.localmedia.LocalTrackCandidate

/**
 * The desktop filesystem source behind the shared scan owner, the counterpart of MediaStore's device query. It
 * walks the configured roots without following symlinks, keeps only files [SupportedLocalAudio] admits, and reads
 * a bounded head plus tail for the tag reader. Extracted artwork lands under [artworkRoot] named by track id, so
 * every rescan of an unchanged file keeps the same stored artwork path.
 */
class JvmLocalMediaSource(
    private val roots: () -> List<Path>,
    private val artworkRoot: Path,
) : LocalMediaSource {
    override suspend fun enumerate(): List<LocalTrackCandidate> = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        val found = linkedMapOf<Path, LocalTrackCandidate>()
        roots().map { it.toAbsolutePath().normalize() }.distinct().forEach { root ->
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return@forEach
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    context.ensureActive()
                    if (!attrs.isRegularFile || file in found) return FileVisitResult.CONTINUE
                    val name = file.fileName.toString()
                    if (!SupportedLocalAudio.isSupported(name, probeMime(file))) return FileVisitResult.CONTINUE
                    val parent = file.parent?.toAbsolutePath()?.toString() ?: root.toString()
                    found[file] = LocalTrackCandidate(
                        path = file.toAbsolutePath().toString(), name = name,
                        folder = parent, modifiedEpochMs = attrs.lastModifiedTime().toMillis(),
                        sizeBytes = attrs.size(), mimeHint = probeMime(file),
                    )
                    return FileVisitResult.CONTINUE
                }
            })
        }
        found.values.toList()
    }

    override suspend fun readTagBytes(candidate: LocalTrackCandidate): Pair<ByteArray, ByteArray> =
        withContext(Dispatchers.IO) {
            val path = Paths.get(candidate.path)
            val head = Files.newByteChannel(path).use { channel ->
                val buffer = ByteArray(minOf(HEAD_BYTES, channel.size().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
                var read = 0
                while (read < buffer.size) {
                    val count = channel.read(java.nio.ByteBuffer.wrap(buffer, read, buffer.size - read))
                    if (count < 0) break
                    read += count
                }
                buffer.copyOf(read)
            }
            val tail = if (candidate.sizeBytes > head.size) {
                Files.newByteChannel(path).use { channel ->
                    val size = minOf(TAIL_BYTES.toLong(), candidate.sizeBytes - head.size).toInt()
                    val buffer = ByteArray(size)
                    channel.position(candidate.sizeBytes - size)
                    var read = 0
                    while (read < buffer.size) {
                        val count = channel.read(java.nio.ByteBuffer.wrap(buffer, read, buffer.size - read))
                        if (count < 0) break
                        read += count
                    }
                    buffer.copyOf(read)
                }
            } else ByteArray(0)
            head to tail
        }

    override suspend fun storeArtwork(trackId: String, artwork: ByteArray, mime: String): String? =
        withContext(Dispatchers.IO) {
            val extension = when (mime) {
                "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg"
            }
            val target = artworkRoot.resolve("${trackId.substringAfter(':')}.$extension")
            Files.createDirectories(artworkRoot)
            Files.write(target, artwork)
            target.toUri().toString()
        }

    private fun probeMime(file: Path): String? = try {
        Files.probeContentType(file)
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val HEAD_BYTES = 256 * 1024
        const val TAIL_BYTES = 64 * 1024
    }
}
