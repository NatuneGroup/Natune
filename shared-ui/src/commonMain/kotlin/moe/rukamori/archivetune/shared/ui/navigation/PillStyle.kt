package moe.rukamori.archivetune.shared.ui.navigation

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import moe.rukamori.archivetune.shared.domain.settings.MiniPlayerBackgroundStyle
import moe.rukamori.archivetune.shared.domain.settings.NavigationBarCompactBehavior
import moe.rukamori.archivetune.shared.domain.settings.NavigationBarStyle
import moe.rukamori.archivetune.shared.domain.settings.AppleMusicExperienceKey
import moe.rukamori.archivetune.shared.domain.settings.CompactBottomPillsKey
import moe.rukamori.archivetune.shared.domain.settings.CompactControlsStockColorKey
import moe.rukamori.archivetune.shared.domain.settings.HideNavigationBarLabelsKey
import moe.rukamori.archivetune.shared.domain.settings.LiquidGlassNavBarEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.MiniPlayerBackgroundStyleKey
import moe.rukamori.archivetune.shared.domain.settings.NavigationBarCompactBehaviorKey
import moe.rukamori.archivetune.shared.domain.settings.NavigationBarCornerRadiusKey
import moe.rukamori.archivetune.shared.domain.settings.NavigationBarFrostedBlurKey
import moe.rukamori.archivetune.shared.domain.settings.NavigationBarGlassGlowIntensityKey
import moe.rukamori.archivetune.shared.domain.settings.NavigationBarGlassGlowKey
import moe.rukamori.archivetune.shared.domain.settings.NavigationBarHeightKey
import moe.rukamori.archivetune.shared.domain.settings.NavigationBarHideOnScrollKey
import moe.rukamori.archivetune.shared.domain.settings.NavigationBarLabelSpacingKey
import moe.rukamori.archivetune.shared.domain.settings.NavigationBarOpacityKey
import moe.rukamori.archivetune.shared.domain.settings.NavigationBarStyleKey
import moe.rukamori.archivetune.shared.domain.settings.NavigationBarTintFrostedBlurKey
import moe.rukamori.archivetune.shared.domain.settings.NavigationBarTransparencyKey
import moe.rukamori.archivetune.shared.domain.settings.NavigationBarWidthKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.ui.glass.LocalLiquidGlassBackdrop
import moe.rukamori.archivetune.shared.ui.glass.LocalNavigationHaze
import moe.rukamori.archivetune.shared.ui.glass.LocalOpticalPolicy
import moe.rukamori.archivetune.shared.ui.glass.NAVIGATION_BAR_GLASS_GLOW_INTENSITY_DEFAULT

// Frosted backdrop blur radius, in px (RenderEffect works in raw pixels). Shared by the navigation
// bar and the mini player's frosted background so the two read as one material.
const val PillFrostBlurRadiusPx = 60f

// How much of the blurred backdrop shows through the opaque base. The base is always fully opaque
// and the blurred content is composited on top at this alpha, so page brightness can only ever
// modulate the surface by this fraction — and an empty backdrop layer simply yields a solid pill.
const val PillFrostOverlayAlpha = 0.30f

// The tinted variant sits on a dark translucent base, so a higher overlay alpha lets the blurred
// app content read through it as tinted glass.
const val PillTintFrostOverlayAlpha = 0.45f

private const val ApplePillContainerAlpha = 0.92f
private const val FrostedTintContainerAlpha = 0.85f
private val PillBorderWidth = 1.dp

/**
 * True while the shell has slid the bottom navigation bar away on scroll. The content insets stay
 * static so lists do not jump, which leaves bottom-anchored chrome hovering over the gap unless it
 * follows the bar down.
 */
val LocalNavigationBarHiddenByScroll = compositionLocalOf { false }

/** The animated compact fraction the bottom UI follows (0 = expanded, 1 = one-row compact). */
val LocalBottomUiCompactFraction = compositionLocalOf { 0f }

enum class PillRole {
    NAVIGATION_BAR,
    MINI_PLAYER,
    STANDALONE,
}

enum class PillBackdrop {
    NONE,
    FROSTED,
    LIQUID_GLASS,
}

val NavigationBarStyle.floats: Boolean
    get() = this == NavigationBarStyle.FLOATING || this == NavigationBarStyle.APPLE_MUSIC ||
        this == NavigationBarStyle.SUKISU

val NavigationBarStyle.pillHorizontalInset: Dp
    get() = if (floats) FloatingNavigationBarHorizontalPadding else NavigationBarHorizontalPadding

val NavigationBarStyle.pillBottomInset: Dp
    get() = if (floats) FloatingNavigationBarBottomPadding else NavigationBarBottomPadding

