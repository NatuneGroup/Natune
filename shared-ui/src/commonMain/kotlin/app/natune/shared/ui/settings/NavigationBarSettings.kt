package app.natune.shared.ui.settings

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import app.natune.shared.domain.settings.CompactBottomPillsKey
import app.natune.shared.domain.settings.CompactControlsStockColorKey
import app.natune.shared.domain.settings.DisableBlurKey
import app.natune.shared.domain.settings.HideNavigationBarLabelsKey
import app.natune.shared.domain.settings.LiquidGlassEnabledKey
import app.natune.shared.domain.settings.LiquidGlassNavBarEnabledKey
import app.natune.shared.domain.settings.NavigationBarCompactBehavior
import app.natune.shared.domain.settings.NavigationBarCompactBehaviorKey
import app.natune.shared.domain.settings.NavigationBarCornerRadiusKey
import app.natune.shared.domain.settings.NavigationBarFrostedBlurKey
import app.natune.shared.domain.settings.NavigationBarGlassGlowIntensityKey
import app.natune.shared.domain.settings.NavigationBarGlassGlowKey
import app.natune.shared.domain.settings.NavigationBarHeightKey
import app.natune.shared.domain.settings.NavigationBarHideOnScrollKey
import app.natune.shared.domain.settings.NavigationBarLabelSpacingKey
import app.natune.shared.domain.settings.NavigationBarOpacityKey
import app.natune.shared.domain.settings.NavigationBarStyle
import app.natune.shared.domain.settings.NavigationBarStyleKey
import app.natune.shared.domain.settings.NavigationBarTintFrostedBlurKey
import app.natune.shared.domain.settings.NavigationBarTransparencyKey
import app.natune.shared.domain.settings.NavigationBarWidthKey
import app.natune.shared.domain.settings.PreferenceSnapshot
import app.natune.shared.domain.settings.applyNavigationTreatment
import app.natune.shared.domain.settings.selectedNavigationBarStyle
import app.natune.shared.ui.glass.NAVIGATION_BAR_GLASS_GLOW_INTENSITY_DEFAULT
import app.natune.shared.ui.navigation.NAVIGATION_BAR_CORNER_RADIUS_DEFAULT
import app.natune.shared.ui.navigation.NAVIGATION_BAR_HEIGHT_DEFAULT
import app.natune.shared.ui.navigation.NAVIGATION_BAR_LABEL_SPACING_DEFAULT
import app.natune.shared.ui.navigation.NAVIGATION_BAR_OPACITY_DEFAULT
import app.natune.shared.ui.navigation.NAVIGATION_BAR_TRANSPARENCY_DEFAULT
import app.natune.shared.ui.navigation.NAVIGATION_BAR_WIDTH_DEFAULT
import app.natune.shared.ui.navigation.navigationBarCompactBehavior
import app.natune.shared.ui.resources.Res
import app.natune.shared.ui.resources.ios_lane_nav_bar_style
import app.natune.shared.ui.resources.ios_lane_nav_compact
import app.natune.shared.ui.resources.ios_lane_nav_compact_adaptive
import app.natune.shared.ui.resources.ios_lane_nav_compact_always_compact
import app.natune.shared.ui.resources.ios_lane_nav_compact_always_expanded
import app.natune.shared.ui.resources.ios_lane_nav_compact_behavior
import app.natune.shared.ui.resources.ios_lane_nav_compact_behavior_desc
import app.natune.shared.ui.resources.ios_lane_nav_corner_radius
import app.natune.shared.ui.resources.ios_lane_nav_corner_radius_desc
import app.natune.shared.ui.resources.ios_lane_nav_dimensions
import app.natune.shared.ui.resources.ios_lane_nav_frosted
import app.natune.shared.ui.resources.ios_lane_nav_frosted_desc
import app.natune.shared.ui.resources.ios_lane_nav_glow
import app.natune.shared.ui.resources.ios_lane_nav_glow_desc
import app.natune.shared.ui.resources.ios_lane_nav_glow_intensity
import app.natune.shared.ui.resources.ios_lane_nav_glow_intensity_desc
import app.natune.shared.ui.resources.ios_lane_nav_height
import app.natune.shared.ui.resources.ios_lane_nav_height_desc
import app.natune.shared.ui.resources.ios_lane_nav_hide_labels
import app.natune.shared.ui.resources.ios_lane_nav_hide_labels_desc
import app.natune.shared.ui.resources.ios_lane_nav_hide_on_scroll
import app.natune.shared.ui.resources.ios_lane_nav_hide_on_scroll_desc
import app.natune.shared.ui.resources.ios_lane_nav_label_spacing
import app.natune.shared.ui.resources.ios_lane_nav_label_spacing_desc
import app.natune.shared.ui.resources.ios_lane_nav_layout
import app.natune.shared.ui.resources.ios_lane_nav_layout_desc
import app.natune.shared.ui.resources.ios_lane_nav_liquid
import app.natune.shared.ui.resources.ios_lane_nav_liquid_desc
import app.natune.shared.ui.resources.ios_lane_nav_liquid_disabled
import app.natune.shared.ui.resources.ios_lane_nav_opacity
import app.natune.shared.ui.resources.ios_lane_nav_opacity_desc
import app.natune.shared.ui.resources.ios_lane_nav_reset_dimensions
import app.natune.shared.ui.resources.ios_lane_nav_stock_color
import app.natune.shared.ui.resources.ios_lane_nav_stock_color_desc
import app.natune.shared.ui.resources.ios_lane_nav_style_apple_music
import app.natune.shared.ui.resources.ios_lane_nav_style_default
import app.natune.shared.ui.resources.ios_lane_nav_style_floating
import app.natune.shared.ui.resources.ios_lane_nav_style_sukisu
import app.natune.shared.ui.resources.ios_lane_nav_style_yuma
import app.natune.shared.ui.resources.ios_lane_nav_tint_frosted
import app.natune.shared.ui.resources.ios_lane_nav_tint_frosted_desc
import app.natune.shared.ui.resources.ios_lane_nav_transparency
import app.natune.shared.ui.resources.ios_lane_nav_transparency_desc
import app.natune.shared.ui.resources.ios_lane_nav_width
import app.natune.shared.ui.resources.ios_lane_nav_width_desc
import app.natune.shared.ui.resources.m6_liquid_glass_blur_overridden
import app.natune.shared.ui.resources.m6_liquid_glass_unsupported
import androidx.compose.ui.Modifier

