package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import kotlin.math.roundToInt

/**
 * The sleeve's dominant colours for the shared artwork theme seed. The image is sampled on a bounded grid (never every pixel of
 * a 4K cover), near-white/near-black and transparent samples are skipped, and quantised colours are ranked by how often
 * they occur times how saturated they are, so a grey background cannot beat the sleeve's actual accent. Returns at most
 * three distinct colours, dominant first; an image with no usable pixels returns an empty list and the default scheme
 * stays.
 */
fun artworkPalette(image: ImageBitmap): List<Color> {
    if (image.width <= 0 || image.height <= 0) return emptyList()
    val pixels = image.toPixelMap()
    val grid = 32
    val stepX = ((pixels.width + grid - 1) / grid).coerceAtLeast(1)
    val stepY = ((pixels.height + grid - 1) / grid).coerceAtLeast(1)
    val counts = HashMap<Int, Int>()
    var fallback: Int? = null
    var fallbackCount = 0
    for (py in 0 until pixels.height step stepY) {
        for (px in 0 until pixels.width step stepX) {
            val color = pixels[px, py]
            if (color.alpha < 0.5f) continue
            val r = (color.red * 255f).roundToInt()
            val g = (color.green * 255f).roundToInt()
            val b = (color.blue * 255f).roundToInt()
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val brightness = (r + g + b) / (3f * 255f)
            val saturation = if (max == 0) 0f else (max - min) / max.toFloat()
            if (brightness < 0.06f || brightness > 0.94f || saturation < 0.04f) continue
            val key = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            val next = (counts[key] ?: 0) + 1
            counts[key] = next
            if (fallback == null || next > fallbackCount) {
                fallback = key
                fallbackCount = next
            }
        }
    }
    fun keyColor(key: Int): Color = Color(
        ((key shr 8) and 0xF) * 17 / 255f,
        ((key shr 4) and 0xF) * 17 / 255f,
        (key and 0xF) * 17 / 255f,
    )
    if (counts.isEmpty()) return fallback?.let { listOf(keyColor(it)) }.orEmpty()
    return counts.entries
        .sortedByDescending { (key, count) ->
            val color = keyColor(key)
            val max = maxOf(color.red, color.green, color.blue)
            val min = minOf(color.red, color.green, color.blue)
            val saturation = if (max == 0f) 0f else (max - min) / max
            count * (1f + saturation * 2f)
        }
        .take(3)
        .map { keyColor(it.key) }
        .distinct()
}
