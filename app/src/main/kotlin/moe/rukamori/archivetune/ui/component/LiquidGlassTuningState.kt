package moe.rukamori.archivetune.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import moe.rukamori.archivetune.constants.LiquidGlassAdaptiveLuminanceKey
import moe.rukamori.archivetune.constants.LiquidGlassBackdropVibrancyKey
import moe.rukamori.archivetune.constants.LiquidGlassBlurRadiusKey
import moe.rukamori.archivetune.constants.LiquidGlassChromaticAberrationKey
import moe.rukamori.archivetune.constants.LiquidGlassDepth3DKey
import moe.rukamori.archivetune.constants.LiquidGlassIntensityKey
import moe.rukamori.archivetune.constants.LiquidGlassRefractionAmountKey
import moe.rukamori.archivetune.constants.LiquidGlassRefractionHeightKey
import moe.rukamori.archivetune.constants.LiquidGlassShadowDepthKey
import moe.rukamori.archivetune.constants.LiquidGlassTintOpacityKey
import moe.rukamori.archivetune.shared.domain.settings.LiquidGlassTuningDefaults
import moe.rukamori.archivetune.shared.domain.settings.LiquidGlassTuningValues
import moe.rukamori.archivetune.utils.PreferenceStore
import moe.rukamori.archivetune.utils.dataStore

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
