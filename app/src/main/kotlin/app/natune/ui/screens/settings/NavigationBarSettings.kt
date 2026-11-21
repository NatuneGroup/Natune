/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

@file:OptIn(ExperimentalMaterial3Api::class)

package app.natune.ui.screens.settings

import android.os.Build
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.navigation.NavController
import app.natune.LocalPlayerAwareWindowInsets
import app.natune.R
import app.natune.constants.CompactBottomPillsKey
import app.natune.constants.CompactControlsStockColorKey
import app.natune.constants.DisableBlurKey
import app.natune.constants.HideNavigationBarLabelsKey
import app.natune.constants.LiquidGlassEnabledKey
import app.natune.constants.LiquidGlassNavBarEnabledKey
import app.natune.constants.NAVIGATION_BAR_CORNER_RADIUS_DEFAULT
import app.natune.constants.NAVIGATION_BAR_HEIGHT_DEFAULT
import app.natune.constants.NAVIGATION_BAR_LABEL_SPACING_DEFAULT
import app.natune.constants.NAVIGATION_BAR_OPACITY_DEFAULT
import app.natune.constants.NAVIGATION_BAR_TRANSPARENCY_DEFAULT
import app.natune.constants.NAVIGATION_BAR_WIDTH_DEFAULT
import app.natune.constants.NavigationBarCompactBehavior
import app.natune.constants.NavigationBarCompactBehaviorKey
import app.natune.constants.NavigationBarCornerRadiusKey
import app.natune.constants.NavigationBarFrostedBlurKey
import app.natune.constants.NavigationBarHeightKey
import app.natune.constants.NavigationBarHideOnScrollKey
import app.natune.constants.NavigationBarLabelSpacingKey
import app.natune.constants.NavigationBarOpacityKey
import app.natune.constants.NavigationBarStyle
import app.natune.constants.NavigationBarStyleKey
import app.natune.constants.NavigationBarTintFrostedBlurKey
import app.natune.constants.NavigationBarTransparencyKey
import app.natune.constants.NavigationBarWidthKey
import app.natune.shared.ui.resources.Res
import app.natune.shared.ui.resources.ios_lane_nav_compact_adaptive
import app.natune.shared.ui.resources.ios_lane_nav_compact_always_compact
import app.natune.shared.ui.resources.ios_lane_nav_compact_always_expanded
import app.natune.shared.ui.resources.ios_lane_nav_compact_behavior
import app.natune.shared.ui.resources.ios_lane_nav_compact_behavior_desc
import app.natune.shared.ui.resources.ios_lane_nav_glow
import app.natune.shared.ui.resources.ios_lane_nav_glow_desc
import app.natune.shared.ui.resources.ios_lane_nav_glow_intensity
import app.natune.shared.ui.resources.ios_lane_nav_glow_intensity_desc
import app.natune.shared.ui.resources.ios_lane_nav_liquid
import app.natune.shared.ui.resources.ios_lane_nav_liquid_desc
import app.natune.shared.ui.resources.ios_lane_nav_liquid_disabled
import app.natune.shared.ui.resources.ios_lane_nav_stock_color
import app.natune.shared.ui.resources.ios_lane_nav_stock_color_desc
import app.natune.shared.ui.resources.ios_lane_nav_style_sukisu
import app.natune.shared.ui.resources.m6_liquid_glass_blur_overridden
import app.natune.shared.ui.resources.m6_liquid_glass_unsupported
import app.natune.ui.component.EnumListPreference
import app.natune.ui.component.IconButton
import app.natune.ui.component.LocalRestartPrompt
import app.natune.ui.component.NAVIGATION_BAR_GLASS_GLOW_INTENSITY_DEFAULT
import app.natune.ui.component.NavigationBarGlassGlowIntensityKey
import app.natune.ui.component.NavigationBarGlassGlowKey
import app.natune.ui.component.PreferenceEntry
import app.natune.ui.component.SettingsTopAppBar
import app.natune.ui.component.SwitchPreference
import app.natune.ui.component.preferenceGroup
import app.natune.ui.component.setNavigationTreatment
import app.natune.ui.utils.backToMain
import app.natune.utils.rememberPreferenceSnapshot
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource as sharedString

