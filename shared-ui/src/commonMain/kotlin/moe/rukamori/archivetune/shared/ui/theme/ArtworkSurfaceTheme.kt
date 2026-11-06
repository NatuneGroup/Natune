package moe.rukamori.archivetune.shared.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import moe.rukamori.archivetune.shared.domain.settings.ThemePaletteStyle

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
