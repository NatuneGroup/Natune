package moe.rukamori.archivetune.ui.screens.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.SuccessResult
import coil3.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.ui.theme.BoundedArtworkMemo
import moe.rukamori.archivetune.ui.theme.PlayerColorExtractor
import moe.rukamori.archivetune.ui.utils.LocalItemPreferences

private const val ARTWORK_CARD_GRADIENT_MEMO_ENTRIES = 64

private data class ArtworkCardGradientKey(
    val artworkUrl: String,
    val fallbackArgb: Int,
)

/**
 * Extracted library-card gradients, keyed by artwork + fallback colour.
 *
 * A row that scrolls out and back re-composes and re-runs its effect; without this every return
 * trip paid another Coil request and Palette pass for a colour set that cannot have changed.
 * Bounded and process-wide, the same shape the player's palette cache uses.
 */
private val artworkCardGradientMemo =
    BoundedArtworkMemo<ArtworkCardGradientKey, List<Color>>(ARTWORK_CARD_GRADIENT_MEMO_ENTRIES)

/**
 * The artwork-colour gradient behind a library row's card.
 *
 * [enabled] exists because a caller may only use the result for its active row (the songs list
 * colours just the playing row): an inactive row must not start a decode or a palette pass at
 * all rather than compute one and throw it away.
 */
@Composable
fun rememberArtworkGradient(
    thumbnailUrl: String?,
    fallbackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    enabled: Boolean = true,
): List<Color> {
    val context = LocalContext.current
    val fallback = remember(fallbackColor) { listOf(fallbackColor, fallbackColor.copy(alpha = 0.5f)) }
    if (!enabled || thumbnailUrl.isNullOrBlank()) return fallback

    val key =
        remember(thumbnailUrl, fallbackColor) {
            ArtworkCardGradientKey(artworkUrl = thumbnailUrl, fallbackArgb = fallbackColor.toArgb())
        }
    val cached = remember(key) { artworkCardGradientMemo.get(key) }
    var colors by remember(key) { mutableStateOf(cached ?: fallback) }

    LaunchedEffect(key, cached) {
        if (cached != null) return@LaunchedEffect
        val request =
            ImageRequest
                .Builder(context)
                .data(key.artworkUrl)
                // A card tint needs a thumbnail, not the full-resolution cover.
                .size(PlayerColorExtractor.Config.IMAGE_SIZE, PlayerColorExtractor.Config.IMAGE_SIZE)
                .allowHardware(false)
                .build()

        val extracted =
            try {
                val result =
                    withContext(Dispatchers.IO) {
                        context.imageLoader.execute(request)
                    }
                val bitmap = (result as? SuccessResult)?.image?.toBitmap()
                if (bitmap == null) {
                    null
                } else {
                    withContext(Dispatchers.Default) {
                        val palette =
                            Palette
                                .from(bitmap)
                                .maximumColorCount(PlayerColorExtractor.Config.MAX_COLOR_COUNT)
                                .resizeBitmapArea(PlayerColorExtractor.Config.BITMAP_AREA)
                                .generate()
                        PlayerColorExtractor.extractGradientColors(
                            palette = palette,
                            fallbackColor = key.fallbackArgb,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }

        if (extracted.isNullOrEmpty()) return@LaunchedEffect
        val settled =
            if (extracted.size >= 2) {
                extracted
            } else {
                listOf(extracted[0], extracted[0].copy(alpha = 0.5f))
            }
        artworkCardGradientMemo.put(key, settled)
        colors = settled
    }
    return colors
}

@Composable
fun rememberArtworkCardColor(
    thumbnailUrl: String?,
    fallbackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    enabled: Boolean = true,
): Color {
    val gradientColors =
        rememberArtworkGradient(
            thumbnailUrl = thumbnailUrl,
            fallbackColor = fallbackColor,
            enabled = enabled,
        )
    val surfaceColor = MaterialTheme.colorScheme.surface
    val useDarkTheme = remember(surfaceColor) { ColorUtils.calculateLuminance(surfaceColor.toArgb()) < 0.5 }
    val pureBlack = LocalItemPreferences.current.pureBlack

    return remember(gradientColors, useDarkTheme, pureBlack) {
        val baseColor = gradientColors.firstOrNull() ?: fallbackColor
        val baseArgb = baseColor.toArgb()
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(baseArgb, hsv)
        val hue = hsv[0]

        if (useDarkTheme) {
            // Brighter s/v for visibility in pure black mode.
            val s = (hsv[1] * 0.45f).coerceIn(0.06f, 0.20f)
            val v = if (pureBlack) 0.18f else 0.12f
            Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, s, v)))
        } else {
            val s = (hsv[1] * 0.30f).coerceIn(0.03f, 0.12f)
            val v = 0.95f
            Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, s, v)))
        }
    }
}
