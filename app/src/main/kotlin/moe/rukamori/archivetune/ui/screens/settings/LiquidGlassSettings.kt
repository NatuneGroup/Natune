package moe.rukamori.archivetune.ui.screens.settings

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
import androidx.navigation.NavController
import moe.rukamori.archivetune.LocalBlurPolicy
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.LiquidGlassAdaptiveLuminanceKey
import moe.rukamori.archivetune.constants.LiquidGlassBackdropVibrancyKey
import moe.rukamori.archivetune.constants.LiquidGlassBlurRadiusKey
import moe.rukamori.archivetune.constants.LiquidGlassChromaticAberrationKey
import moe.rukamori.archivetune.constants.LiquidGlassDepth3DKey
import moe.rukamori.archivetune.constants.LiquidGlassEnabledKey
import moe.rukamori.archivetune.constants.LiquidGlassIntensityKey
import moe.rukamori.archivetune.constants.LiquidGlassRefractionAmountKey
import moe.rukamori.archivetune.constants.LiquidGlassRefractionHeightKey
import moe.rukamori.archivetune.constants.LiquidGlassShadowDepthKey
import moe.rukamori.archivetune.constants.LiquidGlassTintOpacityKey
import moe.rukamori.archivetune.shared.domain.settings.LiquidGlassIntensity
import moe.rukamori.archivetune.shared.domain.settings.LiquidGlassTuningDefaults
import moe.rukamori.archivetune.ui.component.EnumListPreference
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.SettingsTopAppBar
import moe.rukamori.archivetune.ui.component.SwitchPreference
import moe.rukamori.archivetune.ui.component.preferenceGroup
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.rememberPreferenceSnapshot
import kotlin.math.roundToInt

const val LIQUID_GLASS_SETTINGS_ROUTE = "settings/appearance/liquid_glass"

