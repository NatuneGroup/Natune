package moe.rukamori.archivetune.shared.domain.dsp.clarity

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Studio Master Clarity as LastWave shipped it: the six-section contour and the four named trims. */
class DspClarityPresetTest {
    @Test
    fun referencePresetReproducesTheShippingContour() {
        val bands = ClarityPreset.REFERENCE.bands
        assertEquals(6, bands.size)
        assertEquals(ClarityFilterType.HIGH_PASS, bands[0].type)
        assertEquals(24.0, bands[0].frequencyHz)
        assertEquals(ClarityPreset.BUTTERWORTH_Q, bands[0].q)
        assertEquals(72.0, bands[1].frequencyHz)
        assertEquals(3.2, bands[1].gainDb)
        assertEquals(0.80, bands[1].q)
        assertEquals(280.0, bands[2].frequencyHz)
        assertEquals(-3.0, bands[2].gainDb)
        assertEquals(750.0, bands[3].frequencyHz)
        assertEquals(-1.4, bands[3].gainDb)
        assertEquals(3_400.0, bands[4].frequencyHz)
        assertEquals(3.8, bands[4].gainDb)
        assertEquals(ClarityFilterType.HIGH_SHELF, bands[5].type)
        assertEquals(10_500.0, bands[5].frequencyHz)
        assertEquals(4.8, bands[5].gainDb)
        assertEquals(0.85, bands[5].slope)
        assertEquals(1.0, ClarityPreset.REFERENCE.subsonicGain)
        assertEquals(1.0, ClarityPreset.REFERENCE.sideGain)
        assertEquals(0.18, ClarityPreset.REFERENCE.exciterAmount)
    }

    @Test
    fun namedPresetsTrimTheirOwnStagesOnly() {
        val speaker = ClarityPreset.SPEAKER.bands
        assertEquals(3.2 - 1.5, speaker[1].gainDb)
        assertEquals(4.8 - 0.5, speaker[5].gainDb)
        assertEquals(-3.0, speaker[2].gainDb)

        val headphone = ClarityPreset.HEADPHONE.bands
        assertEquals(3.2 - 0.5, headphone[1].gainDb)
        assertEquals(3.8 - 1.0, headphone[4].gainDb)
        assertEquals(4.8, headphone[5].gainDb)

        val dac = ClarityPreset.DAC
        // The DAC trims land on the air shelf and the exciter; its mono-bass side gain keeps the reference value.
        assertEquals(4.8 - 1.5, dac.bands[5].gainDb)
        assertEquals(1.0, dac.sideGain)
        assertTrue(dac.exciterAmount < 0.18)
        assertEquals(0.18 * 10.0.pow(-1.0 / 20.0), dac.exciterAmount)
    }

    @Test
    fun storedIndexesAndIdsResolveToTheSamePresets() {
        assertEquals(4, ClarityPreset.ALL.size)
        assertEquals(listOf("reference", "speaker", "headphone", "dac"), ClarityPreset.ALL.map { it.id })
        assertSame(ClarityPreset.REFERENCE, ClarityPreset.fromIndex(0))
        assertSame(ClarityPreset.SPEAKER, ClarityPreset.fromIndex(1))
        assertSame(ClarityPreset.HEADPHONE, ClarityPreset.fromIndex(2))
        assertSame(ClarityPreset.DAC, ClarityPreset.fromIndex(3))
        assertSame(ClarityPreset.REFERENCE, ClarityPreset.fromIndex(-1))
        assertSame(ClarityPreset.REFERENCE, ClarityPreset.fromIndex(9))
        assertSame(ClarityPreset.DAC, ClarityPreset.fromId("dac"))
        assertNull(ClarityPreset.fromId("bass-boost"))
        assertNull(ClarityPreset.fromId(null))
    }
}
