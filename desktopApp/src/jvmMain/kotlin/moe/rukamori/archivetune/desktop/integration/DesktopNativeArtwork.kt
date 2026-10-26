package moe.rukamori.archivetune.desktop.integration

import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

/** OS metadata must never cause an authenticated remote fetch or unbounded image allocation. */
internal fun readDesktopNativeArtwork(uri: String?): ByteArray? {
    if (uri.isNullOrEmpty()) return null
    return try {
        val location = URI(uri)
        if (location.scheme != "file" || !location.rawAuthority.isNullOrEmpty() ||
            location.rawQuery != null || location.rawFragment != null) return null
        val path = Path.of(location)
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) return null
        val bytes = Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(MAX_ARTWORK_BYTES + 1) }
        if (bytes.isEmpty() || bytes.size > MAX_ARTWORK_BYTES) return null
        MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) return null
            val reader = readers.next()
            try {
                reader.input = input
                if (reader.formatName.lowercase() !in setOf("png", "jpeg", "jpg")) return null
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width !in 1..MAX_ARTWORK_EDGE || height !in 1..MAX_ARTWORK_EDGE) return null
            } finally { reader.dispose() }
        }
        bytes
    } catch (_: Exception) { null } // Artwork is optional; never replace transport with a failed image.
}

private const val MAX_ARTWORK_BYTES = 4 * 1024 * 1024
private const val MAX_ARTWORK_EDGE = 2048
