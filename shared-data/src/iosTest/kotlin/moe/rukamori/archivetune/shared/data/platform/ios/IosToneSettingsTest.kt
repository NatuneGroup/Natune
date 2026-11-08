package moe.rukamori.archivetune.shared.data.platform.ios

import kotlin.test.Test
import kotlin.test.assertEquals
import moe.rukamori.archivetune.shared.domain.dsp.DspSettings
import moe.rukamori.archivetune.shared.domain.dsp.clarity.ClarityPreset
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBand
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBandType

class IosToneSettingsTest {
    @Test fun clarityAndDcRemovalSurviveUserEqualizerOff() {
        val settings = DspSettings(equalizerEnabled = false, clarity = ClarityPreset.REFERENCE,
            dcBlockerEnabled = true, equalizerBands = listOf(EqualizerBand(EqualizerBandType.PEAKING, 1000.0, 3.0)))
        val bands = settings.toIosEqBands()
        assertEquals(7, bands.size)
        assertEquals(3, bands.first().type)
        assertEquals(5f, bands.last().frequencyHz)
        assertEquals(3, bands.last().type)
    }

    @Test fun allUserBandsFitBesideTheContour() {
        val settings = DspSettings(equalizerBands = List(16) { EqualizerBand(EqualizerBandType.PEAKING, 1000.0, 3.0) },
            clarity = ClarityPreset.REFERENCE, dcBlockerEnabled = true)
        assertEquals(23, settings.toIosEqBands().size)
        assertEquals(16, settings.toIosEqBands().count { it.frequencyHz == 1000f })
        assertEquals(emptyList(), settings.withoutProcessing().toIosEqBands())
    }

    @Test fun normalizationAndPreampRemainIndependent() {
        assertEquals(-6f, DspSettings(preampDb = -6.0, normalizationEnabled = false).iosInputGainDb)
        assertEquals(0f, DspSettings(preampDb = -6.0, preampEnabled = false).iosInputGainDb)
        assertEquals(24f, DspSettings(preampDb = 12.0, linearGain = 100f).iosInputGainDb)
    }
}
