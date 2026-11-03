package moe.rukamori.archivetune.shared.ui.acoustic

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Image as SkiaImage

/**
 * Skia raster upload for the acoustic heat graph. ARGB ints map one-to-one onto BGRA_8888 on a
 * little-endian target, so the bytes go in blue-first without a channel shuffle. A raster that
 * cannot be uploaded reads as the absent image, which the graph draws as its grid alone.
 */
internal actual fun acousticHeatImage(pixels: IntArray, width: Int, height: Int): ImageBitmap? {
    if (pixels.isEmpty() || width <= 0 || height <= 0 || pixels.size.toLong() != width.toLong() * height) {
        return null
    }
    val bytes = ByteArray(pixels.size * 4)
    for (index in pixels.indices) {
        val argb = pixels[index]
        val at = index * 4
        bytes[at] = (argb and 0xFF).toByte() // blue
        bytes[at + 1] = ((argb ushr 8) and 0xFF).toByte() // green
        bytes[at + 2] = ((argb ushr 16) and 0xFF).toByte() // red
        bytes[at + 3] = ((argb ushr 24) and 0xFF).toByte() // alpha
    }
    return runCatching {
        SkiaImage.makeRaster(
            ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL),
            bytes,
            width * 4,
        ).toComposeImageBitmap()
    }.getOrNull()
}
