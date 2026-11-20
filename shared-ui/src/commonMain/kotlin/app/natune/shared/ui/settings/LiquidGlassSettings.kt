package app.natune.shared.ui.settings

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import app.natune.shared.domain.settings.DisableBlurKey
import app.natune.shared.domain.settings.LiquidGlassAdaptiveLuminanceKey
import app.natune.shared.domain.settings.LiquidGlassBackdropVibrancyKey
import app.natune.shared.domain.settings.LiquidGlassBlurRadiusKey
import app.natune.shared.domain.settings.LiquidGlassChromaticAberrationKey
import app.natune.shared.domain.settings.LiquidGlassDepth3DKey
import app.natune.shared.domain.settings.LiquidGlassEnabledKey
import app.natune.shared.domain.settings.LiquidGlassIntensity
import app.natune.shared.domain.settings.LiquidGlassIntensityKey
import app.natune.shared.domain.settings.LiquidGlassRefractionAmountKey
import app.natune.shared.domain.settings.LiquidGlassRefractionHeightKey
import app.natune.shared.domain.settings.LiquidGlassShadowDepthKey
import app.natune.shared.domain.settings.LiquidGlassTintOpacityKey
import app.natune.shared.domain.settings.LiquidGlassTuningDefaults
import app.natune.shared.domain.settings.PreferenceSnapshot
import app.natune.shared.ui.resources.Res
import app.natune.shared.ui.resources.m6_liquid_glass_adaptive_luminance
import app.natune.shared.ui.resources.m6_liquid_glass_adaptive_luminance_desc
import app.natune.shared.ui.resources.m6_liquid_glass_backdrop_vibrancy
import app.natune.shared.ui.resources.m6_liquid_glass_backdrop_vibrancy_desc
import app.natune.shared.ui.resources.m6_liquid_glass_blur_overridden
import app.natune.shared.ui.resources.m6_liquid_glass_blur_radius
import app.natune.shared.ui.resources.m6_liquid_glass_blur_radius_desc
import app.natune.shared.ui.resources.m6_liquid_glass_chromatic_aberration
import app.natune.shared.ui.resources.m6_liquid_glass_chromatic_aberration_desc
import app.natune.shared.ui.resources.m6_liquid_glass_depth_3d
import app.natune.shared.ui.resources.m6_liquid_glass_depth_3d_desc
import app.natune.shared.ui.resources.m6_liquid_glass_disabled
import app.natune.shared.ui.resources.m6_liquid_glass_effects
import app.natune.shared.ui.resources.m6_liquid_glass_general
import app.natune.shared.ui.resources.m6_liquid_glass_intensity
import app.natune.shared.ui.resources.m6_liquid_glass_intensity_desc
import app.natune.shared.ui.resources.m6_liquid_glass_intensity_standard
import app.natune.shared.ui.resources.m6_liquid_glass_intensity_subtle
import app.natune.shared.ui.resources.m6_liquid_glass_intensity_vivid
import app.natune.shared.ui.resources.m6_liquid_glass_optics
import app.natune.shared.ui.resources.m6_liquid_glass_quality
import app.natune.shared.ui.resources.m6_liquid_glass_refraction_amount
import app.natune.shared.ui.resources.m6_liquid_glass_refraction_amount_desc
import app.natune.shared.ui.resources.m6_liquid_glass_refraction_height
import app.natune.shared.ui.resources.m6_liquid_glass_refraction_height_desc
import app.natune.shared.ui.resources.m6_liquid_glass_reset
import app.natune.shared.ui.resources.m6_liquid_glass_reset_desc
import app.natune.shared.ui.resources.m6_liquid_glass_reset_group
import app.natune.shared.ui.resources.m6_liquid_glass_shadow_depth
import app.natune.shared.ui.resources.m6_liquid_glass_shadow_depth_desc
import app.natune.shared.ui.resources.m6_liquid_glass_tint_opacity
import app.natune.shared.ui.resources.m6_liquid_glass_tint_opacity_desc
import app.natune.shared.ui.resources.m6_liquid_glass_unsupported
import org.jetbrains.compose.resources.stringResource

const val LiquidGlassSettingsRoute = "settings/appearance/liquid_glass"

