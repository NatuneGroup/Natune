/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori; © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Metrics match the shipped Android Type.kt, including its zero-sp display letter spacing. */
fun archiveTuneTypography(family: FontFamily): Typography {
    fun style(size: Int, height: Int, spacing: Float = 0f, medium: Boolean = false) = TextStyle(
        fontFamily = family, fontSize = size.sp, lineHeight = height.sp, letterSpacing = spacing.sp,
        fontWeight = if (medium) FontWeight.Medium else FontWeight.Normal,
    )
    return Typography(
        displayLarge = style(57, 64), displayMedium = style(45, 52), displaySmall = style(36, 44),
        headlineLarge = style(32, 40), headlineMedium = style(28, 36), headlineSmall = style(24, 32),
        titleLarge = style(22, 28), titleMedium = style(16, 24, .15f, true), titleSmall = style(14, 20, .1f, true),
        bodyLarge = style(16, 24, .5f), bodyMedium = style(14, 20, .25f), bodySmall = style(12, 16, .4f),
        labelLarge = style(14, 20, .1f, true), labelMedium = style(12, 16, .5f, true),
        labelSmall = style(11, 16, .5f, true),
    )
}
