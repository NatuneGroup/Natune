package moe.rukamori.archivetune.shared.domain.settings

enum class LiquidGlassIntensity { SUBTLE, STANDARD, VIVID }

/** Stored values for a fresh install. The sliders store fractions, not pixels. */
object LiquidGlassTuningDefaults {
    val intensity: LiquidGlassIntensity = LiquidGlassIntensity.STANDARD
    const val REFRACTION_HEIGHT: Float = 0.30f
    const val REFRACTION_AMOUNT: Float = 0.75f
    const val BLUR_RADIUS: Float = 0.65f
    const val TINT_OPACITY: Float = 1.40f
    const val SHADOW_DEPTH: Float = 0.45f
    const val DEPTH_3D: Boolean = true
    const val CHROMATIC_ABERRATION: Boolean = true
    const val BACKDROP_VIBRANCY: Boolean = true
    const val ADAPTIVE_LUMINANCE: Boolean = true

    /** The tint slider runs past 1 so a user can thicken the glass beyond the stock tint. */
    const val TINT_OPACITY_MAX: Float = 2f
}

/**
 * User tuning for every liquid glass surface. Each factor is relative to its default, so the stock
 * values reproduce the stock optics exactly and a slider at zero turns its effect off.
 */
data class LiquidGlassTuningValues(
    val intensity: LiquidGlassIntensity = LiquidGlassTuningDefaults.intensity,
    val refractionHeightFraction: Float = LiquidGlassTuningDefaults.REFRACTION_HEIGHT,
    val refractionAmountFraction: Float = LiquidGlassTuningDefaults.REFRACTION_AMOUNT,
    val blurFraction: Float = LiquidGlassTuningDefaults.BLUR_RADIUS,
    val tintFraction: Float = LiquidGlassTuningDefaults.TINT_OPACITY,
    val shadowFraction: Float = LiquidGlassTuningDefaults.SHADOW_DEPTH,
    val depth3D: Boolean = LiquidGlassTuningDefaults.DEPTH_3D,
    val chromaticAberration: Boolean = LiquidGlassTuningDefaults.CHROMATIC_ABERRATION,
    val backdropVibrancy: Boolean = LiquidGlassTuningDefaults.BACKDROP_VIBRANCY,
    val adaptiveLuminance: Boolean = LiquidGlassTuningDefaults.ADAPTIVE_LUMINANCE,
) {
    private val presetRefraction: Float
        get() = when (intensity) {
            LiquidGlassIntensity.SUBTLE -> 0.55f
            LiquidGlassIntensity.STANDARD -> 1f
            LiquidGlassIntensity.VIVID -> 1.45f
        }

    private val presetBlur: Float
        get() = when (intensity) {
            LiquidGlassIntensity.SUBTLE -> 0.70f
            LiquidGlassIntensity.STANDARD -> 1f
            LiquidGlassIntensity.VIVID -> 1.30f
        }

    val refractionHeightFactor: Float
        get() = relative(refractionHeightFraction, LiquidGlassTuningDefaults.REFRACTION_HEIGHT) * presetRefraction

    val refractionAmountFactor: Float
        get() = relative(refractionAmountFraction, LiquidGlassTuningDefaults.REFRACTION_AMOUNT) * presetRefraction

    val blurFactor: Float
        get() = relative(blurFraction, LiquidGlassTuningDefaults.BLUR_RADIUS) * presetBlur

    val tintFactor: Float
        get() = relative(tintFraction, LiquidGlassTuningDefaults.TINT_OPACITY)

    val shadowFactor: Float
        get() = relative(shadowFraction, LiquidGlassTuningDefaults.SHADOW_DEPTH)

    /** Colour-controls saturation for the sampled backdrop; vibrancy pushes it past 1. */
    val saturation: Float
        get() = if (backdropVibrancy) 1.7f else 1.0f

    companion object {
        val STOCK = LiquidGlassTuningValues()

        /** Unknown stored names fall back to the default rather than failing the whole surface. */
        fun intensityOf(stored: String?): LiquidGlassIntensity =
            LiquidGlassIntensity.entries.firstOrNull { it.name == stored } ?: LiquidGlassTuningDefaults.intensity

        // Stored floats come from backups too, so a negative or non-finite value must not reach the renderer.
        private fun relative(value: Float, default: Float): Float =
            if (value.isFinite()) value.coerceAtLeast(0f) / default else 1f
    }
}
