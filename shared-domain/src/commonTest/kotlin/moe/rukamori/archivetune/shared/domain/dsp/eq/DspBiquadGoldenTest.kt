package moe.rukamori.archivetune.shared.domain.dsp.eq

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins the biquad designs against the cookbook response table in [DspBiquadGoldenVectors]. The coefficients
 * and the magnitudes are both checked, so a design that keeps the same shape but moves a pole still fails.
 */
class DspBiquadGoldenTest {
    private val rate = DspBiquadGoldenVectors.SAMPLE_RATE_HZ

    private fun design(name: String): BiquadCoefficients = when (name) {
        "LOW_PASS_1000_Q_07071" -> BiquadDesign.lowPass(rate, 1_000.0, BiquadDesign.BUTTERWORTH_Q)
        "HIGH_PASS_100_Q_07071" -> BiquadDesign.highPass(rate, 100.0, BiquadDesign.BUTTERWORTH_Q)
        "PEAKING_1000_PLUS_6DB_Q_1" -> BiquadDesign.peaking(rate, 1_000.0, 6.0, 1.0)
        "PEAKING_250_MINUS_4_5DB_Q_0_9" -> BiquadDesign.peaking(rate, 250.0, -4.5, 0.9)
        "LOW_SHELF_200_PLUS_4DB_Q_07071" -> BiquadDesign.lowShelf(rate, 200.0, 4.0, BiquadDesign.BUTTERWORTH_Q)
        "HIGH_SHELF_5000_MINUS_3DB_Q_07071" -> BiquadDesign.highShelf(rate, 5_000.0, -3.0, BiquadDesign.BUTTERWORTH_Q)
        "HIGH_SHELF_SLOPE_10500_PLUS_4_8DB_S_085" -> BiquadDesign.highShelfSlope(rate, 10_500.0, 4.8, 0.85)
        else -> error("no design for $name")
    }

    @Test
    fun coefficientsMatchTheCookbookGoldenValues() {
        for (case in DspBiquadGoldenVectors.cases) {
            val actual = design(case.name)
            val expected = case.coefficients
            assertClose(case.name, "b0", actual.b0, expected.b0)
            assertClose(case.name, "b1", actual.b1, expected.b1)
            assertClose(case.name, "b2", actual.b2, expected.b2)
            assertClose(case.name, "a1", actual.a1, expected.a1)
            assertClose(case.name, "a2", actual.a2, expected.a2)
        }
    }

    @Test
    fun magnitudeResponseMatchesTheGoldenTable() {
        for (case in DspBiquadGoldenVectors.cases) {
            val coefficients = design(case.name)
            DspBiquadGoldenVectors.frequenciesHz.forEachIndexed { index, frequencyHz ->
                val actual = BiquadDesign.magnitude(coefficients, rate, frequencyHz)
                val expected = case.magnitudes[index]
                assertTrue(
                    abs(actual - expected) <= DspBiquadGoldenVectors.TOLERANCE,
                    "${case.name} at ${frequencyHz} Hz: expected $expected, got $actual",
                )
            }
        }
    }

    @Test
    fun goldenTableHoldsEveryDesignItNames() {
        assertTrue(DspBiquadGoldenVectors.cases.size >= 7)
        for (case in DspBiquadGoldenVectors.cases) {
            assertTrue(case.magnitudes.size == DspBiquadGoldenVectors.frequenciesHz.size, case.name)
            design(case.name)
        }
    }

    private fun assertClose(name: String, field: String, actual: Double, expected: Double) {
        assertTrue(
            abs(actual - expected) <= DspBiquadGoldenVectors.TOLERANCE,
            "$name $field: expected $expected, got $actual",
        )
    }
}
