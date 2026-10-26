@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.localmedia

import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.posix.*
import moe.rukamori.archivetune.shared.domain.localmedia.LocalMediaSource
import moe.rukamori.archivetune.shared.domain.localmedia.LocalTrackCandidate

/**
 * The iOS source behind the shared scan owner. The host imports audio through the document picker into
 * [scanRoot] (the app's imported-media directory); scanning walks that tree with the same supported-file filter,
 * folder rules and unchanged shortcut the desktop walk applies. Artwork extracted from tags lands under
 * [artworkRoot] named by track id.
 */
class IosLocalMediaSource(
    private val scanRoot: String,
    private val artworkRoot: String,
) : LocalMediaSource {
    override suspend fun enumerate(): List<LocalTrackCandidate> = withContext(Dispatchers.Default) {
        val found = mutableListOf<LocalTrackCandidate>()
        walk(scanRoot, found)
        found
    }

    private suspend fun walk(directory: String, found: MutableList<LocalTrackCandidate>) {
        val context = currentCoroutineContext()
        val dir = opendir(directory) ?: return
        try {
            while (true) {
                context.ensureActive()
                val entry = readdir(dir) ?: break
                val name = entry.pointed.d_name.toKString()
                if (name == "." || name == "..") continue
                val path = "$directory/$name"
                val info = memScoped {
                    val stat = alloc<stat>()
                    if (stat(path, stat.ptr) != 0) null else stat.st_mode to stat.st_mtimespec
                } ?: continue
                if (S_ISDIR(info.first)) {
                    walk(path, found)
                } else if (SupportedLocalAudio.isSupported(name, null)) {
                    found += LocalTrackCandidate(
                        path = path, name = name, folder = directory,
                        modifiedEpochMs = info.second.tv_sec * 1000L + info.second.tv_nsec / 1_000_000L,
                        sizeBytes = sizeOfPath(path), mimeHint = null,
                    )
                }
            }
        } finally {
            closedir(dir)
        }
    }

    private fun sizeOfPath(path: String): Long = memScoped {
        val info = alloc<stat>()
        if (stat(path, info.ptr) != 0) 0L else info.st_size
    }

    override suspend fun readTagBytes(candidate: LocalTrackCandidate): Pair<ByteArray, ByteArray> =
        withContext(Dispatchers.Default) {
            val head = readRange(candidate.path, 0L, HEAD_BYTES)
            val tail = if (candidate.sizeBytes > head.size) {
                readRange(candidate.path, candidate.sizeBytes - minOf(TAIL_BYTES, candidate.sizeBytes - head.size),
                    TAIL_BYTES)
            } else ByteArray(0)
            head to tail
        }

    private fun readRange(path: String, offset: Long, count: Int): ByteArray {
        val file = fopen(path, "rb") ?: return ByteArray(0)
        try {
            fseek(file, offset, SEEK_SET)
            val buffer = ByteArray(count)
            val read = buffer.usePinned { pinned ->
                fread(pinned.addressOf(0), 1u, count.toULong(), file).toInt()
            }
            return buffer.copyOf(read)
        } finally {
            fclose(file)
        }
    }

    override suspend fun storeArtwork(trackId: String, artwork: ByteArray, mime: String): String? =
        withContext(Dispatchers.Default) {
            val extension = when (mime) {
                "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg"
            }
            NSFileManager.defaultManager.createDirectoryAtPath(artworkRoot, true, null, null)
            val target = "$artworkRoot/${trackId.substringAfter(':')}.$extension"
            val file = fopen(target, "wb") ?: return@withContext null
            try {
                artwork.usePinned { pinned -> fwrite(pinned.addressOf(0), 1u, artwork.size.toULong(), file) }
            } finally {
                fclose(file)
            }
            "file://$target"
        }

    private companion object {
        const val HEAD_BYTES = 256 * 1024
        const val TAIL_BYTES = 64 * 1024
    }
}
