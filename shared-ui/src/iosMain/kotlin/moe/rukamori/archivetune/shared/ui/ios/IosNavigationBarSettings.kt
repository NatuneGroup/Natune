package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.CompactBottomPillsKey
import moe.rukamori.archivetune.shared.domain.settings.CompactControlsStockColorKey
import moe.rukamori.archivetune.shared.domain.settings.HideNavigationBarLabelsKey
import moe.rukamori.archivetune.shared.domain.settings.LiquidGlassEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.LiquidGlassNavBarEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.NavigationBarCompactBehavior
import moe.rukamori.archivetune.shared.domain.settings.NavigationBarCompactBehaviorKey
import moe.rukamori.archivetune.shared.domain.settings.NavigationBarCornerRadiusKey
import moe.rukamori.archivetune.shared.domain.settings.NavigationBarFrostedBlurKey
import moe.rukamori.archivetune.shared.domain.settings.NavigationBarGlassGlowIntensityKey
import moe.rukamori.archivetune.shared.domain.settings.NavigationBarGlassGlowKey
import moe.rukamori.archivetune.shared.domain.settings.NavigationBarHeightKey
import moe.rukamori.archivetune.shared.domain.settings.NavigationBarHideOnScrollKey
import moe.rukamori.archivetune.shared.domain.settings.NavigationBarLabelSpacingKey
import moe.rukamori.archivetune.shared.domain.settings.NavigationBarOpacityKey
import moe.rukamori.archivetune.shared.domain.settings.NavigationBarStyle
import moe.rukamori.archivetune.shared.domain.settings.NavigationBarStyleKey
import moe.rukamori.archivetune.shared.domain.settings.NavigationBarTintFrostedBlurKey
import moe.rukamori.archivetune.shared.domain.settings.NavigationBarTransparencyKey
import moe.rukamori.archivetune.shared.domain.settings.NavigationBarWidthKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.applyNavigationTreatment
import moe.rukamori.archivetune.shared.domain.settings.selectedNavigationBarStyle
import moe.rukamori.archivetune.shared.ui.glass.NAVIGATION_BAR_GLASS_GLOW_INTENSITY_DEFAULT
import moe.rukamori.archivetune.shared.ui.navigation.NAVIGATION_BAR_CORNER_RADIUS_DEFAULT
import moe.rukamori.archivetune.shared.ui.navigation.NAVIGATION_BAR_HEIGHT_DEFAULT
import moe.rukamori.archivetune.shared.ui.navigation.NAVIGATION_BAR_LABEL_SPACING_DEFAULT
import moe.rukamori.archivetune.shared.ui.navigation.NAVIGATION_BAR_OPACITY_DEFAULT
import moe.rukamori.archivetune.shared.ui.navigation.NAVIGATION_BAR_TRANSPARENCY_DEFAULT
import moe.rukamori.archivetune.shared.ui.navigation.NAVIGATION_BAR_WIDTH_DEFAULT
import moe.rukamori.archivetune.shared.ui.navigation.navigationBarCompactBehavior
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_bar_style
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_compact
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_compact_adaptive
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_compact_always_compact
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_compact_always_expanded
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_compact_behavior
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_compact_behavior_desc
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_corner_radius
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_corner_radius_desc
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_dimensions
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_frosted
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_frosted_desc
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_glow
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_glow_desc
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_glow_intensity
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_glow_intensity_desc
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_height
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_height_desc
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_hide_labels
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_hide_labels_desc
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_hide_on_scroll
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_hide_on_scroll_desc
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_label_spacing
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_label_spacing_desc
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_layout
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_layout_desc
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_liquid
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_liquid_desc
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_liquid_disabled
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_opacity
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_opacity_desc
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_reset_dimensions
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_stock_color
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_stock_color_desc
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_style_apple_music
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_style_default
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_style_floating
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_style_sukisu
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_style_yuma
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_tint_frosted
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_tint_frosted_desc
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_transparency
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_transparency_desc
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_width
import moe.rukamori.archivetune.shared.ui.resources.ios_lane_nav_width_desc
import moe.rukamori.archivetune.shared.ui.settings.SettingsController
import moe.rukamori.archivetune.shared.ui.settings.SettingsPage
import moe.rukamori.archivetune.shared.ui.settings.SettingsRow
import moe.rukamori.archivetune.shared.ui.settings.SettingsUnit

/**
 * Settings > Appearance > Navigation bar on this host: the same rows and persisted keys as the Android page, so the
 * shell reads every choice with no translation. Sliders keep the Android ranges and defaults; the treatment switches
 * go through [applyNavigationTreatment], so at most one treatment is on.
 */
@Composable
internal fun IosNavigationBarSettingsPage(
    controller: SettingsController,
    onFailure: () -> Unit,
    scrollTo: String?,
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
                description = Res.string.ios_lane_nav_frosted_desc,
                write = { applyNavigationTreatment(NavigationBarFrostedBlurKey, it) },
            ),
        )
        add(
            SettingsRow.Toggle(
                "tint_frosted_nav_bar", Res.string.ios_lane_nav_tint_frosted, NavigationBarTintFrostedBlurKey, false,
                description = Res.string.ios_lane_nav_tint_frosted_desc,
                write = { applyNavigationTreatment(NavigationBarTintFrostedBlurKey, it) },
            ),
        )
        add(
            SettingsRow.Toggle(
                "liquid_glass_nav_bar", Res.string.ios_lane_nav_liquid, LiquidGlassNavBarEnabledKey, false,
                enabled = liquidGlassEnabled,
                description = if (liquidGlassEnabled) {
                    Res.string.ios_lane_nav_liquid_desc
                } else {
                    Res.string.ios_lane_nav_liquid_disabled
                },
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
                enabled = !fixedDimensions && !preferences.iosLaneNavBarAtDefaults(),
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
    SettingsPage(rows, preferences, controller, scrollTo, onFailure = onFailure)
}

private fun PreferenceSnapshot.iosLaneNavBarAtDefaults(): Boolean =
    this[NavigationBarWidthKey, NAVIGATION_BAR_WIDTH_DEFAULT] == NAVIGATION_BAR_WIDTH_DEFAULT &&
        this[NavigationBarHeightKey, NAVIGATION_BAR_HEIGHT_DEFAULT] == NAVIGATION_BAR_HEIGHT_DEFAULT &&
        this[NavigationBarOpacityKey, NAVIGATION_BAR_OPACITY_DEFAULT] == NAVIGATION_BAR_OPACITY_DEFAULT &&
        this[NavigationBarTransparencyKey, NAVIGATION_BAR_TRANSPARENCY_DEFAULT] ==
        NAVIGATION_BAR_TRANSPARENCY_DEFAULT &&
        this[NavigationBarLabelSpacingKey, NAVIGATION_BAR_LABEL_SPACING_DEFAULT] ==
        NAVIGATION_BAR_LABEL_SPACING_DEFAULT &&
        this[NavigationBarCornerRadiusKey, NAVIGATION_BAR_CORNER_RADIUS_DEFAULT] == NAVIGATION_BAR_CORNER_RADIUS_DEFAULT
