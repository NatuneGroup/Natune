package moe.rukamori.archivetune.desktop

import androidx.compose.ui.graphics.ImageBitmap
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType

internal data class DesktopDownloadFile(val path: Path, val extension: String)

/** Only the download repository can vouch for the committed file and its byte count. */
internal suspend fun desktopDownloadFile(owner: DesktopDownloadsOwner, ref: ContentRef): DesktopDownloadFile? {
    val row = owner.listedRows().firstOrNull { it.provider == ref.provider && it.mediaId == ref.id } ?: return null
    val source = AudioSourceType.entries.firstOrNull { it.name == row.source } ?: return null
    val path = owner.fileOf(row.provider, row.mediaId, source, row.quality.orEmpty()) ?: return null
    val extension = withContext(Dispatchers.IO) {
        Files.newInputStream(path).use { desktopAudioExtension(it.readNBytes(256)) }
    }
    return DesktopDownloadFile(path, extension)
}

/** Detect the actual container, not the requested quality tier or a guessed mp3 suffix. */
internal fun desktopAudioExtension(prefix: ByteArray): String {
    fun starts(vararg bytes: Int): Boolean =
        prefix.size >= bytes.size && bytes.indices.all { prefix[it].toInt() and 255 == bytes[it] }
    fun ascii(offset: Int, value: String): Boolean =
        prefix.size >= offset + value.length && value.indices.all { prefix[offset + it].toInt() == value[it].code }
    return when {
        ascii(0, "fLaC") -> "flac"
        ascii(0, "OggS") -> "ogg"
        ascii(0, "RIFF") && ascii(8, "WAVE") -> "wav"
        ascii(4, "ftyp") -> "m4a"
        starts(0x1a, 0x45, 0xdf, 0xa3) -> if (prefix.toString(Charsets.ISO_8859_1).contains("webm")) "webm" else "mka"
        ascii(0, "ID3") -> "mp3"
        starts(0xff) && prefix.size >= 2 && prefix[1].toInt() and 0xf6 == 0xf0 -> "aac"
        starts(0xff) && prefix.size >= 2 && prefix[1].toInt() and 0xe0 == 0xe0 &&
            prefix[1].toInt() and 0x06 != 0 -> "mp3"
        else -> "bin"
    }
}

/** Never truncate an existing destination on a failed or cancelled export. */
internal suspend fun copyDesktopContentFile(source: Path, destination: Path) = withContext(Dispatchers.IO) {
    val target = destination.toAbsolutePath().normalize()
    require(source.toAbsolutePath().normalize() != target)
    val temporary = Files.createTempFile(target.parent, ".natune-export-", ".tmp")
    try {
        Files.newInputStream(source).use { input ->
            Files.newOutputStream(temporary).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        }
        currentCoroutineContext().ensureActive()
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Unit
    } finally { Files.deleteIfExists(temporary) }
}

internal suspend fun writeDesktopContentPng(bitmap: ImageBitmap, destination: Path) {
    val width = bitmap.width
    val height = bitmap.height
    require(width > 0 && height > 0 && width.toLong() * height <= 8_000_000)
    // Skia readback stays on the caller's UI thread; encoding and file IO do not.
    val pixels = IntArray(width * height)
    bitmap.readPixels(pixels)
    withContext(Dispatchers.IO) {
        val target = destination.toAbsolutePath().normalize()
        val temporary = Files.createTempFile(target.parent, ".natune-image-", ".png")
        try {
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            image.setRGB(0, 0, width, height, pixels, 0, width)
            check(ImageIO.write(image, "png", temporary.toFile()))
            currentCoroutineContext().ensureActive()
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
}