const val NavigationBarSettingsRoute = "settings/appearance/navigation_bar"

/**
 * Settings > Appearance > Navigation bar on every shared host: the same rows and persisted keys as
 * the Android page, so the shell reads every choice with no translation. Sliders keep the Android
 * ranges and defaults; the treatment switches go through [applyNavigationTreatment], so at most one
 * treatment is on. The Liquid Glass row additionally reports why it is greyed out, the way the
 * Android row does: blur off, no refraction support, or the master switch off.
 */
@Composable
fun NavigationBarSettingsPage(
    controller: SettingsController,
    blurSupported: Boolean,
    refractionSupported: Boolean,
    onFailure: () -> Unit,
    scrollTo: String?,
    modifier: Modifier = Modifier,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot
    if (preferences == null) {
        CircularProgressIndicator()
        return
    }
    val style = preferences.selectedNavigationBarStyle()
    val liquidGlassEnabled = preferences[LiquidGlassEnabledKey, false]
    val liquidNav = preferences[LiquidGlassNavBarEnabledKey, false]
    val stockColor = preferences[CompactControlsStockColorKey, false]
    val glow = preferences[NavigationBarGlassGlowKey, true]
    val fixedDimensions = liquidNav || style == NavigationBarStyle.SUKISU
    val blurDisabled = preferences[DisableBlurKey, false] || !blurSupported
    val liquidRowEnabled = liquidGlassEnabled && refractionSupported && !blurDisabled
    val liquidDescription = when {
        blurDisabled -> Res.string.m6_liquid_glass_blur_overridden
        !refractionSupported -> Res.string.m6_liquid_glass_unsupported
        !liquidGlassEnabled -> Res.string.ios_lane_nav_liquid_disabled
        else -> Res.string.ios_lane_nav_liquid_desc
    }
    val scope = rememberCoroutineScope()

    val rows = buildList {
        add(
            SettingsRow.Choice(
                "navigation_bar_style", Res.string.ios_lane_nav_bar_style, NavigationBarStyleKey,
                NavigationBarStyle.DEFAULT.name,
                listOf(
                    SettingsRow.Option(NavigationBarStyle.DEFAULT.name, Res.string.ios_lane_nav_style_default),
                    SettingsRow.Option(NavigationBarStyle.FLOATING.name, Res.string.ios_lane_nav_style_floating),
                    SettingsRow.Option(NavigationBarStyle.YUMA_PLAYER.name, Res.string.ios_lane_nav_style_yuma),
                    SettingsRow.Option(NavigationBarStyle.APPLE_MUSIC.name, Res.string.ios_lane_nav_style_apple_music),
                    SettingsRow.Option(NavigationBarStyle.SUKISU.name, Res.string.ios_lane_nav_style_sukisu),
                ),
                read = { it.selectedNavigationBarStyle().name },
            ),
        )
        add(
            SettingsRow.Toggle(
                "compact_bottom_pills", Res.string.ios_lane_nav_layout, CompactBottomPillsKey, false,
                enabled = style != NavigationBarStyle.YUMA_PLAYER && style != NavigationBarStyle.APPLE_MUSIC,
                description = Res.string.ios_lane_nav_layout_desc,
                descriptionFor = {
                    if (it) Res.string.ios_lane_nav_compact else Res.string.ios_lane_nav_style_default
                },
            ),
        )
        add(
            SettingsRow.Choice(
                "navigation_bar_compact_behavior", Res.string.ios_lane_nav_compact_behavior,
                NavigationBarCompactBehaviorKey, NavigationBarCompactBehavior.ADAPTIVE.name,
                listOf(
                    SettingsRow.Option(
                        NavigationBarCompactBehavior.ADAPTIVE.name, Res.string.ios_lane_nav_compact_adaptive,
                    ),
                    SettingsRow.Option(
                        NavigationBarCompactBehavior.ALWAYS_EXPANDED.name,
                        Res.string.ios_lane_nav_compact_always_expanded,
                    ),
                    SettingsRow.Option(
                        NavigationBarCompactBehavior.ALWAYS_COMPACT.name,
                        Res.string.ios_lane_nav_compact_always_compact,
                    ),
                ),
                // The Apple Music bar is always the one-row layout; the stored choice is kept for other styles.
                enabled = style != NavigationBarStyle.APPLE_MUSIC,
                description = Res.string.ios_lane_nav_compact_behavior_desc,
                read = { it.navigationBarCompactBehavior().name },
            ),
        )
        add(
            SettingsRow.Toggle(
                "frosted_nav_bar", Res.string.ios_lane_nav_frosted, NavigationBarFrostedBlurKey, false,
                enabled = !blurDisabled,
                description = if (blurDisabled) {
                    Res.string.m6_liquid_glass_blur_overridden
                } else {
                    Res.string.ios_lane_nav_frosted_desc
                },
                write = { applyNavigationTreatment(NavigationBarFrostedBlurKey, it) },
            ),
        )
        add(
            SettingsRow.Toggle(
                "tint_frosted_nav_bar", Res.string.ios_lane_nav_tint_frosted, NavigationBarTintFrostedBlurKey, false,
                enabled = !blurDisabled,
                description = if (blurDisabled) {
                    Res.string.m6_liquid_glass_blur_overridden
                } else {
                    Res.string.ios_lane_nav_tint_frosted_desc
                },
                write = { applyNavigationTreatment(NavigationBarTintFrostedBlurKey, it) },
            ),
        )
        add(
            SettingsRow.Toggle(
                "liquid_glass_nav_bar", Res.string.ios_lane_nav_liquid, LiquidGlassNavBarEnabledKey, false,
                enabled = liquidRowEnabled,
                description = liquidDescription,
                write = { applyNavigationTreatment(LiquidGlassNavBarEnabledKey, it) },
            ),
        )
        add(
            SettingsRow.Toggle(
                "compact_controls_stock_color", Res.string.ios_lane_nav_stock_color,
                CompactControlsStockColorKey, false,
                description = Res.string.ios_lane_nav_stock_color_desc,
            ),
        )
        add(
            SettingsRow.Toggle(
                "navigation_bar_glass_glow", Res.string.ios_lane_nav_glow, NavigationBarGlassGlowKey, true,
                description = Res.string.ios_lane_nav_glow_desc,
            ),
        )
        add(
            SettingsRow.Decimal(
                "navigation_bar_glass_glow_intensity", Res.string.ios_lane_nav_glow_intensity,
                NavigationBarGlassGlowIntensityKey, NAVIGATION_BAR_GLASS_GLOW_INTENSITY_DEFAULT, 0.2f..2f,
                enabled = glow && !stockColor,
                unit = SettingsUnit.PERCENT,
                description = Res.string.ios_lane_nav_glow_intensity_desc,
            ),
        )
        add(
            SettingsRow.Toggle(
                "hide_navigation_bar_labels", Res.string.ios_lane_nav_hide_labels,
                HideNavigationBarLabelsKey, false,
                description = Res.string.ios_lane_nav_hide_labels_desc,
            ),
        )
        add(
            SettingsRow.Toggle(
                "navigation_bar_hide_on_scroll", Res.string.ios_lane_nav_hide_on_scroll,
                NavigationBarHideOnScrollKey, false,
                description = Res.string.ios_lane_nav_hide_on_scroll_desc,
            ),
        )
        add(SettingsRow.Header("navigation_bar_dimensions", Res.string.ios_lane_nav_dimensions))
        add(
            SettingsRow.Decimal(
                "navigation_bar_width", Res.string.ios_lane_nav_width, NavigationBarWidthKey,
                NAVIGATION_BAR_WIDTH_DEFAULT, 0.5f..1.0f,
                enabled = !fixedDimensions,
                unit = SettingsUnit.PERCENT,
                description = Res.string.ios_lane_nav_width_desc,
            ),
        )
        add(
            SettingsRow.Decimal(
                "navigation_bar_height", Res.string.ios_lane_nav_height, NavigationBarHeightKey,
                NAVIGATION_BAR_HEIGHT_DEFAULT, 0.8f..1.4f,
                enabled = !fixedDimensions,
                unit = SettingsUnit.PERCENT,
                description = Res.string.ios_lane_nav_height_desc,
            ),
        )
        add(
            SettingsRow.Decimal(
                "navigation_bar_opacity", Res.string.ios_lane_nav_opacity, NavigationBarOpacityKey,
                NAVIGATION_BAR_OPACITY_DEFAULT, 0.2f..1.0f,
                enabled = !fixedDimensions,
                unit = SettingsUnit.PERCENT,
                description = Res.string.ios_lane_nav_opacity_desc,
            ),
        )
        add(
            SettingsRow.Decimal(
                "navigation_bar_transparency", Res.string.ios_lane_nav_transparency, NavigationBarTransparencyKey,
                NAVIGATION_BAR_TRANSPARENCY_DEFAULT, 0.0f..0.95f,
                enabled = !fixedDimensions,
                unit = SettingsUnit.PERCENT,
                description = Res.string.ios_lane_nav_transparency_desc,
            ),
        )
        add(
            SettingsRow.Decimal(
                "navigation_bar_label_spacing", Res.string.ios_lane_nav_label_spacing, NavigationBarLabelSpacingKey,
                NAVIGATION_BAR_LABEL_SPACING_DEFAULT, 0f..16f,
                enabled = !fixedDimensions,
                description = Res.string.ios_lane_nav_label_spacing_desc,
            ),
        )
        add(
            SettingsRow.Decimal(
                "navigation_bar_corner_radius", Res.string.ios_lane_nav_corner_radius, NavigationBarCornerRadiusKey,
                NAVIGATION_BAR_CORNER_RADIUS_DEFAULT, 0f..48f,
                enabled = !fixedDimensions,
                description = Res.string.ios_lane_nav_corner_radius_desc,
            ),
        )
        add(
            SettingsRow.Action(
                "navigation_bar_reset_dimensions", Res.string.ios_lane_nav_reset_dimensions,
                enabled = !fixedDimensions && !preferences.navBarAtDefaults(),
                run = {
                    scope.launch {
                        try {
                            controller.edit {
                                this[NavigationBarWidthKey] = NAVIGATION_BAR_WIDTH_DEFAULT
                                this[NavigationBarHeightKey] = NAVIGATION_BAR_HEIGHT_DEFAULT
                                this[NavigationBarOpacityKey] = NAVIGATION_BAR_OPACITY_DEFAULT
                                this[NavigationBarTransparencyKey] = NAVIGATION_BAR_TRANSPARENCY_DEFAULT
                                this[NavigationBarLabelSpacingKey] = NAVIGATION_BAR_LABEL_SPACING_DEFAULT
                                this[NavigationBarCornerRadiusKey] = NAVIGATION_BAR_CORNER_RADIUS_DEFAULT
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            onFailure()
                        }
                    }
                },
            ),
        )
    }
    SettingsPage(rows, preferences, controller, scrollTo, onFailure = onFailure, modifier = modifier)
}

private fun PreferenceSnapshot.navBarAtDefaults(): Boolean =
    this[NavigationBarWidthKey, NAVIGATION_BAR_WIDTH_DEFAULT] == NAVIGATION_BAR_WIDTH_DEFAULT &&
        this[NavigationBarHeightKey, NAVIGATION_BAR_HEIGHT_DEFAULT] == NAVIGATION_BAR_HEIGHT_DEFAULT &&
        this[NavigationBarOpacityKey, NAVIGATION_BAR_OPACITY_DEFAULT] == NAVIGATION_BAR_OPACITY_DEFAULT &&
        this[NavigationBarTransparencyKey, NAVIGATION_BAR_TRANSPARENCY_DEFAULT] ==
        NAVIGATION_BAR_TRANSPARENCY_DEFAULT &&
        this[NavigationBarLabelSpacingKey, NAVIGATION_BAR_LABEL_SPACING_DEFAULT] ==
        NAVIGATION_BAR_LABEL_SPACING_DEFAULT &&
        this[NavigationBarCornerRadiusKey, NAVIGATION_BAR_CORNER_RADIUS_DEFAULT] == NAVIGATION_BAR_CORNER_RADIUS_DEFAULT