val NavigationBarStyle.pillMaxWidth: Dp
    get() = if (this == NavigationBarStyle.DEFAULT) NavigationBarMaxWidth else FloatingNavigationBarMaxWidth

/**
 * The geometry and material of the bottom chrome, resolved once from the navigation bar style the
 * user picked in Appearance. The navigation bar, the mini player and the bottom search pill all read
 * it, so they share a corner radius, insets, elevation, hairline and surface tone instead of each
 * hard-coding its own. Ported from the baseline PillStyle.
 *
 * [containerColor] is the navigation bar's own surface (frosted, tinted, pure-black, translucent);
 * [raisedContainerColor] is the tone of the surfaces stacked above it. The search pill sits inside
 * the content that the frosted and liquid-glass backdrops record, so it cannot sample them without
 * rendering recursively — it takes the raised tone and the shared shape and elevation only.
 */
class PillStyle(
    val navigationStyle: NavigationBarStyle,
    val backdrop: PillBackdrop,
    val tinted: Boolean,
    val cornerRadius: Dp,
    val widthFraction: Float,
    val containerColor: Color,
    val raisedContainerColor: Color,
    val contentColor: Color,
    val tintedContent: Color,
    val compact: Boolean,
    borderColor: Color?,
) {
    val horizontalInset: Dp get() = navigationStyle.pillHorizontalInset
    val bottomInset: Dp get() = navigationStyle.pillBottomInset
    val maxWidth: Dp get() = navigationStyle.pillMaxWidth * (if (compact) 0.9f else 1f)
    val isDocked: Boolean get() = navigationStyle == NavigationBarStyle.DEFAULT
    val tonalElevation: Dp get() = NavigationBarDefaults.Elevation
    val shadowElevation: Dp
        get() = if (navigationStyle.floats) 8.dp else NavigationBarDefaults.Elevation
    val border: BorderStroke? = borderColor?.let { BorderStroke(PillBorderWidth, it) }

    /**
     * Docked pills pinch toward the junction with their neighbour as [proximity] rises; floating and
     * Apple Music pills never dock, so they keep one radius on every corner.
     */
    fun shape(
        role: PillRole,
        proximity: Float = 0f,
    ): Shape {
        if (backdrop == PillBackdrop.LIQUID_GLASS && role != PillRole.MINI_PLAYER) {
            return RoundedCornerShape(percent = 50)
        }
        if (navigationStyle == NavigationBarStyle.APPLE_MUSIC) return RoundedCornerShape(percent = 50)
        if (!isDocked) return RoundedCornerShape(cornerRadius)
        val docking = proximity.coerceIn(0f, 1f)
        val standalone = if (compact) 24.dp else FloatingBarStandaloneCornerRadius
        val outerRadius = if (compact) 20.dp else FloatingBarOuterCornerRadius
        val junctionRadius = if (compact) 10.dp else FloatingBarJunctionCornerRadius
        return when (role) {
            PillRole.NAVIGATION_BAR -> {
                val top = lerp(standalone, junctionRadius, docking)
                val bottom = lerp(standalone, outerRadius, docking)
                RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
            }

            PillRole.MINI_PLAYER -> {
                val top = lerp(standalone, outerRadius, docking)
                val bottom = lerp(standalone, junctionRadius, docking)
                RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
            }

            PillRole.STANDALONE -> RoundedCornerShape(standalone)
        }
    }
}

// The baseline dimensions the bottom chrome shares.
val NavigationBarHorizontalPadding = 12.dp
val NavigationBarBottomPadding = 10.dp
val NavigationBarMaxWidth = 420.dp
val FloatingBarStandaloneCornerRadius = 32.dp
val FloatingBarOuterCornerRadius = 28.dp
val FloatingBarJunctionCornerRadius = 12.dp
val NavigationBarHeight = 78.dp
val FloatingNavigationBarHorizontalPadding = 24.dp
val FloatingNavigationBarBottomPadding = 16.dp
val FloatingNavigationBarMaxWidth = 360.dp
val MiniPlayerHeight = 70.dp
val MiniPlayerCompactHeight = 64.dp
val MiniPlayerBottomSpacing = 4.dp
val NavigationBarHideScrollThreshold = 14.dp
val CompactControlSize = 56.dp
val CompactControlGap = 12.dp
val NavigationItemsMaxWidth = 360.dp
val NavigationItemVerticalPadding = 8.dp

const val NAVIGATION_BAR_WIDTH_DEFAULT = 0.8f
const val NAVIGATION_BAR_HEIGHT_DEFAULT = 1.0f
const val NAVIGATION_BAR_OPACITY_DEFAULT = 1.0f
const val NAVIGATION_BAR_TRANSPARENCY_DEFAULT = 0.0f
const val NAVIGATION_BAR_LABEL_SPACING_DEFAULT = 4f
const val NAVIGATION_BAR_CORNER_RADIUS_DEFAULT = 28f

