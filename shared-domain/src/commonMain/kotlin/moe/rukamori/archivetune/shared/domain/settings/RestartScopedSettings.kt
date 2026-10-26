package moe.rukamori.archivetune.shared.domain.settings

/**
 * Settings whose live application relayouts or re-records the whole app: the UI scale overrides the root density, a
 * font swaps the typography of every text node, tablet mode rebuilds the navigation scaffold, and the glass and frosted
 * surfaces record the navigation host into a backdrop every frame. They are applied once per process. Changing one
 * stores the value and asks for a restart.
 */
data class RestartScopedSettings(
    val uiScale: Float,
    val fontPreference: AppFontPreference,
    val customFontUri: String,
    val tabletModeEnabled: Boolean,
    val liquidGlassEnabled: Boolean,
    val liquidGlassNavBarEnabled: Boolean,
    val navigationBarFrostedBlur: Boolean,
    val navigationBarTintFrostedBlur: Boolean,
) {
    companion object {
        const val UI_SCALE_MIN = 0.85f
        const val UI_SCALE_MAX = 1.30f

        val Defaults =
            RestartScopedSettings(
                uiScale = 1.0f,
                fontPreference = AppFontPreference.DEFAULT,
                customFontUri = "",
                tabletModeEnabled = false,
                liquidGlassEnabled = false,
                liquidGlassNavBarEnabled = false,
                navigationBarFrostedBlur = false,
                navigationBarTintFrostedBlur = false,
            )

        /** Reads the restart-scoped values from a stored snapshot. A missing snapshot reads as [Defaults]. */
        fun from(snapshot: PreferenceSnapshot?): RestartScopedSettings {
            if (snapshot == null) return Defaults
            return RestartScopedSettings(
                uiScale = snapshot[UiScaleFactorKey, Defaults.uiScale].coerceIn(UI_SCALE_MIN, UI_SCALE_MAX),
                fontPreference = resolveFontPreference(snapshot[FontPreferenceKey], snapshot[UseSystemFontKey]),
                customFontUri = snapshot[CustomFontUriKey].orEmpty(),
                tabletModeEnabled = snapshot[TabletModeEnabledKey, Defaults.tabletModeEnabled],
                liquidGlassEnabled = snapshot[LiquidGlassEnabledKey, Defaults.liquidGlassEnabled],
                liquidGlassNavBarEnabled = snapshot[LiquidGlassNavBarEnabledKey, Defaults.liquidGlassNavBarEnabled],
                navigationBarFrostedBlur = snapshot[NavigationBarFrostedBlurKey, Defaults.navigationBarFrostedBlur],
                navigationBarTintFrostedBlur =
                    snapshot[NavigationBarTintFrostedBlurKey, Defaults.navigationBarTintFrostedBlur],
            )
        }

        /**
         * The legacy "use system font" switch is migrated to [FontPreferenceKey] after launch. Resolving it here keeps
         * that migration from reading as a pending restart.
         */
        fun resolveFontPreference(
            stored: String?,
            legacyUseSystemFont: Boolean?,
        ): AppFontPreference =
            when {
                stored != null -> stored.toEnum(AppFontPreference.DEFAULT)
                legacyUseSystemFont == true -> AppFontPreference.SYSTEM
                else -> AppFontPreference.DEFAULT
            }
    }
}

/** Which restart-scoped settings differ between what is running and what is stored. */
data class PendingRestart(
    val uiScale: Boolean,
    val font: Boolean,
    val tabletMode: Boolean,
    val liquidGlass: Boolean,
    val navigationBarGlass: Boolean,
) {
    val any: Boolean get() = uiScale || font || tabletMode || liquidGlass || navigationBarGlass

    companion object {
        fun between(
            running: RestartScopedSettings,
            stored: RestartScopedSettings,
        ): PendingRestart =
            PendingRestart(
                uiScale = running.uiScale != stored.uiScale,
                font =
                    running.fontPreference != stored.fontPreference ||
                        (stored.fontPreference == AppFontPreference.CUSTOM && running.customFontUri != stored.customFontUri),
                tabletMode = running.tabletModeEnabled != stored.tabletModeEnabled,
                liquidGlass = running.liquidGlassEnabled != stored.liquidGlassEnabled,
                navigationBarGlass =
                    running.liquidGlassNavBarEnabled != stored.liquidGlassNavBarEnabled ||
                        running.navigationBarFrostedBlur != stored.navigationBarFrostedBlur ||
                        running.navigationBarTintFrostedBlur != stored.navigationBarTintFrostedBlur,
            )
    }
}

/**
 * Chooses a font file. The URI and the selection are the font's identity. The display name is only a label, so
 * renaming a font does not ask for a restart.
 */
fun PreferenceEditor.setCustomFont(
    uri: String,
    name: String,
) {
    this[CustomFontUriKey] = uri
    this[CustomFontNameKey] = name
    this[FontPreferenceKey] = AppFontPreference.CUSTOM.name
}
