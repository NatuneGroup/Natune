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
