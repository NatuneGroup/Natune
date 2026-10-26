package moe.rukamori.archivetune.shared.domain.settings

/**
 * The process latch for restart-scoped settings. The stored snapshot is read once, on first use, and the values it
 * yields are what this process runs with. Later stored edits never move them. [pending] reports how the stored values
 * now differ, which is what the restart prompt is for.
 */
class StartupSettingsLatch(
    private val read: () -> PreferenceSnapshot?,
) {
    val values: RestartScopedSettings by lazy { RestartScopedSettings.from(read()) }

    val uiScale: Float get() = values.uiScale
    val fontPreference: AppFontPreference get() = values.fontPreference
    val customFontUri: String get() = values.customFontUri
    val tabletModeEnabled: Boolean get() = values.tabletModeEnabled
    val liquidGlassEnabled: Boolean get() = values.liquidGlassEnabled
    val liquidGlassNavBarEnabled: Boolean get() = values.liquidGlassNavBarEnabled
    val navigationBarFrostedBlur: Boolean get() = values.navigationBarFrostedBlur
    val navigationBarTintFrostedBlur: Boolean get() = values.navigationBarTintFrostedBlur

    fun pending(stored: PreferenceSnapshot?): PendingRestart =
        PendingRestart.between(values, RestartScopedSettings.from(stored))
}
