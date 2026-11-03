package moe.rukamori.archivetune.shared.domain.dsp.tempo

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PitchConversionTest {
    @Test
    fun unityMultiplierIsZeroCents() {
        assertEquals(0.0, PitchConversion.multiplierToSemitones(1.0), 1e-12)
        assertEquals(0.0, PitchConversion.multiplierToCents(1.0), 1e-12)
    }

    @Test
    fun octaveUpIsTwelveHundredCents() {
        assertEquals(12.0, PitchConversion.multiplierToSemitones(2.0), 1e-12)
        assertEquals(1200.0, PitchConversion.multiplierToCents(2.0), 1e-9)
    }

    @Test
    fun octaveDownIsMinusTwelveSemitones() {
        assertEquals(-12.0, PitchConversion.multiplierToSemitones(0.5), 1e-12)
        assertEquals(-1200.0, PitchConversion.multiplierToCents(0.5), 1e-9)
    }

    @Test
    fun dialogRangeStaysInsideThePitchUnitLimits() {
        // The tempo-and-pitch dialog allows 0.25..2.0; the iOS unit clamps to ±2400 cents.
        assertTrue(abs(PitchConversion.multiplierToCents(0.25)) <= 2400.0)
        assertTrue(abs(PitchConversion.multiplierToCents(2.0)) <= 2400.0)
    }

    @Test
    fun semitonesRoundTripThroughMultiplier() {
        for (semitones in listOf(-24.0, -12.0, -1.0, 0.0, 3.0, 7.5, 12.0)) {
            val multiplier = PitchConversion.semitonesToMultiplier(semitones)
            assertEquals(semitones, PitchConversion.multiplierToSemitones(multiplier), 1e-9)
        }
    }
}
