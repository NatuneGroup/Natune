package moe.rukamori.archivetune.shared.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import moe.rukamori.archivetune.shared.domain.settings.DesktopArtworkSeedKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.ThemePaletteStyle

/**
 * The desktop's own base seed, taken from the app mark rather than from Material's stock purple. A desktop window always
 * has *some* seed before its first frame, so the first thing a user sees is the product's own tonal family instead of a
 * framework default that only turns into the artwork's colours once a sleeve has loaded.
 */
val DesktopDefaultThemeSeed = Color(0xFF13894A)

/**
 * The scheme a desktop window paints its first frame with: the sleeve it last tinted itself with when one was remembered,
 * otherwise [DesktopDefaultThemeSeed]. Every role comes from that one seed, so the page, the rail and the top bar are the
 * same tonal family and no surface is left on the default scheme.
 */
fun desktopFirstFrameScheme(
    storedSeed: Color?,
    isDark: Boolean,
    style: ThemePaletteStyle,
): ColorScheme = paletteColorScheme(storedSeed ?: DesktopDefaultThemeSeed, isDark, style)

/**
 * The seed of the last sleeve this desktop tinted itself with. Null before the window has ever shown a sleeve; a stored
 * zero means the same thing, because it is not a colour.
 */
fun PreferenceSnapshot.desktopArtworkSeed(): Color? =
    this[DesktopArtworkSeedKey]?.takeIf { it != 0L }?.let { Color(it.toInt()) }

/**
 * The desktop's artwork-seeded surface scheme. It reuses the same one-seed generator as a saved custom palette, so the
 * page, rail, top bar and containers are the Material tonal family of the current sleeve instead of the stock purple.
 * Text roles come from that family, so contrast is generated rather than hand-picked. A null seed keeps the caller's
 * default scheme.
 */
fun artworkSurfaceScheme(
    seed: Color?,
    isDark: Boolean,
    style: ThemePaletteStyle,
): ColorScheme? = seed?.let { paletteColorScheme(it, isDark, style) }

/**
 * The sleeve seed: the palette's dominant colour, skipping a near-white or near-black entry that would produce a flat
 * grey family. Null when the host has no artwork palette yet.
 */
fun artworkPaletteSeed(palette: List<Color>): Color? =
    palette.firstOrNull { it.alpha > 0.5f && it.luminance() in 0.08f..0.92f }
        ?: palette.firstOrNull { it.alpha > 0.5f }
