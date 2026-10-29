package moe.rukamori.archivetune.shared.domain.settings

/**
 * The three ways the navigation bar can be treated. At most one is on: a frosted blur, a tinted frosted blur, or the
 * liquid glass bar.
 */
val navigationTreatmentKeys: Set<BooleanPreferenceKey> =
    setOf(NavigationBarFrostedBlurKey, NavigationBarTintFrostedBlurKey, LiquidGlassNavBarEnabledKey)

/** Legacy/restored collisions use the same precedence on every host, even before migration. */
fun PreferenceSnapshot.selectedNavigationTreatment(): BooleanPreferenceKey? = when {
    this[LiquidGlassNavBarEnabledKey] == true -> LiquidGlassNavBarEnabledKey
    this[NavigationBarTintFrostedBlurKey] == true -> NavigationBarTintFrostedBlurKey
    this[NavigationBarFrostedBlurKey] == true -> NavigationBarFrostedBlurKey
    else -> null
}

/**
 * Switches one treatment. Turning a treatment on clears the other two in the same edit, so the bar never reads two
 * treatments at once. Turning one off leaves the others as they are.
 */
fun PreferenceEditor.applyNavigationTreatment(
    key: BooleanPreferenceKey,
    enabled: Boolean,
) {
    require(key in navigationTreatmentKeys)
    this[key] = enabled
    if (enabled) {
        navigationTreatmentKeys.filterNot { it == key }.forEach { this[it] = false }
    }
}
