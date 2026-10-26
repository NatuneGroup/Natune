/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.domain.settings

/**
 * The three ways the navigation bar can be treated. At most one is on: a frosted blur, a tinted frosted blur, or the
 * liquid glass bar.
 */
val navigationTreatmentKeys: Set<BooleanPreferenceKey> =
    setOf(NavigationBarFrostedBlurKey, NavigationBarTintFrostedBlurKey, LiquidGlassNavBarEnabledKey)

/**
 * Switches one treatment. Turning a treatment on clears the other two in the same edit, so the bar never reads two
 * treatments at once. Turning one off leaves the others as they are.
 */
fun PreferenceEditor.applyNavigationTreatment(
    key: BooleanPreferenceKey,
    enabled: Boolean,
) {
    this[key] = enabled
    if (enabled) {
        navigationTreatmentKeys.filterNot { it == key }.forEach { this[it] = false }
    }
}