/**
 * Settings > Appearance > Navigation bar: the style, layout, treatment, glow and dimension rows the bar
 * renders, in the same Yuma rows as every other Android settings page. Rules match the shared page that
 * desktop and iOS use: at most one blur/glass treatment, SukiSU and Liquid Glass fix their own
 * dimensions, and the Apple Music and Yuma styles keep their own layout.
 */
@Composable
fun NavigationBarSettings(navController: NavController, scrollTo: String? = null) {
    val prefs = rememberPreferenceSnapshot()
    val restartPrompt = LocalRestartPrompt.current
    val (style, onStyleChange) = prefs.enumState(NavigationBarStyleKey, NavigationBarStyle.DEFAULT)
    val (compactPills, onCompactPillsChange) = prefs.state(CompactBottomPillsKey, defaultValue = false)
    val (compactBehavior, onCompactBehaviorChange) =
        prefs.enumState(NavigationBarCompactBehaviorKey, NavigationBarCompactBehavior.ADAPTIVE)
    val (frosted) = prefs.state(NavigationBarFrostedBlurKey, defaultValue = false)
    val (tintFrosted) = prefs.state(NavigationBarTintFrostedBlurKey, defaultValue = false)
    val (liquidNav) = prefs.state(LiquidGlassNavBarEnabledKey, defaultValue = false)
    val (liquidGlassEnabled) = prefs.state(LiquidGlassEnabledKey, defaultValue = false)
    val (disableBlur) = prefs.state(DisableBlurKey, defaultValue = false)
    val (stockColor, onStockColorChange) = prefs.state(CompactControlsStockColorKey, defaultValue = false)
    val (glow, onGlowChange) = prefs.state(NavigationBarGlassGlowKey, defaultValue = true)
    val (glowIntensity, onGlowIntensityChange) =
        prefs.state(NavigationBarGlassGlowIntensityKey, defaultValue = NAVIGATION_BAR_GLASS_GLOW_INTENSITY_DEFAULT)
    val (hideLabels, onHideLabelsChange) = prefs.state(HideNavigationBarLabelsKey, defaultValue = false)
    val (hideOnScroll, onHideOnScrollChange) = prefs.state(NavigationBarHideOnScrollKey, defaultValue = false)
    val (width, onWidthChange) = prefs.state(NavigationBarWidthKey, defaultValue = NAVIGATION_BAR_WIDTH_DEFAULT)
    val (height, onHeightChange) = prefs.state(NavigationBarHeightKey, defaultValue = NAVIGATION_BAR_HEIGHT_DEFAULT)
    val (opacity, onOpacityChange) = prefs.state(NavigationBarOpacityKey, defaultValue = NAVIGATION_BAR_OPACITY_DEFAULT)
    val (transparency, onTransparencyChange) =
        prefs.state(NavigationBarTransparencyKey, defaultValue = NAVIGATION_BAR_TRANSPARENCY_DEFAULT)
    val (labelSpacing, onLabelSpacingChange) =
        prefs.state(NavigationBarLabelSpacingKey, defaultValue = NAVIGATION_BAR_LABEL_SPACING_DEFAULT)
    val (cornerRadius, onCornerRadiusChange) =
        prefs.state(NavigationBarCornerRadiusKey, defaultValue = NAVIGATION_BAR_CORNER_RADIUS_DEFAULT)

    // Backdrop blur and the refraction shader both need Android 12's RenderEffect, as in PillStyle.
    val opticsSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val blurDisabled = disableBlur || !opticsSupported
    val liquidRowEnabled = liquidGlassEnabled && opticsSupported && !blurDisabled
    val fixedDimensions = liquidNav || style == NavigationBarStyle.SUKISU
    val ownLayout = style == NavigationBarStyle.YUMA_PLAYER || style == NavigationBarStyle.APPLE_MUSIC
    val atDefaults =
        width == NAVIGATION_BAR_WIDTH_DEFAULT && height == NAVIGATION_BAR_HEIGHT_DEFAULT &&
            opacity == NAVIGATION_BAR_OPACITY_DEFAULT && transparency == NAVIGATION_BAR_TRANSPARENCY_DEFAULT &&
            labelSpacing == NAVIGATION_BAR_LABEL_SPACING_DEFAULT && cornerRadius == NAVIGATION_BAR_CORNER_RADIUS_DEFAULT
    // Frosted, tinted and Liquid Glass bars are restart-scoped and mutually exclusive.
    val setTreatment: (Preferences.Key<Boolean>, Boolean) -> Unit = { key, enabled ->
        setNavigationTreatment(restartPrompt, prefs, key, enabled)
    }
    val navIcon: @Composable () -> Unit = { Icon(painterResource(R.drawable.nav_bar), null) }
    val tuneIcon: @Composable () -> Unit = { Icon(painterResource(R.drawable.tune), null) }
    val blurIcon: @Composable () -> Unit = { Icon(painterResource(R.drawable.blur_on), null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SettingsTopAppBar(
                title = { Text(stringResource(R.string.navigation_bar_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
                        Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                    }
                },
            )
        },
    ) { innerPadding ->
        val playerAwareBottomPadding =
            LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
        val listState = rememberLazyListState()
        val positions = rememberPreferencePositions()
        val generalTitle = stringResource(R.string.general)
        val dimensionsTitle = stringResource(R.string.navigation_bar_dimensions)
        val effectsTitle = stringResource(R.string.frosted_blur)

        LaunchedEffect(scrollTo) { positions.scrollToKey(scrollTo, listState) }

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = playerAwareBottomPadding + SettingsDimensions.ScreenBottomPadding),
            modifier =
                Modifier
                    .padding(top = innerPadding.calculateTopPadding())
                    .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal))
                    .then(positions.containerModifier()),
        ) {
            preferenceGroup(title = generalTitle) {
                item {
                    EnumListPreference(
                        modifier = positions.modifierFor("navigation_bar_style"),
                        title = { Text(stringResource(R.string.navigation_bar_style)) },
                        icon = { Icon(painterResource(R.drawable.style), null) },
                        selectedValue = style,
                        valueText = { it.label() },
                        onValueSelected = onStyleChange,
                    )
                }
                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("compact_bottom_pills"),
                        title = { Text(stringResource(R.string.navigation_bar_layout)) },
                        description = stringResource(R.string.navigation_bar_layout_desc),
                        icon = navIcon,
                        checked = compactPills,
                        onCheckedChange = onCompactPillsChange,
                        isEnabled = !ownLayout,
                    )
                }
                item {
                    EnumListPreference(
                        modifier = positions.modifierFor("navigation_bar_compact_behavior"),
                        title = { Text(sharedString(Res.string.ios_lane_nav_compact_behavior)) },
                        description = sharedString(Res.string.ios_lane_nav_compact_behavior_desc),
                        icon = navIcon,
                        selectedValue = compactBehavior,
                        valueText = { it.label() },
                        onValueSelected = onCompactBehaviorChange,
                        isEnabled = style != NavigationBarStyle.APPLE_MUSIC,
                    )
                }
                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("hide_navigation_bar_labels"),
                        title = { Text(stringResource(R.string.hide_navigation_bar_labels)) },
                        description = stringResource(R.string.hide_navigation_bar_labels_desc),
                        icon = navIcon,
                        checked = hideLabels,
                        onCheckedChange = onHideLabelsChange,
                    )
                }
                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("navigation_bar_hide_on_scroll"),
                        title = { Text(stringResource(R.string.navigation_bar_hide_on_scroll)) },
                        description = stringResource(R.string.navigation_bar_hide_on_scroll_desc),
                        icon = navIcon,
                        checked = hideOnScroll,
                        onCheckedChange = onHideOnScrollChange,
                    )
                }
            }

            preferenceGroup(title = effectsTitle) {
                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("frosted_nav_bar"),
                        title = { Text(stringResource(R.string.navigation_bar_frosted_blur)) },
                        description = stringResource(
                            if (blurDisabled) R.string.navigation_bar_frosted_blur_unsupported
                            else R.string.navigation_bar_frosted_blur_desc,
                        ),
                        icon = blurIcon,
                        checked = frosted,
                        onCheckedChange = { setTreatment(NavigationBarFrostedBlurKey, it) },
                        isEnabled = !blurDisabled,
                    )
                }
                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("tint_frosted_nav_bar"),
                        title = { Text(stringResource(R.string.navigation_bar_tint_frosted_blur)) },
                        description = stringResource(
                            if (blurDisabled) R.string.navigation_bar_frosted_blur_unsupported
                            else R.string.navigation_bar_tint_frosted_blur_desc,
                        ),
                        icon = blurIcon,
                        checked = tintFrosted,
                        onCheckedChange = { setTreatment(NavigationBarTintFrostedBlurKey, it) },
                        isEnabled = !blurDisabled,
                    )
                }
                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("liquid_glass_nav_bar"),
                        title = { Text(sharedString(Res.string.ios_lane_nav_liquid)) },
                        description = sharedString(
                            when {
                                blurDisabled -> Res.string.m6_liquid_glass_blur_overridden
                                !opticsSupported -> Res.string.m6_liquid_glass_unsupported
                                !liquidGlassEnabled -> Res.string.ios_lane_nav_liquid_disabled
                                else -> Res.string.ios_lane_nav_liquid_desc
                            },
                        ),
                        icon = blurIcon,
                        checked = liquidNav,
                        onCheckedChange = { setTreatment(LiquidGlassNavBarEnabledKey, it) },
                        isEnabled = liquidRowEnabled,
                    )
                }
                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("compact_controls_stock_color"),
                        title = { Text(sharedString(Res.string.ios_lane_nav_stock_color)) },
                        description = sharedString(Res.string.ios_lane_nav_stock_color_desc),
                        icon = { Icon(painterResource(R.drawable.palette), null) },
                        checked = stockColor,
                        onCheckedChange = onStockColorChange,
                    )
                }
                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("navigation_bar_glass_glow"),
                        title = { Text(sharedString(Res.string.ios_lane_nav_glow)) },
                        description = sharedString(Res.string.ios_lane_nav_glow_desc),
                        icon = blurIcon,
                        checked = glow,
                        onCheckedChange = onGlowChange,
                    )
                }
                item {
                    NavigationBarSliderPreference(
                        modifier = positions.modifierFor("navigation_bar_glass_glow_intensity"),
                        title = sharedString(Res.string.ios_lane_nav_glow_intensity),
                        description = sharedString(Res.string.ios_lane_nav_glow_intensity_desc),
                        icon = tuneIcon,
                        value = glowIntensity,
                        valueRange = 0.2f..2f,
                        valueLabel = { "${(it * 100).roundToInt()}%" },
                        onValueChange = onGlowIntensityChange,
                        isEnabled = glow && !stockColor,
                    )
                }
            }

            preferenceGroup(title = dimensionsTitle) {
                item {
                    NavigationBarSliderPreference(
                        modifier = positions.modifierFor("navigation_bar_width"),
                        title = stringResource(R.string.navigation_bar_width),
                        description = stringResource(R.string.navigation_bar_width_desc),
                        icon = tuneIcon,
                        value = width,
                        valueRange = 0.5f..1f,
                        valueLabel = { "${(it * 100).roundToInt()}%" },
                        onValueChange = onWidthChange,
                        isEnabled = !fixedDimensions,
                    )
                }
                item {
                    NavigationBarSliderPreference(
                        modifier = positions.modifierFor("navigation_bar_height"),
                        title = stringResource(R.string.navigation_bar_height),
                        description = stringResource(R.string.navigation_bar_height_desc),
                        icon = tuneIcon,
                        value = height,
                        valueRange = 0.8f..1.4f,
                        valueLabel = { "${(it * 100).roundToInt()}%" },
                        onValueChange = onHeightChange,
                        isEnabled = !fixedDimensions,
                    )
                }
                item {
                    NavigationBarSliderPreference(
                        modifier = positions.modifierFor("navigation_bar_opacity"),
                        title = stringResource(R.string.navigation_bar_opacity),
                        description = stringResource(R.string.navigation_bar_opacity_desc),
                        icon = tuneIcon,
                        value = opacity,
                        valueRange = 0.2f..1f,
                        valueLabel = { "${(it * 100).roundToInt()}%" },
                        onValueChange = onOpacityChange,
                        isEnabled = !fixedDimensions,
                    )
                }
                item {
                    NavigationBarSliderPreference(
                        modifier = positions.modifierFor("navigation_bar_transparency"),
                        title = stringResource(R.string.navigation_bar_transparency),
                        description = stringResource(R.string.navigation_bar_transparency_desc),
                        icon = tuneIcon,
                        value = transparency,
                        valueRange = 0f..0.95f,
                        valueLabel = { "${(it * 100).roundToInt()}%" },
                        onValueChange = onTransparencyChange,
                        isEnabled = !fixedDimensions,
                    )
                }
                item {
                    NavigationBarSliderPreference(
                        modifier = positions.modifierFor("navigation_bar_label_spacing"),
                        title = stringResource(R.string.navigation_bar_label_spacing),
                        description = stringResource(R.string.navigation_bar_label_spacing_desc),
                        icon = tuneIcon,
                        value = labelSpacing,
                        valueRange = 0f..16f,
                        valueLabel = { "${it.roundToInt()} dp" },
                        onValueChange = onLabelSpacingChange,
                        isEnabled = !fixedDimensions,
                    )
                }
                item {
                    NavigationBarSliderPreference(
                        modifier = positions.modifierFor("navigation_bar_corner_radius"),
                        title = stringResource(R.string.navigation_bar_corner_radius),
                        description = stringResource(R.string.navigation_bar_corner_radius_desc),
                        icon = tuneIcon,
                        value = cornerRadius,
                        valueRange = 0f..48f,
                        valueLabel = { "${it.roundToInt()} dp" },
                        onValueChange = onCornerRadiusChange,
                        isEnabled = !fixedDimensions,
                    )
                }
                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("navigation_bar_reset_dimensions"),
                        title = { Text(stringResource(R.string.navigation_bar_reset_dimensions)) },
                        icon = { Icon(painterResource(R.drawable.restore), null) },
                        isEnabled = !fixedDimensions && !atDefaults,
                        onClick = {
                            onWidthChange(NAVIGATION_BAR_WIDTH_DEFAULT)
                            onHeightChange(NAVIGATION_BAR_HEIGHT_DEFAULT)
                            onOpacityChange(NAVIGATION_BAR_OPACITY_DEFAULT)
                            onTransparencyChange(NAVIGATION_BAR_TRANSPARENCY_DEFAULT)
                            onLabelSpacingChange(NAVIGATION_BAR_LABEL_SPACING_DEFAULT)
                            onCornerRadiusChange(NAVIGATION_BAR_CORNER_RADIUS_DEFAULT)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun NavigationBarStyle.label(): String =
    when (this) {
        NavigationBarStyle.DEFAULT -> stringResource(R.string.navigation_bar_style_default)
        NavigationBarStyle.FLOATING -> stringResource(R.string.navigation_bar_style_floating)
        NavigationBarStyle.YUMA_PLAYER -> stringResource(R.string.navigation_bar_style_yuma_player)
        NavigationBarStyle.APPLE_MUSIC -> stringResource(R.string.navigation_bar_style_apple_music)
        NavigationBarStyle.SUKISU -> sharedString(Res.string.ios_lane_nav_style_sukisu)
    }

@Composable
private fun NavigationBarCompactBehavior.label(): String =
    sharedString(
        when (this) {
            NavigationBarCompactBehavior.ADAPTIVE -> Res.string.ios_lane_nav_compact_adaptive
            NavigationBarCompactBehavior.ALWAYS_EXPANDED -> Res.string.ios_lane_nav_compact_always_expanded
            NavigationBarCompactBehavior.ALWAYS_COMPACT -> Res.string.ios_lane_nav_compact_always_compact
        },
    )

/** A Yuma [PreferenceEntry] with an inline slider; it saves once the drag ends, not on every frame. */
@Composable
private fun NavigationBarSliderPreference(
    modifier: Modifier,
    title: String,
    description: String,
    icon: @Composable () -> Unit,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: (Float) -> String,
    onValueChange: (Float) -> Unit,
    isEnabled: Boolean,
) {
    var sliderValue by remember { mutableFloatStateOf(value) }
    LaunchedEffect(value) { sliderValue = value }
    PreferenceEntry(
        modifier = modifier,
        title = { Text("$title · ${valueLabel(sliderValue)}") },
        description = description,
        icon = icon,
        isEnabled = isEnabled,
        content = {
            Spacer(Modifier.height(10.dp))
            Slider(
                value = sliderValue.coerceIn(valueRange.start, valueRange.endInclusive),
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { onValueChange(sliderValue.coerceIn(valueRange.start, valueRange.endInclusive)) },
                valueRange = valueRange,
                enabled = isEnabled,
                colors = SliderDefaults.colors(
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    thumbColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}