val NavigationBarAnimationSpec =
    spring<Dp>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessLow,
    )

/**
 * The stored compact behaviour, or — while the user has never picked one — Adaptive when "Hide
 * while scrolling" is on and Always expanded when it is off, so scroll-driven chrome stays opt-in.
 * The Apple Music bar is the one-row layout (Library circle, mini player, Search circle), as
 * in 4nx3b; a separate tab capsule above a separate mini player is not that style.
 */
fun PreferenceSnapshot.navigationBarCompactBehavior(): NavigationBarCompactBehavior =
    if (navigationPillStyle() == NavigationBarStyle.APPLE_MUSIC) {
        NavigationBarCompactBehavior.ALWAYS_COMPACT
    } else {
        this[NavigationBarCompactBehaviorKey]
            ?.let { runCatching { NavigationBarCompactBehavior.valueOf(it) }.getOrNull() }
            ?: if (this[NavigationBarHideOnScrollKey, false]) {
                NavigationBarCompactBehavior.ADAPTIVE
            } else {
                NavigationBarCompactBehavior.ALWAYS_EXPANDED
            }
    }

/**
 * When the Apple Music experience switch is on, the stored style is overridden to APPLE_MUSIC — the
 * same atomic edit the baseline MainActivity applies; SUKISU stays selectable on its own.
 */
fun PreferenceSnapshot.navigationPillStyle(): NavigationBarStyle =
    if (this[AppleMusicExperienceKey, false]) {
        NavigationBarStyle.APPLE_MUSIC
    } else {
        this[NavigationBarStyleKey]?.let { runCatching { NavigationBarStyle.valueOf(it) }.getOrNull() }
            ?: NavigationBarStyle.DEFAULT
    }

/** The glow strength for the bar, gated by the stock-colours opt-out the same way the baseline does. */
fun PreferenceSnapshot.navigationBarGlowStrength(): Float =
    if (this[NavigationBarGlassGlowKey, false] && !this[CompactControlsStockColorKey, false]) {
        this[NavigationBarGlassGlowIntensityKey, NAVIGATION_BAR_GLASS_GLOW_INTENSITY_DEFAULT]
    } else {
        0f
    }

fun PreferenceSnapshot.frostedNavigationEnabled(): Boolean =
    this[NavigationBarFrostedBlurKey, false]

fun PreferenceSnapshot.tintedFrostedNavigation(): Boolean =
    this[NavigationBarTintFrostedBlurKey, false]

fun PreferenceSnapshot.hideNavigationLabels(
    style: NavigationBarStyle = navigationPillStyle(),
): Boolean =
    this[HideNavigationBarLabelsKey, false] &&
        style != NavigationBarStyle.YUMA_PLAYER &&
        style != NavigationBarStyle.APPLE_MUSIC

fun PreferenceSnapshot.miniPlayerBackgroundStyle(): MiniPlayerBackgroundStyle =
    this[MiniPlayerBackgroundStyleKey]?.let { runCatching { MiniPlayerBackgroundStyle.valueOf(it) }.getOrNull() }
        ?: MiniPlayerBackgroundStyle.THEME

/**
 * The pill the current preferences describe, resolved against what the host can actually draw. Liquid
 * glass needs the recorded backdrop the optical policy allows; frosted needs the navigation haze
 * source the shell provides. When neither is available the pill falls back to a solid container,
 * exactly like the baseline's capabilities gate.
 */
@Composable
fun rememberPillStyle(preferences: PreferenceSnapshot): PillStyle {
    return rememberPillStyle(
        style = preferences.navigationPillStyle(),
        pureBlack = MaterialTheme.colorScheme.background == Color.Black,
        frostedBlur = preferences.frostedNavigationEnabled(),
        tintFrostedBlur = preferences.tintedFrostedNavigation(),
        liquidGlass = LocalOpticalPolicy.current.liquidGlassLook &&
            preferences[LiquidGlassNavBarEnabledKey, false] &&
            !preferences[CompactControlsStockColorKey, false],
        frostedBackdropAvailable = LocalNavigationHaze.current != null,
        liquidGlassBackdropAvailable = LocalLiquidGlassBackdrop.current != null,
        preferences = preferences,
    )
}

