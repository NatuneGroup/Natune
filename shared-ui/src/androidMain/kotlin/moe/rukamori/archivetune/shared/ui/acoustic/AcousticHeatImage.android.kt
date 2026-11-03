package moe.rukamori.archivetune.shared.ui.acoustic

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Android backing for the acoustic heat image: an ARGB_8888 bitmap filled from the raster's ARGB ints,
 * one-to-one without a channel shuffle. A raster that cannot be allocated reads as the absent image,
 * which the graph draws as its grid alone.
 */
internal actual fun acousticHeatImage(pixels: IntArray, width: Int, height: Int): ImageBitmap? {
    if (pixels.isEmpty() || width <= 0 || height <= 0 || pixels.size.toLong() != width.toLong() * height) {
        return null
    }
    return runCatching {
        Bitmap.createBitmap(pixels, 0, width, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }.getOrNull()
}
