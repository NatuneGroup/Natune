/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared.ui.glass

import androidx.compose.runtime.staticCompositionLocalOf
import moe.rukamori.archivetune.shared.domain.settings.*

data class OpticalCapabilities(val blur: Boolean, val refraction: Boolean)
data class OpticalPolicy(
    val recordLiquidGlass: Boolean,
    val recordNavigation: Boolean,
    val artworkBlur: Boolean,
    val motion: Boolean,
    /**
     * The Liquid Glass look is wanted and drawable, ignoring occlusion. Scrims that sample no backdrop follow this rather
     * than [recordLiquidGlass], so they do not appear and vanish with the player sheet.
     */
    val liquidGlassLook: Boolean = false,
    /**
     * A frosted mini player samples the page without a navigation surface, so the navigation recording stays on for it
     * even while no frosted bar, rail or compact row is composed (see [ProvideBackdropSources]).
     */
    val frostedMiniPlayer: Boolean = false,
)

val LocalOpticalPolicy = staticCompositionLocalOf { OpticalPolicy(false, false, false, false) }

fun opticalPolicy(
    preferences: PreferenceSnapshot,
    capabilities: OpticalCapabilities,
    /**
     * The host lifecycle: false while the activity or window is paused, stopped or minimized. Together with [visible] it
     * turns every decorative frame loop off, because [OpticalPolicy.motion] is false without it.
     */
    resumed: Boolean = true,
    /** False while the surface is occluded or off screen (desktop: minimized or covered), which also stops frame loops. */
    visible: Boolean = true,
    playerExpanded: Boolean = false,
    lyricsExpanded: Boolean = false,
    frostedMiniPlayer: Boolean = false,
    /** The platform's reduced-motion setting (animator duration scale of zero), supplied by the host. */
    reducedMotion: Boolean = false,
): OpticalPolicy {
    val active = resumed && visible
    val blur = !preferences[DisableBlurKey, false] && capabilities.blur
    val unoccluded = active && !playerExpanded && !lyricsExpanded
    val look = blur && capabilities.refraction && preferences[LiquidGlassEnabledKey, false]
    return OpticalPolicy(
        recordLiquidGlass = unoccluded && look,
        recordNavigation = unoccluded && blur && (preferences[NavigationBarFrostedBlurKey, false] ||
            preferences[NavigationBarTintFrostedBlurKey, false] || frostedMiniPlayer),
        artworkBlur = active && blur,
        motion = active && !preferences[DisableAnimationsKey, false] && !reducedMotion,
        liquidGlassLook = look,
        frostedMiniPlayer = frostedMiniPlayer,
    )
}

internal fun liquidGlassAllowed(masterEnabled: Boolean, platformBlurAllowed: Boolean, sourceAvailable: Boolean, visible: Boolean) =
    masterEnabled && platformBlurAllowed && sourceAvailable && visible

internal fun liquidGlassRefractionHeight(requestedPx: Float, minDimensionPx: Float): Float =
    minOf(requestedPx.coerceAtLeast(0f), minDimensionPx.coerceAtLeast(0f) / 2f)