@Composable
fun rememberPillStyle(
    style: NavigationBarStyle,
    pureBlack: Boolean,
    frostedBlur: Boolean,
    tintFrostedBlur: Boolean,
    liquidGlass: Boolean,
    frostedBackdropAvailable: Boolean,
    liquidGlassBackdropAvailable: Boolean,
    preferences: PreferenceSnapshot,
): PillStyle {
    val colors = MaterialTheme.colorScheme
    val compactBottomPills = preferences[CompactBottomPillsKey, false]
    val useCompactPills =
        compactBottomPills && style != NavigationBarStyle.YUMA_PLAYER && style != NavigationBarStyle.APPLE_MUSIC
    val widthPreference = preferences[NavigationBarWidthKey, NAVIGATION_BAR_WIDTH_DEFAULT]
    val opacity = preferences[NavigationBarOpacityKey, NAVIGATION_BAR_OPACITY_DEFAULT]
    val transparency = preferences[NavigationBarTransparencyKey, NAVIGATION_BAR_TRANSPARENCY_DEFAULT]
    val cornerRadiusPreference = preferences[NavigationBarCornerRadiusKey, NAVIGATION_BAR_CORNER_RADIUS_DEFAULT]
    val surfaceContainer = colors.surfaceContainer
    val surfaceContainerHigh = colors.surfaceContainerHigh
    val outlineVariant = colors.outlineVariant
    val onSurface = colors.onSurface
    val primary = colors.primary
    val schemeIsDark = pureBlack || colors.background.luminance() < 0.5f

    val isYumaPlayer = style == NavigationBarStyle.YUMA_PLAYER
    // Both Apple-flavoured bars sit on a neutral translucent surface with a hairline edge.
    val isNeutralCapsule = isYumaPlayer || style == NavigationBarStyle.APPLE_MUSIC
    // On the baseline the platform check gates backdrops; here the haze/backdrop locals exist only when
    // the host supplied a drawable surface, which folds that check into availability.
    val canLiquidGlass = liquidGlass && liquidGlassBackdropAvailable
    val canBlurBackdrop = (frostedBlur || tintFrostedBlur) && frostedBackdropAvailable
    val tintedBase = if (schemeIsDark) lerp(Color.Black, primary, 0.30f) else lerp(Color.White, primary, 0.26f)
    val tintedContent = if (schemeIsDark) Color.White else lerp(primary, Color.Black, 0.55f)
    val containerColor =
        when {
            // Transparent so the liquid-glass shader tint shows through.
            canLiquidGlass -> Color.Transparent
            canBlurBackdrop ->
                when {
                    tintFrostedBlur -> tintedBase.copy(alpha = FrostedTintContainerAlpha)
                    // Pure black still needs some surface under the blur, or the frost is invisible.
                    pureBlack -> Color.Black.copy(alpha = 0.45f)
                    else -> surfaceContainer
                }
            tintFrostedBlur -> tintedBase
            pureBlack -> Color.Black
            isNeutralCapsule -> surfaceContainer.copy(alpha = ApplePillContainerAlpha)
            // Transparency applies only without a frost overlay, which already provides the
            // see-through effect; adding it there would double-count.
            else -> surfaceContainer.copy(alpha = (opacity * (1f - transparency)).coerceIn(0.05f, 1f))
        }

    return PillStyle(
        navigationStyle = style,
        backdrop =
            when {
                canLiquidGlass -> PillBackdrop.LIQUID_GLASS
                canBlurBackdrop -> PillBackdrop.FROSTED
                else -> PillBackdrop.NONE
            },
        tinted = tintFrostedBlur,
        cornerRadius =
            if (useCompactPills) {
                20.dp
            } else if (style == NavigationBarStyle.DEFAULT) {
                FloatingBarStandaloneCornerRadius
            } else {
                cornerRadiusPreference.dp
            },
        widthFraction =
            when (style) {
                NavigationBarStyle.YUMA_PLAYER ->
                    if (widthPreference == NAVIGATION_BAR_WIDTH_DEFAULT) 0.94f else widthPreference.coerceIn(0.6f, 1f)
                // 4nx3b's bar geometry: the user's width (80% by default), never a fixed narrow
                // pill that three labelled tabs cannot fit in.
                NavigationBarStyle.APPLE_MUSIC -> widthPreference.coerceIn(0.5f, 1f)
                NavigationBarStyle.FLOATING -> widthPreference.coerceIn(0.5f, 1f) * (if (useCompactPills) 0.9f else 1f)
                NavigationBarStyle.SUKISU -> widthPreference.coerceIn(0.5f, 1f) * (if (useCompactPills) 0.9f else 1f)
                NavigationBarStyle.DEFAULT -> if (useCompactPills) 0.88f else 1f
            },
        containerColor = containerColor,
        raisedContainerColor =
            if (isNeutralCapsule) surfaceContainerHigh.copy(alpha = ApplePillContainerAlpha) else surfaceContainerHigh,
        contentColor =
            when {
                tintFrostedBlur -> tintedContent
                pureBlack -> Color.White
                else -> onSurface
            },
        tintedContent = tintedContent,
        compact = useCompactPills,
        borderColor = if (isNeutralCapsule) outlineVariant else null,
    )
}