/**
 * The rows of [LiquidGlassSettings], as a pure function so the gating is testable. Mirrors the
 * Android page: the tuning is stored whatever the state, but it is editable only while glass can
 * actually draw — the master switch on, blur allowed and a renderer that supports refraction.
 * A disabled page still shows the one row that says why.
 */
fun liquidGlassSettingsRows(
    preferences: PreferenceSnapshot,
    blurSupported: Boolean,
    refractionSupported: Boolean,
    onReset: () -> Unit,
): List<SettingsRow> {
    val blurDisabled = preferences[DisableBlurKey, false] || !blurSupported
    val glassEnabled = preferences[LiquidGlassEnabledKey, false]
    val enabled = glassEnabled && refractionSupported && !blurDisabled
    return buildList {
        add(SettingsRow.Header("liquid_glass_general", Res.string.m6_liquid_glass_general))
        if (!enabled) {
            add(
                SettingsRow.Custom("liquid_glass_note", Res.string.m6_liquid_glass_effects) {
                    ListItem(
                        headlineContent = { Text(stringResource(Res.string.m6_liquid_glass_effects)) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    when {
                                        blurDisabled -> Res.string.m6_liquid_glass_blur_overridden
                                        !refractionSupported -> Res.string.m6_liquid_glass_unsupported
                                        else -> Res.string.m6_liquid_glass_disabled
                                    }
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                },
            )
        }
        add(
            SettingsRow.Choice(
                "glass_intensity", Res.string.m6_liquid_glass_intensity, LiquidGlassIntensityKey,
                LiquidGlassTuningDefaults.intensity.name,
                listOf(
                    SettingsRow.Option(LiquidGlassIntensity.SUBTLE.name, Res.string.m6_liquid_glass_intensity_subtle),
                    SettingsRow.Option(LiquidGlassIntensity.STANDARD.name, Res.string.m6_liquid_glass_intensity_standard),
                    SettingsRow.Option(LiquidGlassIntensity.VIVID.name, Res.string.m6_liquid_glass_intensity_vivid),
                ),
                enabled = enabled,
                description = Res.string.m6_liquid_glass_intensity_desc,
            ),
        )
        add(SettingsRow.Header("liquid_glass_optics", Res.string.m6_liquid_glass_optics))
        add(
            SettingsRow.Decimal(
                "glass_refraction_height", Res.string.m6_liquid_glass_refraction_height,
                LiquidGlassRefractionHeightKey, LiquidGlassTuningDefaults.REFRACTION_HEIGHT, 0f..1f, 19,
                enabled = enabled, unit = SettingsUnit.PERCENT,
                description = Res.string.m6_liquid_glass_refraction_height_desc,
            ),
        )
        add(
            SettingsRow.Decimal(
                "glass_refraction_amount", Res.string.m6_liquid_glass_refraction_amount,
                LiquidGlassRefractionAmountKey, LiquidGlassTuningDefaults.REFRACTION_AMOUNT, 0f..1f, 19,
                enabled = enabled, unit = SettingsUnit.PERCENT,
                description = Res.string.m6_liquid_glass_refraction_amount_desc,
            ),
        )
        add(
            SettingsRow.Decimal(
                "glass_blur_radius", Res.string.m6_liquid_glass_blur_radius,
                LiquidGlassBlurRadiusKey, LiquidGlassTuningDefaults.BLUR_RADIUS, 0f..1f, 19,
                enabled = enabled, unit = SettingsUnit.PERCENT,
                description = Res.string.m6_liquid_glass_blur_radius_desc,
            ),
        )
        add(
            SettingsRow.Decimal(
                "glass_tint_opacity", Res.string.m6_liquid_glass_tint_opacity,
                LiquidGlassTintOpacityKey, LiquidGlassTuningDefaults.TINT_OPACITY,
                0f..LiquidGlassTuningDefaults.TINT_OPACITY_MAX,
                (LiquidGlassTuningDefaults.TINT_OPACITY_MAX * 20f).toInt() - 1,
                enabled = enabled, unit = SettingsUnit.PERCENT,
                description = Res.string.m6_liquid_glass_tint_opacity_desc,
            ),
        )
        add(
            SettingsRow.Decimal(
                "glass_shadow_depth", Res.string.m6_liquid_glass_shadow_depth,
                LiquidGlassShadowDepthKey, LiquidGlassTuningDefaults.SHADOW_DEPTH, 0f..1f, 19,
                enabled = enabled, unit = SettingsUnit.PERCENT,
                description = Res.string.m6_liquid_glass_shadow_depth_desc,
            ),
        )
        add(SettingsRow.Header("liquid_glass_quality", Res.string.m6_liquid_glass_quality))
        add(
            SettingsRow.Toggle(
                "glass_depth_3d", Res.string.m6_liquid_glass_depth_3d, LiquidGlassDepth3DKey,
                LiquidGlassTuningDefaults.DEPTH_3D, enabled = enabled,
                description = Res.string.m6_liquid_glass_depth_3d_desc,
            ),
        )
        add(
            SettingsRow.Toggle(
                "glass_chromatic_aberration", Res.string.m6_liquid_glass_chromatic_aberration,
                LiquidGlassChromaticAberrationKey, LiquidGlassTuningDefaults.CHROMATIC_ABERRATION,
                enabled = enabled, description = Res.string.m6_liquid_glass_chromatic_aberration_desc,
            ),
        )
        add(
            SettingsRow.Toggle(
                "glass_backdrop_vibrancy", Res.string.m6_liquid_glass_backdrop_vibrancy,
                LiquidGlassBackdropVibrancyKey, LiquidGlassTuningDefaults.BACKDROP_VIBRANCY,
                enabled = enabled, description = Res.string.m6_liquid_glass_backdrop_vibrancy_desc,
            ),
        )
        add(
            SettingsRow.Toggle(
                "glass_adaptive_luminance", Res.string.m6_liquid_glass_adaptive_luminance,
                LiquidGlassAdaptiveLuminanceKey, LiquidGlassTuningDefaults.ADAPTIVE_LUMINANCE,
                enabled = enabled, description = Res.string.m6_liquid_glass_adaptive_luminance_desc,
            ),
        )
        add(SettingsRow.Header("liquid_glass_reset_group", Res.string.m6_liquid_glass_reset_group))
        add(
            SettingsRow.Action(
                "glass_reset", Res.string.m6_liquid_glass_reset, enabled = enabled,
                description = Res.string.m6_liquid_glass_reset_desc,
                run = onReset,
            ),
        )
    }
}

/**
 * Settings > Appearance > Liquid Glass: the Android tuning page over the same persisted keys. The
 * host tells the page whether blur and refraction can draw, so the rows report why they are greyed
 * rather than silently doing nothing.
 */
@Composable
fun LiquidGlassSettings(
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
    val scope = rememberCoroutineScope()
    SettingsPage(
        rows = liquidGlassSettingsRows(preferences, blurSupported, refractionSupported) {
            scope.launch {
                try {
                    controller.edit {
                        this[LiquidGlassIntensityKey] = LiquidGlassTuningDefaults.intensity.name
                        this[LiquidGlassRefractionHeightKey] = LiquidGlassTuningDefaults.REFRACTION_HEIGHT
                        this[LiquidGlassRefractionAmountKey] = LiquidGlassTuningDefaults.REFRACTION_AMOUNT
                        this[LiquidGlassBlurRadiusKey] = LiquidGlassTuningDefaults.BLUR_RADIUS
                        this[LiquidGlassTintOpacityKey] = LiquidGlassTuningDefaults.TINT_OPACITY
                        this[LiquidGlassShadowDepthKey] = LiquidGlassTuningDefaults.SHADOW_DEPTH
                        this[LiquidGlassDepth3DKey] = LiquidGlassTuningDefaults.DEPTH_3D
                        this[LiquidGlassChromaticAberrationKey] = LiquidGlassTuningDefaults.CHROMATIC_ABERRATION
                        this[LiquidGlassBackdropVibrancyKey] = LiquidGlassTuningDefaults.BACKDROP_VIBRANCY
                        this[LiquidGlassAdaptiveLuminanceKey] = LiquidGlassTuningDefaults.ADAPTIVE_LUMINANCE
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    onFailure()
                }
            }
        },
        preferences = preferences,
        controller = controller,
        scrollTo = scrollTo,
        onFailure = onFailure,
        modifier = modifier,
    )
}
