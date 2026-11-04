package moe.rukamori.archivetune.shared.domain.dsp.clarity

import kotlin.math.pow

/** The filter shapes the clarity contour is built from. It needs a high-pass, which the user equalizer does not. */
enum class ClarityFilterType { HIGH_PASS, PEAKING, HIGH_SHELF }

/**
 * One section of a clarity preset. [slope] is the RBJ shelf slope and only [ClarityFilterType.HIGH_SHELF]
 * reads it; [q] shapes the peaking and high-pass sections.
 */
data class ClarityBand(
    val type: ClarityFilterType,
    val frequencyHz: Double,
    val gainDb: Double,
    val q: Double,
    val slope: Double = DEFAULT_SLOPE,
) {
    init {
        require(frequencyHz.isFinite() && frequencyHz > 0.0) { "frequency must be a positive number" }
        require(gainDb.isFinite() && gainDb in -MAX_GAIN_DB..MAX_GAIN_DB) { "gain must be within ±$MAX_GAIN_DB dB" }
        require(q.isFinite() && q > 0.0) { "q must be positive" }
        require(slope.isFinite() && slope in MIN_SLOPE..1.0) { "slope must be within $MIN_SLOPE..1.0" }
    }

    companion object {
        const val MAX_GAIN_DB = 24.0
        const val DEFAULT_SLOPE = 1.0
        const val MIN_SLOPE = 0.1
    }
}

/**
 * Studio Master Clarity, ported from the LastWave native chain (`lastwave/DspProcessor.cpp`) into one
 * portable preset: a fixed six-section contour whose design gains each named preset trims, plus the
 * makeup, width and exciter amounts the native chain applies.
 *
 * [trimsDb] holds the native per-stage trims in their documented order: 0 subsonic high-pass post gain,
 * 1 bass foundation, 2 low-mid separation, 3 boxiness control, 4 presence detail, 5 air shelf, 6 mono-bass
 * side gain, 7 exciter amount. Reference is all zeros, so it reproduces the shipping curve exactly.
 */
data class ClarityPreset(
    val id: String,
    val displayName: String,
    val trimsDb: List<Double>,
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require(trimsDb.size == TRIM_COUNT) { "trimsDb must hold $TRIM_COUNT stages" }
        require(trimsDb.all { it.isFinite() && it in MIN_TRIM_DB..MAX_TRIM_DB }) {
            "every trim must be within ±$MAX_TRIM_DB dB"
        }
    }

    /** The six contour sections with this preset's trims already added to their design gains. */
    val bands: List<ClarityBand> = listOf(
        ClarityBand(ClarityFilterType.HIGH_PASS, SUBSONIC_HZ, 0.0, BUTTERWORTH_Q),
        ClarityBand(ClarityFilterType.PEAKING, BASS_HZ, BASS_GAIN_DB + trimsDb[1], BASS_Q),
        ClarityBand(ClarityFilterType.PEAKING, LOW_MID_HZ, LOW_MID_GAIN_DB + trimsDb[2], LOW_MID_Q),
        ClarityBand(ClarityFilterType.PEAKING, BOXINESS_HZ, BOXINESS_GAIN_DB + trimsDb[3], BOXINESS_Q),
        ClarityBand(ClarityFilterType.PEAKING, PRESENCE_HZ, PRESENCE_GAIN_DB + trimsDb[4], PRESENCE_Q),
        ClarityBand(ClarityFilterType.HIGH_SHELF, AIR_HZ, AIR_GAIN_DB + trimsDb[5], AIR_Q, AIR_SLOPE),
    )

    /** Linear post gain after the subsonic high-pass. Exactly unity for the neutral trim. */
    val subsonicGain: Double
        get() = linearGain(trimsDb[0])

    /** Linear post gain on the side channel before the width multiply. */
    val sideGain: Double
        get() = linearGain(trimsDb[6])

    /** Exciter level after its own trim; the neutral trim keeps the shipping 0.18. */
    val exciterAmount: Double
        get() = EXCITER_AMOUNT * linearGain(trimsDb[7])

    private fun linearGain(trimDb: Double): Double = if (trimDb == 0.0) 1.0 else 10.0.pow(trimDb / 20.0)

    companion object {
        const val TRIM_COUNT = 8
        const val MIN_TRIM_DB = -12.0
        const val MAX_TRIM_DB = 12.0

        const val BUTTERWORTH_Q = 0.7071067811865476
        const val SUBSONIC_HZ = 24.0
        const val BASS_HZ = 72.0
        const val BASS_GAIN_DB = 3.2
        const val BASS_Q = 0.80
        const val LOW_MID_HZ = 280.0
        const val LOW_MID_GAIN_DB = -3.0
        const val LOW_MID_Q = 0.90
        const val BOXINESS_HZ = 750.0
        const val BOXINESS_GAIN_DB = -1.4
        const val BOXINESS_Q = 0.85
        const val PRESENCE_HZ = 3_400.0
        const val PRESENCE_GAIN_DB = 3.8
        const val PRESENCE_Q = 0.85
        const val AIR_HZ = 10_500.0
        const val AIR_GAIN_DB = 4.8
        const val AIR_Q = 0.85
        const val AIR_SLOPE = 0.85
        const val MONO_BASS_HZ = 130.0
        const val EXCITER_HZ = 6_000.0
        const val EXCITER_AMOUNT = 0.18
        const val MAKEUP_GAIN = 1.04
        const val STEREO_WIDTH = 1.22

        val REFERENCE: ClarityPreset = ClarityPreset(
            id = "reference",
            displayName = "Reference",
            trimsDb = listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
        )

        val SPEAKER: ClarityPreset = ClarityPreset(
            id = "speaker",
            displayName = "Speaker",
            trimsDb = listOf(0.0, -1.5, 0.0, 0.0, 0.0, -0.5, 0.0, 0.0),
        )

        val HEADPHONE: ClarityPreset = ClarityPreset(
            id = "headphone",
            displayName = "Headphone",
            trimsDb = listOf(0.0, -0.5, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0),
        )

        val DAC: ClarityPreset = ClarityPreset(
            id = "dac",
            displayName = "DAC",
            trimsDb = listOf(0.0, 0.0, 0.0, 0.0, 0.0, -1.5, 0.0, -1.0),
        )

        val ALL: List<ClarityPreset> = listOf(REFERENCE, SPEAKER, HEADPHONE, DAC)

        /** The stored `lw_clarity_preset` index; anything outside 0..3 reads as Reference, as the native chain did. */
        fun fromIndex(index: Int): ClarityPreset = ALL.getOrNull(index) ?: REFERENCE

        fun fromId(id: String?): ClarityPreset? = ALL.firstOrNull { it.id == id }
    }
}
