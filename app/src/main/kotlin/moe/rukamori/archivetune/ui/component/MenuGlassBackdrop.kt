package moe.rukamori.archivetune.ui.component

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The root-content backdrop that the bottom-sheet menu samples. It is separate from
 * [LocalLiquidGlassBackdrop] because that one stops recording while the player is expanded or the
 * lyrics are full screen, which is exactly when menus are often opened. The host records it only
 * while a menu is visible, plus [MENU_GLASS_EXIT_LATCH_MS] so the exit animation still has content.
 */
val LocalMenuGlassBackdrop = compositionLocalOf<ThrottledLayerBackdrop?> { null }

const val MENU_GLASS_EXIT_LATCH_MS = 260L

/** The tint drawn over the blurred backdrop, before the menu's own content. */
internal fun menuGlassTint(scheme: ColorScheme, dark: Boolean, tintFactor: Float): Color =
    if (dark) {
        Color(0xFF1C1C1E).copy(alpha = (0.55f * tintFactor).coerceIn(0f, 1f))
    } else {
        scheme.surfaceContainerHigh.copy(alpha = (0.42f * tintFactor).coerceIn(0f, 1f))
    }

/**
 * Menu rows draw their section cards in surfaceContainer colours. Over glass those opaque cards
 * would hide the backdrop entirely, so they turn into faint washes and the ink follows the theme.
 */
internal fun menuGlassColorScheme(scheme: ColorScheme, dark: Boolean): ColorScheme {
    val ink = if (dark) Color.White else Color(0xFF1C1B1F)
    return scheme.copy(
        onSurface = ink,
        onBackground = ink,
        onSurfaceVariant = ink.copy(alpha = 0.72f),
        surfaceContainerLow = Color.Transparent,
        surfaceContainer = Color.Transparent,
        surfaceContainerHigh = (if (dark) Color.White else Color.Black).copy(alpha = if (dark) 0.08f else 0.05f),
        surfaceContainerHighest = (if (dark) Color.White else Color.Black).copy(alpha = if (dark) 0.14f else 0.08f),
        outlineVariant = ink.copy(alpha = 0.12f),
    )
}
