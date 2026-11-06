package moe.rukamori.archivetune.desktop.playback

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.dsp.eq.BiquadCoefficients
import moe.rukamori.archivetune.shared.domain.dsp.eq.BiquadDesign
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBand
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBandType
import moe.rukamori.archivetune.shared.domain.dsp.eq.GraphicEqualizer

/**
 * Pins what the desktop host hands the native mixer to the shared design. The mixer only runs biquads, so the
 * packed layout and the coefficients are the whole contract between the two: a design, a band order or a stride
 * that quietly changes here would retune the desktop equalizer without any other test failing.
 *
 * The two peaking expectations are the Audio EQ Cookbook values the shared golden vectors pin at the same
 * parameters, reproduced because a commonTest fixture is not visible from another module. The shelves compare
 * against [BiquadDesign] itself, whose own golden vectors cover their shape.
 */
class DesktopEqualizerCoefficientsTest {
    private val sampleRateHz = 48_000
    private val tolerance = 1e-6

    private fun section(
        type: EqualizerBandType,
        frequencyHz: Double,
        gainDb: Double,
        q: Double,
    ): EqualizerBand = EqualizerBand(type = type, frequencyHz = frequencyHz, gainDb = gainDb, q = q)

    private fun assertSection(expected: BiquadCoefficients, packed: DoubleArray, offset: Int) {
        assertEquals(expected.b0, packed[offset], tolerance, "b0 at $offset")
        assertEquals(expected.b1, packed[offset + 1], tolerance, "b1 at $offset")
        assertEquals(expected.b2, packed[offset + 2], tolerance, "b2 at $offset")
        assertEquals(expected.a1, packed[offset + 3], tolerance, "a1 at $offset")
        assertEquals(expected.a2, packed[offset + 4], tolerance, "a2 at $offset")
    }

    @Test
    fun aPeakingBandPacksTheGoldenSection() {
        val packed = equalizerCoefficients(sampleRateHz, listOf(section(EqualizerBandType.PEAKING, 1_000.0, 6.0, 1.0)))

        // DspBiquadGoldenVectors.PEAKING_1000_PLUS_6DB_Q_1, at the same 48 kHz rate.
        assertEquals(5, packed.size)
        assertEquals(1.043953086990335, packed[0], tolerance)
        assertEquals(-1.8953207239365961, packed[1], tolerance)
        assertEquals(0.8677222847598566, packed[2], tolerance)
        assertEquals(-1.8953207239365961, packed[3], tolerance)
        assertEquals(0.9116753717501915, packed[4], tolerance)
    }

    @Test
    fun aCuttingPeakingBandPacksTheGoldenSection() {
        val packed = equalizerCoefficients(sampleRateHz, listOf(section(EqualizerBandType.PEAKING, 250.0, -4.5, 0.9)))

        // DspBiquadGoldenVectors.PEAKING_250_MINUS_4_5DB_Q_0_9.
        assertEquals(5, packed.size)
        assertEquals(0.9906961416388775, packed[0], tolerance)
        assertEquals(-1.9529335957111675, packed[1], tolerance)
        assertEquals(0.9632836393179424, packed[2], tolerance)
        assertEquals(-1.9529335957111675, packed[3], tolerance)
        assertEquals(0.95397978095682, packed[4], tolerance)
    }

    @Test
    fun everyBandKeepsTheSectionOfTheSharedDesign() {
        val bands = listOf(
            section(EqualizerBandType.PEAKING, 1_000.0, 6.0, 1.0),
            section(EqualizerBandType.LOW_SHELF, 200.0, 4.0, BiquadDesign.BUTTERWORTH_Q),
            section(EqualizerBandType.HIGH_SHELF, 5_000.0, -3.0, BiquadDesign.BUTTERWORTH_Q),
        )

        val packed = equalizerCoefficients(sampleRateHz, bands)

        assertEquals(bands.size * 5, packed.size)
        assertSection(BiquadDesign.peaking(sampleRateHz, 1_000.0, 6.0, 1.0), packed, 0)
        assertSection(BiquadDesign.lowShelf(sampleRateHz, 200.0, 4.0, BiquadDesign.BUTTERWORTH_Q), packed, 5)
        assertSection(BiquadDesign.highShelf(sampleRateHz, 5_000.0, -3.0, BiquadDesign.BUTTERWORTH_Q), packed, 10)
    }

    @Test
    fun aFlatEqualizerPacksNothing() {
        val flat = GraphicEqualizer.bandsFromLevelsMb(GraphicEqualizer.flatLevelsMb())

        assertContentEquals(DoubleArray(0), equalizerCoefficients(sampleRateHz, flat))
    }

    @Test
    fun theGraphicLayoutKeepsEveryBandIncludingZeroDecibels() {
        val levels = GraphicEqualizer.flatLevelsMb().toMutableList().also { it[3] = 600 }
        val packed = equalizerCoefficients(sampleRateHz, GraphicEqualizer.bandsFromLevelsMb(levels))

        // A drag through 0 dB must not change the section count, or the mixer would reset its filter history.
        assertEquals(GraphicEqualizer.BAND_COUNT * 5, packed.size)
        assertSection(BiquadDesign.peaking(sampleRateHz, 250.0, 6.0, GraphicEqualizer.BAND_Q), packed, 3 * 5)
        assertSection(BiquadDesign.peaking(sampleRateHz, 31.25, 0.0, GraphicEqualizer.BAND_Q), packed, 0)
    }

    @Test
    fun theMixerRefusesMoreBandsThanItHolds() {
        val bands = List(NativeTwoDeckMixer.MAX_BANDS + 1) {
            section(EqualizerBandType.PEAKING, 1_000.0, 1.0, 1.0)
        }

        assertFailsWith<IllegalArgumentException> { equalizerCoefficients(sampleRateHz, bands) }
    }

    @Test
    fun aResampledDesignNeverLeavesThePackedArray() {
        // Every section is normalized, so no coefficient is infinite even at the band edges of the layout.
        val bands = GraphicEqualizer.bandsFromLevelsMb(GraphicEqualizer.flatLevelsMb().toMutableList().also { it[9] = 2_400 })

        val packed = equalizerCoefficients(48_000, bands)

        assertTrue(packed.all { it.isFinite() })
    }
}
