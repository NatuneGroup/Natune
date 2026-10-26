package moe.rukamori.archivetune.shared.domain.dsp.normalization

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DspNormalizationGainTest {
    @Test
    fun unknownOrNonFiniteMeasurementsPlayAtUnity() {
        assertEquals(1f, NormalizationGain.linearGain(trackGainDb = null, trackPeak = null))
        assertEquals(
            1f,
            NormalizationGain.linearGain(trackGainDb = Double.NaN, trackPeak = Double.POSITIVE_INFINITY, preampDb = Double.NaN),
        )
        assertEquals(1f, NormalizationGain.linearGain(trackGainDb = 0.0, trackPeak = 0.0))
    }

    @Test
    fun trackCorrectionAndPreampAddInDecibels() {
        assertEquals(10.0.pow(6.0 / 20.0).toFloat(), NormalizationGain.linearGain(trackGainDb = 6.0, trackPeak = null), 1e-6f)
        assertEquals(1f, NormalizationGain.linearGain(trackGainDb = -3.0, trackPeak = null, preampDb = 3.0), 1e-6f)
    }

    @Test
    fun preampAndTotalGainAreBounded() {
        assertEquals(
            10.0.pow(NormalizationGain.MAX_PREAMP_DB / 20.0).toFloat(),
            NormalizationGain.linearGain(trackGainDb = 0.0, trackPeak = null, preampDb = 40.0),
            1e-5f,
        )
        assertEquals(
            10.0.pow(NormalizationGain.MIN_GAIN_DB / 20.0).toFloat(),
            NormalizationGain.linearGain(trackGainDb = -60.0, trackPeak = null),
            1e-6f,
        )
        assertEquals(
            10.0.pow(NormalizationGain.MAX_GAIN_DB / 20.0).toFloat(),
            NormalizationGain.linearGain(trackGainDb = 60.0, trackPeak = null),
            1e-5f,
        )
    }

    @Test
    fun knownPeakLimitsTheGainSoTheTrackCannotClip() {
        val limited = NormalizationGain.linearGain(trackGainDb = 6.0, trackPeak = 0.9)
        assertEquals(1f / 0.9f, limited, 1e-5f)
        assertTrue(0.9f * limited <= 1f + 1e-6f)

        val headroom = NormalizationGain.linearGain(trackGainDb = 6.0, trackPeak = 0.3)
        assertEquals(10.0.pow(6.0 / 20.0).toFloat(), headroom, 1e-5f)
    }
}
