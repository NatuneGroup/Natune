package moe.rukamori.archivetune.shared.domain.dsp.eq

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EqualizerBandwidthTest {
    @Test
    fun theSharedBandQIsExactlyOneOctave() {
        // The shared 10-band layout uses Q=sqrt(2); the standard convention makes that exactly 1 octave.
        assertEquals(1.0, EqualizerBandwidth.octavesForQ(1.4142135623730951), 1e-12)
        assertEquals(1.3885, EqualizerBandwidth.octavesForQ(1.0), 1e-3)
    }

    @Test
    fun widerBandMeansLowerQ() {
        assertEquals(1.41421356, EqualizerBandwidth.qForOctaves(1.0), 1e-8)
        assertEquals(0.6667, EqualizerBandwidth.qForOctaves(2.0), 1e-3)
    }

    @Test
    fun conversionRoundTripsAcrossTheBandLayout() {
        // The shared 10-band layout uses Q=1.414; the full supported Q range must round-trip.
        for (q in listOf(0.1, 0.5, 0.7071, 1.0, 1.414, 4.0, 12.0)) {
            assertEquals(q, EqualizerBandwidth.qForOctaves(EqualizerBandwidth.octavesForQ(q)), 1e-9)
        }
    }

    @Test
    fun bandTypeOrderMatchesTheNativeEnum() {
        // IosEqBand packs PEAKING/LOW_SHELF/HIGH_SHELF as 0/1/2 for the at_eq_band ABI.
        assertEquals(0, EqualizerBandType.PEAKING.ordinal)
        assertEquals(1, EqualizerBandType.LOW_SHELF.ordinal)
        assertEquals(2, EqualizerBandType.HIGH_SHELF.ordinal)
    }

    @Test
    fun invalidQAndOctavesAreRejected() {
        assertFailsWith<IllegalArgumentException> { EqualizerBandwidth.octavesForQ(0.0) }
        assertFailsWith<IllegalArgumentException> { EqualizerBandwidth.octavesForQ(-1.0) }
        assertFailsWith<IllegalArgumentException> { EqualizerBandwidth.qForOctaves(0.0) }
    }
}
