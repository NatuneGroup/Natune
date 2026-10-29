package moe.rukamori.archivetune.shared.domain.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiquidGlassTuningValuesTest {
    @Test
    fun stockValuesReproduceTheStockOptics() {
        val stock = LiquidGlassTuningValues.STOCK

        assertEquals(1f, stock.refractionHeightFactor, 1e-6f)
        assertEquals(1f, stock.refractionAmountFactor, 1e-6f)
        assertEquals(1f, stock.blurFactor, 1e-6f)
        assertEquals(1f, stock.tintFactor, 1e-6f)
        assertEquals(1f, stock.shadowFactor, 1e-6f)
        assertEquals(1.7f, stock.saturation, 1e-6f)
        assertTrue(stock.depth3D && stock.chromaticAberration && stock.adaptiveLuminance)
    }

    @Test
    fun intensityPresetsScaleRefractionAndBlur() {
        val subtle = LiquidGlassTuningValues(intensity = LiquidGlassIntensity.SUBTLE)
        val vivid = LiquidGlassTuningValues(intensity = LiquidGlassIntensity.VIVID)

        assertEquals(0.55f, subtle.refractionHeightFactor, 1e-6f)
        assertEquals(0.70f, subtle.blurFactor, 1e-6f)
        assertEquals(1.45f, vivid.refractionAmountFactor, 1e-6f)
        assertEquals(1.30f, vivid.blurFactor, 1e-6f)
        assertEquals(1f, vivid.tintFactor, 1e-6f)
    }

    @Test
    fun slidersAreRelativeToTheirDefaults() {
        val doubled = LiquidGlassTuningValues(blurFraction = 1.30f, tintFraction = 0f)

        assertEquals(2f, doubled.blurFactor, 1e-6f)
        assertEquals(0f, doubled.tintFactor, 1e-6f)
        assertFalse(LiquidGlassTuningValues(backdropVibrancy = false).saturation > 1f)
    }

    @Test
    fun corruptStoredValuesFallBackSafely() {
        val corrupt = LiquidGlassTuningValues(blurFraction = Float.NaN, shadowFraction = -1f)

        assertEquals(1f, corrupt.blurFactor, 1e-6f)
        assertEquals(0f, corrupt.shadowFactor, 1e-6f)
        assertEquals(LiquidGlassIntensity.STANDARD, LiquidGlassTuningValues.intensityOf("bogus"))
        assertEquals(LiquidGlassIntensity.VIVID, LiquidGlassTuningValues.intensityOf("VIVID"))
    }

    @Test
    fun tuningKeysKeepTheirPersistedNames() {
        assertEquals("liquidGlassIntensity", LiquidGlassIntensityKey.name)
        assertEquals("liquidGlassRefractionHeight", LiquidGlassRefractionHeightKey.name)
        assertEquals("liquidGlassRefractionAmount", LiquidGlassRefractionAmountKey.name)
        assertEquals("liquidGlassBlurRadius", LiquidGlassBlurRadiusKey.name)
        assertEquals("liquidGlassTintOpacity", LiquidGlassTintOpacityKey.name)
        assertEquals("liquidGlassShadowDepth", LiquidGlassShadowDepthKey.name)
        assertEquals("liquidGlassDepth3D", LiquidGlassDepth3DKey.name)
        assertEquals("liquidGlassChromaticAberration", LiquidGlassChromaticAberrationKey.name)
        assertEquals("liquidGlassBackdropVibrancy", LiquidGlassBackdropVibrancyKey.name)
        assertEquals("liquidGlassAdaptiveLuminance", LiquidGlassAdaptiveLuminanceKey.name)
        assertEquals(PreferenceKind.STRING, LiquidGlassIntensityKey.kind)
        assertEquals(PreferenceKind.FLOAT, LiquidGlassTintOpacityKey.kind)
        assertEquals(PreferenceKind.BOOLEAN, LiquidGlassDepth3DKey.kind)
        assertTrue(PersistedPreferenceKeys.contains(LiquidGlassAdaptiveLuminanceKey))
    }
}
