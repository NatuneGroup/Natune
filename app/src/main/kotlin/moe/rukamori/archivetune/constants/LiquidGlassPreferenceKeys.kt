package moe.rukamori.archivetune.constants

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

// Live liquid glass tuning, read on every frame that draws glass, so none of these is restart-scoped.
// Defaults live in shared-domain LiquidGlassTuningDefaults.
val LiquidGlassIntensityKey = stringPreferencesKey("liquidGlassIntensity")
val LiquidGlassRefractionHeightKey = floatPreferencesKey("liquidGlassRefractionHeight")
val LiquidGlassRefractionAmountKey = floatPreferencesKey("liquidGlassRefractionAmount")
val LiquidGlassBlurRadiusKey = floatPreferencesKey("liquidGlassBlurRadius")
val LiquidGlassTintOpacityKey = floatPreferencesKey("liquidGlassTintOpacity")
val LiquidGlassShadowDepthKey = floatPreferencesKey("liquidGlassShadowDepth")
val LiquidGlassDepth3DKey = booleanPreferencesKey("liquidGlassDepth3D")
val LiquidGlassChromaticAberrationKey = booleanPreferencesKey("liquidGlassChromaticAberration")
val LiquidGlassBackdropVibrancyKey = booleanPreferencesKey("liquidGlassBackdropVibrancy")
val LiquidGlassAdaptiveLuminanceKey = booleanPreferencesKey("liquidGlassAdaptiveLuminance")
