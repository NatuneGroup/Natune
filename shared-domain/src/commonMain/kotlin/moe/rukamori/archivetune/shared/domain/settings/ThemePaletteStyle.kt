package moe.rukamori.archivetune.shared.domain.settings

/**
 * How a saved palette color becomes a color scheme. The stored value is the enum name, and an unset or unknown name
 * reads as [AUTOMATIC]. AUTOMATIC keeps the rule the Android palette has always used, where the color's chroma picks
 * the style, so a saved color looks the same until the user chooses another style. The other entries name the Material
 * generation styles.
 */
enum class ThemePaletteStyle {
    AUTOMATIC,
    TONAL_SPOT,
    VIBRANT,
    EXPRESSIVE,
    RAINBOW,
    FRUIT_SALAD,
    MONOCHROME,
    NEUTRAL,
}

/** The palette style in effect. A removed or misspelled name falls back to [ThemePaletteStyle.AUTOMATIC]. */
fun PreferenceSnapshot.themePaletteStyle(): ThemePaletteStyle =
    this[ThemePaletteStyleKey].toEnum(ThemePaletteStyle.AUTOMATIC)
