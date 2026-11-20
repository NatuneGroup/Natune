package app.natune.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import app.natune.constants.LiquidGlassAdaptiveLuminanceKey
import app.natune.constants.LiquidGlassBackdropVibrancyKey
import app.natune.constants.LiquidGlassBlurRadiusKey
import app.natune.constants.LiquidGlassChromaticAberrationKey
import app.natune.constants.LiquidGlassDepth3DKey
import app.natune.constants.LiquidGlassIntensityKey
import app.natune.constants.LiquidGlassRefractionAmountKey
import app.natune.constants.LiquidGlassRefractionHeightKey
import app.natune.constants.LiquidGlassShadowDepthKey
import app.natune.constants.LiquidGlassTintOpacityKey
import app.natune.shared.domain.settings.LiquidGlassTuningDefaults
import app.natune.shared.domain.settings.LiquidGlassTuningValues
import app.natune.utils.PreferenceStore
import app.natune.utils.dataStore

internal fun liquidGlassTuningOf(prefs: Preferences): LiquidGlassTuning =
    LiquidGlassTuningValues(
        intensity = LiquidGlassTuningValues.intensityOf(prefs[LiquidGlassIntensityKey]),
        refractionHeightFraction = prefs[LiquidGlassRefractionHeightKey] ?: LiquidGlassTuningDefaults.REFRACTION_HEIGHT,
        refractionAmountFraction = prefs[LiquidGlassRefractionAmountKey] ?: LiquidGlassTuningDefaults.REFRACTION_AMOUNT,
        blurFraction = prefs[LiquidGlassBlurRadiusKey] ?: LiquidGlassTuningDefaults.BLUR_RADIUS,
        tintFraction = prefs[LiquidGlassTintOpacityKey] ?: LiquidGlassTuningDefaults.TINT_OPACITY,
        shadowFraction = prefs[LiquidGlassShadowDepthKey] ?: LiquidGlassTuningDefaults.SHADOW_DEPTH,
        depth3D = prefs[LiquidGlassDepth3DKey] ?: LiquidGlassTuningDefaults.DEPTH_3D,
        chromaticAberration = prefs[LiquidGlassChromaticAberrationKey] ?: LiquidGlassTuningDefaults.CHROMATIC_ABERRATION,
        backdropVibrancy = prefs[LiquidGlassBackdropVibrancyKey] ?: LiquidGlassTuningDefaults.BACKDROP_VIBRANCY,
        adaptiveLuminance = prefs[LiquidGlassAdaptiveLuminanceKey] ?: LiquidGlassTuningDefaults.ADAPTIVE_LUMINANCE,
    )

/**
 * The live tuning for the whole app, read once at the root and handed down through
 * [LocalLiquidGlassTuning]. One collector instead of ten keeps the root from recomposing per key.
 */
@Composable
fun rememberLiquidGlassTuning(): LiquidGlassTuning {
    val context = LocalContext.current
    val initial = remember { PreferenceStore.snapshot()?.let(::liquidGlassTuningOf) ?: LiquidGlassTuning.STOCK }
    val tuning by remember(context) {
        context.dataStore.data.map(::liquidGlassTuningOf).distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = initial)
    return tuning
}
