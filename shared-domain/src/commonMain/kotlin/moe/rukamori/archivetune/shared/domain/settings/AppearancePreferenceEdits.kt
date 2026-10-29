package moe.rukamori.archivetune.shared.domain.settings

/** Palette sources are exclusive; choosing a fixed palette must not be undone at the next launch. */
fun PreferenceEditor.setDynamicTheme(enabled: Boolean) {
    this[DynamicThemeKey] = enabled
    if (enabled) this[RandomThemeOnStartupKey] = false
}

fun PreferenceEditor.setRandomThemeOnStartup(enabled: Boolean) {
    this[RandomThemeOnStartupKey] = enabled
    if (enabled) this[DynamicThemeKey] = false
}

fun PreferenceEditor.setCustomThemeColor(value: String) {
    this[CustomThemeColorKey] = value
    this[DynamicThemeKey] = false
    this[RandomThemeOnStartupKey] = false
}

/** Deferred startup work cannot replace a palette the user chose while it was running. */
fun PreferenceEditor.applyStartupRandomTheme(expectedColor: String?, generatedColor: String) {
    if (!this[DynamicThemeKey, true] && this[RandomThemeOnStartupKey, false] &&
        this[CustomThemeColorKey] == expectedColor
    ) this[CustomThemeColorKey] = generatedColor
}

fun PreferenceEditor.setFontPreference(font: AppFontPreference) {
    this[FontPreferenceKey] = font.name
    this[UseSystemFontKey] = null
}

/** CUSTOM lyrics use the player's custom image and cannot be selected independently. */
fun PreferenceEditor.setPlayerBackgroundStyle(style: String) {
    require(style in setOf("DEFAULT", "GRADIENT", "CUSTOM", "BLUR", "COLORING", "BLUR_GRADIENT", "GLOW", "GLOW_ANIMATED"))
    this[PlayerBackgroundStyleKey] = style
    if (style == "CUSTOM") this[LyricsBackgroundStyleKey] = "CUSTOM"
    else if (this[LyricsBackgroundStyleKey] == "CUSTOM") this[LyricsBackgroundStyleKey] = "DEFAULT"
}

enum class MiniPlayerMaterialBlock { BLUR_DISABLED, UNSUPPORTED, GLASS_DISABLED }

fun miniPlayerMaterialBlock(
    style: MiniPlayerBackgroundStyle,
    blurDisabled: Boolean,
    blurSupported: Boolean,
    liquidGlassEnabled: Boolean,
): MiniPlayerMaterialBlock? {
    if (style != MiniPlayerBackgroundStyle.FROSTED && style != MiniPlayerBackgroundStyle.LIQUID_GLASS) return null
    return when {
        blurDisabled -> MiniPlayerMaterialBlock.BLUR_DISABLED
        !blurSupported -> MiniPlayerMaterialBlock.UNSUPPORTED
        style == MiniPlayerBackgroundStyle.LIQUID_GLASS && !liquidGlassEnabled -> MiniPlayerMaterialBlock.GLASS_DISABLED
        else -> null
    }
}