/** The Appearance row that opens [LiquidGlassSettings]; kept here so the tuning page owns its own entry. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiquidGlassCustomisationEntry(navController: NavController, positions: PreferencePositions) {
    PreferenceEntry(
        modifier = positions.modifierFor("liquid_glass_customisation"),
        title = { Text(stringResource(R.string.liquid_glass_settings_title)) },
        description = stringResource(R.string.liquid_glass_customisation_subtitle),
        icon = { Icon(painterResource(R.drawable.tune), null) },
        onClick = { navController.navigate(LIQUID_GLASS_SETTINGS_ROUTE) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiquidGlassSettings(navController: NavController, scrollTo: String? = null) {
    val prefs = rememberPreferenceSnapshot()
    val (liquidGlassEnabled) = prefs.state(LiquidGlassEnabledKey, defaultValue = false)
    val (intensity, onIntensityChange) =
        prefs.enumState(LiquidGlassIntensityKey, defaultValue = LiquidGlassTuningDefaults.intensity)
    val (refractionHeight, onRefractionHeightChange) =
        prefs.state(LiquidGlassRefractionHeightKey, LiquidGlassTuningDefaults.REFRACTION_HEIGHT)
    val (refractionAmount, onRefractionAmountChange) =
        prefs.state(LiquidGlassRefractionAmountKey, LiquidGlassTuningDefaults.REFRACTION_AMOUNT)
    val (blurRadius, onBlurRadiusChange) = prefs.state(LiquidGlassBlurRadiusKey, LiquidGlassTuningDefaults.BLUR_RADIUS)
    val (tintOpacity, onTintOpacityChange) = prefs.state(LiquidGlassTintOpacityKey, LiquidGlassTuningDefaults.TINT_OPACITY)
    val (shadowDepth, onShadowDepthChange) = prefs.state(LiquidGlassShadowDepthKey, LiquidGlassTuningDefaults.SHADOW_DEPTH)
    val (depth3D, onDepth3DChange) = prefs.state(LiquidGlassDepth3DKey, LiquidGlassTuningDefaults.DEPTH_3D)
    val (chromaticAberration, onChromaticAberrationChange) =
        prefs.state(LiquidGlassChromaticAberrationKey, LiquidGlassTuningDefaults.CHROMATIC_ABERRATION)
    val (backdropVibrancy, onBackdropVibrancyChange) =
        prefs.state(LiquidGlassBackdropVibrancyKey, LiquidGlassTuningDefaults.BACKDROP_VIBRANCY)
    val (adaptiveLuminance, onAdaptiveLuminanceChange) =
        prefs.state(LiquidGlassAdaptiveLuminanceKey, LiquidGlassTuningDefaults.ADAPTIVE_LUMINANCE)

    val blurDisabled = !LocalBlurPolicy.current.any
    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    // Tuning is stored whatever the state, but it only shows while glass can actually draw.
    val enabled = liquidGlassEnabled && supported && !blurDisabled
    val unavailableReason =
        when {
            blurDisabled -> stringResource(R.string.blur_setting_overridden)
            !supported -> stringResource(R.string.liquid_glass_effects_unsupported)
            !liquidGlassEnabled -> stringResource(R.string.liquid_glass_nav_bar_disabled)
            else -> null
        }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SettingsTopAppBar(
                title = { Text(stringResource(R.string.liquid_glass_settings_title)) },
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
        LaunchedEffect(scrollTo) { positions.scrollToKey(scrollTo, listState) }

        val generalTitle = stringResource(R.string.general)
        val opticsTitle = stringResource(R.string.liquid_glass_optics_group)
        val qualityTitle = stringResource(R.string.liquid_glass_quality_group)
        val resetTitle = stringResource(R.string.liquid_glass_reset_group)
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
                item(visible = unavailableReason != null) {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.liquid_glass_effects)) },
                        description = unavailableReason,
                        icon = { Icon(painterResource(R.drawable.info), null) },
                    )
                }
                item {
                    EnumListPreference(
                        modifier = positions.modifierFor("glass_intensity"),
                        title = { Text(stringResource(R.string.liquid_glass_intensity)) },
                        description = stringResource(R.string.liquid_glass_intensity_desc),
                        icon = { Icon(painterResource(R.drawable.graphic_eq), null) },
                        selectedValue = intensity,
                        onValueSelected = onIntensityChange,
                        valueText = {
                            when (it) {
                                LiquidGlassIntensity.SUBTLE -> stringResource(R.string.liquid_glass_intensity_subtle)
                                LiquidGlassIntensity.STANDARD -> stringResource(R.string.liquid_glass_intensity_standard)
                                LiquidGlassIntensity.VIVID -> stringResource(R.string.liquid_glass_intensity_vivid)
                            }
                        },
                        isEnabled = enabled,
                    )
                }
            }

            preferenceGroup(modifier = positions.modifierFor("glass_optics"), title = opticsTitle) {
                item {
                    GlassPercentSlider(
                        modifier = positions.modifierFor("glass_refraction_height"),
                        title = stringResource(R.string.liquid_glass_refraction_height),
                        description = stringResource(R.string.liquid_glass_refraction_height_desc),
                        value = refractionHeight,
                        onValueChange = onRefractionHeightChange,
                        enabled = enabled,
                    )
                }
                item {
                    GlassPercentSlider(
                        modifier = positions.modifierFor("glass_refraction_amount"),
                        title = stringResource(R.string.liquid_glass_refraction_amount),
                        description = stringResource(R.string.liquid_glass_refraction_amount_desc),
                        value = refractionAmount,
                        onValueChange = onRefractionAmountChange,
                        enabled = enabled,
                    )
                }
                item {
                    GlassPercentSlider(
                        modifier = positions.modifierFor("glass_blur_radius"),
                        title = stringResource(R.string.liquid_glass_blur_radius),
                        description = stringResource(R.string.liquid_glass_blur_radius_desc),
                        value = blurRadius,
                        onValueChange = onBlurRadiusChange,
                        enabled = enabled,
                    )
                }
                item {
                    GlassPercentSlider(
                        modifier = positions.modifierFor("glass_tint_opacity"),
                        title = stringResource(R.string.liquid_glass_tint_opacity),
                        description = stringResource(R.string.liquid_glass_tint_opacity_desc),
                        value = tintOpacity,
                        onValueChange = onTintOpacityChange,
                        enabled = enabled,
                        max = LiquidGlassTuningDefaults.TINT_OPACITY_MAX,
                    )
                }
                item {
                    GlassPercentSlider(
                        modifier = positions.modifierFor("glass_shadow_depth"),
                        title = stringResource(R.string.liquid_glass_shadow_depth),
                        description = stringResource(R.string.liquid_glass_shadow_depth_desc),
                        value = shadowDepth,
                        onValueChange = onShadowDepthChange,
                        enabled = enabled,
                    )
                }
            }

            preferenceGroup(modifier = positions.modifierFor("glass_quality"), title = qualityTitle) {
                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("glass_depth_3d"),
                        title = { Text(stringResource(R.string.liquid_glass_depth_3d)) },
                        description = stringResource(R.string.liquid_glass_depth_3d_desc),
                        icon = { Icon(painterResource(R.drawable.sliders), null) },
                        checked = depth3D,
                        onCheckedChange = onDepth3DChange,
                        isEnabled = enabled,
                    )
                }
                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("glass_chromatic_aberration"),
                        title = { Text(stringResource(R.string.liquid_glass_chromatic_aberration)) },
                        description = stringResource(R.string.liquid_glass_chromatic_aberration_desc),
                        icon = { Icon(painterResource(R.drawable.contrast), null) },
                        checked = chromaticAberration,
                        onCheckedChange = onChromaticAberrationChange,
                        isEnabled = enabled,
                    )
                }
                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("glass_backdrop_vibrancy"),
                        title = { Text(stringResource(R.string.liquid_glass_backdrop_vibrancy)) },
                        description = stringResource(R.string.liquid_glass_backdrop_vibrancy_desc),
                        icon = { Icon(painterResource(R.drawable.palette), null) },
                        checked = backdropVibrancy,
                        onCheckedChange = onBackdropVibrancyChange,
                        isEnabled = enabled,
                    )
                }
                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("glass_adaptive_luminance"),
                        title = { Text(stringResource(R.string.liquid_glass_adaptive_luminance)) },
                        description = stringResource(R.string.liquid_glass_adaptive_luminance_desc),
                        icon = { Icon(painterResource(R.drawable.solar_brightness_auto_linear), null) },
                        checked = adaptiveLuminance,
                        onCheckedChange = onAdaptiveLuminanceChange,
                        isEnabled = enabled,
                    )
                }
            }

            preferenceGroup(title = resetTitle) {
                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("glass_reset"),
                        title = { Text(stringResource(R.string.liquid_glass_reset)) },
                        description = stringResource(R.string.liquid_glass_reset_desc),
                        icon = { Icon(painterResource(R.drawable.restore), null) },
                        isEnabled = enabled,
                        onClick = {
                            onIntensityChange(LiquidGlassTuningDefaults.intensity)
                            onRefractionHeightChange(LiquidGlassTuningDefaults.REFRACTION_HEIGHT)
                            onRefractionAmountChange(LiquidGlassTuningDefaults.REFRACTION_AMOUNT)
                            onBlurRadiusChange(LiquidGlassTuningDefaults.BLUR_RADIUS)
                            onTintOpacityChange(LiquidGlassTuningDefaults.TINT_OPACITY)
                            onShadowDepthChange(LiquidGlassTuningDefaults.SHADOW_DEPTH)
                            onDepth3DChange(LiquidGlassTuningDefaults.DEPTH_3D)
                            onChromaticAberrationChange(LiquidGlassTuningDefaults.CHROMATIC_ABERRATION)
                            onBackdropVibrancyChange(LiquidGlassTuningDefaults.BACKDROP_VIBRANCY)
                            onAdaptiveLuminanceChange(LiquidGlassTuningDefaults.ADAPTIVE_LUMINANCE)
                        },
                    )
                }
            }
        }
    }
}

/**
 * A percentage slider that writes once when the drag ends. Every glass surface on screen re-reads
 * the tuning, so writing on each drag frame would re-run DataStore and every glass node per frame.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlassPercentSlider(
    title: String,
    description: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    max: Float = 1f,
) {
    var dragValue by remember(value) { mutableFloatStateOf(value.coerceIn(0f, max)) }
    PreferenceEntry(
        modifier = modifier,
        title = { Text(title) },
        description = description,
        trailingContent = {
            Text(
                text = stringResource(R.string.liquid_glass_percent_value, (dragValue * 100f).roundToInt()),
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
        },
        isEnabled = enabled,
        content = {
            Spacer(modifier = Modifier.height(10.dp))
            Slider(
                value = dragValue,
                onValueChange = { dragValue = it },
                onValueChangeFinished = { onValueChange(dragValue) },
                valueRange = 0f..max,
                steps = (max * 20f).roundToInt() - 1,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().padding(start = 56.dp),
            )
        },
    )
}
