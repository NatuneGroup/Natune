package moe.rukamori.archivetune.ui.screens.artist

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalAnimationsDisabled
import moe.rukamori.archivetune.playback.artwork.PlayerPaletteCacheKey
import moe.rukamori.archivetune.playback.artwork.guessArtworkProvider
import moe.rukamori.archivetune.ui.player.LocalPlayerSheetOverlayActive
import moe.rukamori.archivetune.ui.theme.BackdropTonePalette
import moe.rukamori.archivetune.ui.theme.PlayerPaletteCache

/** Animated gradient stops, held as State so the page only redraws while they crossfade. */
@Stable
class ArtistAmbientColors internal constructor(
    val top: State<Color>,
    val mid: State<Color>,
    val bottom: State<Color>,
)

/**
 * The artist's artwork tones for an artist sub-page (songs, albums, items). Until the artwork is
 * analysed, or when there is none, every stop is the theme surface, so the page looks as before.
 */
@Composable
fun rememberArtistAmbientColors(
    artistId: String,
    artworkUrl: String?,
): ArtistAmbientColors {
    val context = LocalContext.current
    val surface = MaterialTheme.colorScheme.surface
    val darkTheme = surface.luminance() < 0.5f
    val cacheKey = remember(artistId, artworkUrl, darkTheme) { artistAmbientCacheKey(artistId, artworkUrl, darkTheme) }
    var extracted by remember(cacheKey) { mutableStateOf(cacheKey?.let(PlayerPaletteCache::get)) }
    // Decoding waits while the player sheet covers the page; nothing here is visible then.
    val covered = LocalPlayerSheetOverlayActive.current
    LaunchedEffect(cacheKey, covered) {
        if (cacheKey == null || covered || extracted != null) return@LaunchedEffect
        extracted = extractArtistAmbientColors(context, cacheKey)
    }
    val palette =
        remember(extracted, surface, darkTheme) {
            val colors = extracted
            if (colors.isNullOrEmpty()) {
                BackdropTonePalette(surface, surface, surface)
            } else {
                BackdropTonePalette.fromColorsLight(colors, fallbackColor = surface, darkTheme = darkTheme)
            }
        }
    val spec: AnimationSpec<Color> = if (LocalAnimationsDisabled.current) snap() else tween(ARTIST_AMBIENT_CROSSFADE_MS)
    val top = animateColorAsState(palette.top, spec, label = "artistAmbientTop")
    val mid = animateColorAsState(palette.mid, spec, label = "artistAmbientMid")
    val bottom = animateColorAsState(palette.bottom, spec, label = "artistAmbientBottom")
    return remember(top, mid, bottom) { ArtistAmbientColors(top, mid, bottom) }
}

fun Modifier.artistAmbientBackground(colors: ArtistAmbientColors): Modifier =
    drawBehind {
        drawRect(Brush.verticalGradient(0f to colors.top.value, 0.5f to colors.mid.value, 1f to colors.bottom.value))
    }

/** The top-bar fill: the gradient's top stop, so the bar meets the page without a seam. */
fun Modifier.artistAmbientBarBackground(colors: ArtistAmbientColors): Modifier =
    drawBehind { drawRect(colors.top.value) }

private fun artistAmbientCacheKey(
    artistId: String,
    artworkUrl: String?,
    darkTheme: Boolean,
): PlayerPaletteCacheKey? {
    if (artworkUrl.isNullOrBlank()) return null
    return PlayerPaletteCacheKey(
        mediaId = "artist:$artistId",
        provider = guessArtworkProvider(artworkUrl),
        artworkIdentity = artworkUrl,
        backgroundMode = ARTIST_AMBIENT_MODE,
        darkTheme = darkTheme,
    )
}

/**
 * Up to two colours from the lower band of the artwork (where a portrait's background usually
 * is), cached only on success so a network failure is retried on the next visit.
 */
private suspend fun extractArtistAmbientColors(
    context: Context,
    key: PlayerPaletteCacheKey,
): List<Color>? {
    PlayerPaletteCache.get(key)?.let { return it }
    val url = key.artworkIdentity
    val request =
        ImageRequest
            .Builder(context)
            .data(url)
            .memoryCacheKey(url)
            .diskCacheKey(url)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .size(ARTIST_AMBIENT_EXTRACT_SIZE_PX, ARTIST_AMBIENT_EXTRACT_SIZE_PX)
            .allowHardware(false)
            .build()
    val result =
        try {
            withContext(Dispatchers.IO) { context.imageLoader.execute(request) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }
    val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return null
    val palette =
        withContext(Dispatchers.Default) {
            val bandTop = (bitmap.height * ARTIST_AMBIENT_BAND_START).toInt().coerceIn(0, (bitmap.height - 2).coerceAtLeast(1))
            runCatching {
                Palette.from(bitmap).setRegion(0, bandTop, bitmap.width, bitmap.height)
                    .maximumColorCount(24).resizeBitmapArea(2000).generate()
            }.getOrElse { Palette.from(bitmap).maximumColorCount(24).resizeBitmapArea(2000).generate() }
        }
    val dominant = palette.dominantSwatch
    val muted = palette.mutedSwatch?.takeIf { it.rgb != dominant?.rgb }
    val stops = listOfNotNull(dominant, muted).map { Color(it.rgb) }
    if (stops.isEmpty()) return null
    PlayerPaletteCache.put(key, stops)
    return stops
}

private const val ARTIST_AMBIENT_MODE = "ARTIST_AMBIENT"
private const val ARTIST_AMBIENT_CROSSFADE_MS = 1200
private const val ARTIST_AMBIENT_EXTRACT_SIZE_PX = 64
private const val ARTIST_AMBIENT_BAND_START = 0.62f
