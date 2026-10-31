package moe.rukamori.archivetune.shared.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.ktx.toHct
import moe.rukamori.archivetune.shared.domain.settings.CustomThemeColorKey
import moe.rukamori.archivetune.shared.domain.settings.DynamicThemeKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.ThemePaletteStyle

/**
 * The color a palette was saved with, or null when there is none. Dynamic theme is the default and the palette sources
 * are exclusive, so a saved color applies only while dynamic theme is off. Anything other than #RRGGBB or #AARRGGBB is
 * not a color, and it keeps the default scheme instead of a guessed one.
 */
fun PreferenceSnapshot.savedPaletteSeed(): Color? {
    if (this[DynamicThemeKey, true]) return null
    val value = this[CustomThemeColorKey] ?: return null
    if (!value.startsWith("#")) return null
    val digits = value.substring(1)
    if (digits.length != 6 && digits.length != 8) return null
    if (!digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    val argb = digits.toLong(16)
    return Color(if (digits.length == 6) 0xFF000000L or argb else argb)
}

/**
 * The scheme a saved color generates. Every role comes from that one seed. With [ThemePaletteStyle.AUTOMATIC] it is the
 * scheme the Android palette builds from the same color. The other styles change only the generation style.
 */
fun paletteColorScheme(seed: Color, isDark: Boolean, style: ThemePaletteStyle): ColorScheme =
    dynamicColorScheme(
        seedColor = seed,
        isDark = isDark,
        contrastLevel = 0.0,
        style = style.materialKolorStyle(seed),
    )

private fun ThemePaletteStyle.materialKolorStyle(seed: Color): PaletteStyle = when (this) {
    ThemePaletteStyle.AUTOMATIC -> automaticPaletteStyle(seed)
    ThemePaletteStyle.TONAL_SPOT -> PaletteStyle.TonalSpot
    ThemePaletteStyle.VIBRANT -> PaletteStyle.Vibrant
    ThemePaletteStyle.EXPRESSIVE -> PaletteStyle.Expressive
    ThemePaletteStyle.RAINBOW -> PaletteStyle.Rainbow
    ThemePaletteStyle.FRUIT_SALAD -> PaletteStyle.FruitSalad
    ThemePaletteStyle.MONOCHROME -> PaletteStyle.Monochrome
    ThemePaletteStyle.NEUTRAL -> PaletteStyle.Neutral
}

/** The Android rule: a nearly grey color gets Monochrome, a muted one gets Neutral, and any other gets TonalSpot. */
private fun automaticPaletteStyle(seed: Color): PaletteStyle {
    val chroma = seed.toHct().chroma
    return when {
        chroma < 4.0 -> PaletteStyle.Monochrome
        chroma < 12.0 -> PaletteStyle.Neutral
        else -> PaletteStyle.TonalSpot
    }
}
