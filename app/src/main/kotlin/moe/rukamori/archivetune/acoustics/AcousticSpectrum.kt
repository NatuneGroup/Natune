package moe.rukamori.archivetune.acoustics

import kotlin.math.log10
import kotlin.math.pow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * One loudness value per frequency band for the spectrum graph. The heat grid's time columns are
 * averaged in linear power, like [AcousticHeatRaster], so a short loud passage is not diluted into
 * the noise floor. The band count stays small so the path built from it is cheap to draw and the
 * curve reads as one smooth shape instead of per-bin hair.
 */
internal class AcousticSpectrum(
    /** Normalized loudness in [0, 1] per band, lowest frequency first. Empty when the grid is unusable. */
    val bands: FloatArray,
    /** Frequency at the right edge of the graph (half the decoded sample rate). */
    val nyquistHz: Float,
) {
    companion object {
        const val BAND_COUNT = 160
        private val SMOOTH_WEIGHTS = floatArrayOf(1f, 4f, 6f, 4f, 1f)

        /** Spectrum of [result], or an empty spectrum when the grid does not match its dimensions. */
        suspend fun of(
            result: AcousticAuditResult,
            bandCount: Int = BAND_COUNT,
        ): AcousticSpectrum {
            val srcWidth = result.width
            val srcHeight = result.height
            val heat = result.magnitudeDb
            if (bandCount <= 0 || srcWidth <= 0 || srcHeight <= 0 ||
                srcWidth.toLong() * srcHeight != heat.size.toLong()
            ) {
                return AcousticSpectrum(FloatArray(0), result.nyquistHz)
            }
            val power = DoubleArray(bandCount)
            val count = IntArray(bandCount)
            for (row in 0 until srcHeight) {
                currentCoroutineContext().ensureActive()
                val band = (row.toLong() * bandCount / srcHeight).toInt()
                val base = row * srcWidth
                var rowPower = 0.0
                var rowCells = 0
                for (column in 0 until srcWidth) {
                    val value = heat[base + column]
                    if (value.isFinite()) {
                        rowPower += 10.0.pow(value / 10.0)
                        rowCells++
                    }
                }
                power[band] += rowPower
                count[band] += rowCells
            }
            val bands =
                FloatArray(bandCount) { band ->
                    if (count[band] == 0) {
                        0f
                    } else {
                        AcousticHeatPalette.intensity(
                            (10.0 * log10(power[band] / count[band])).toFloat(),
                        )
                    }
                }
            smooth(bands)
            return AcousticSpectrum(bands, result.nyquistHz)
        }

        /**
         * One binomial smoothing pass so bin-to-bin noise does not make the drawn curve hairy.
         * A real shelf stays a steep drop: the kernel is five bands wide, a few percent of the axis.
         */
        private fun smooth(bands: FloatArray) {
            if (bands.size < SMOOTH_WEIGHTS.size) return
            val source = bands.copyOf()
            val radius = SMOOTH_WEIGHTS.size / 2
            for (i in bands.indices) {
                var weighted = 0f
                var weight = 0f
                for (tap in SMOOTH_WEIGHTS.indices) {
                    val j = i + tap - radius
                    if (j in bands.indices) {
                        weighted += source[j] * SMOOTH_WEIGHTS[tap]
                        weight += SMOOTH_WEIGHTS[tap]
                    }
                }
                bands[i] = weighted / weight
            }
        }
    }
}
