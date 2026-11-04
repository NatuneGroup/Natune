package moe.rukamori.archivetune.shared.domain.dsp.eq

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The fixed ten-octave graphic layout of the hosts that have no device band list: one peaking section per
 * centre with one octave of width. Android keeps its own device band list, so this layout is what the shared
 * DSP settings document stores for a host that has none.
 */
object GraphicEqualizer {
    /** The ten centres, one octave apart from 31.25 Hz to 16 kHz. */
    val BAND_CENTRES_HZ: List<Double> = listOf(
        31.25, 62.5, 125.0, 250.0, 500.0, 1_000.0, 2_000.0, 4_000.0, 8_000.0, 16_000.0,
    )

    /** One octave of bandwidth per band, the spacing both graphic equalizers used. */
    const val BAND_Q: Double = 1.4142135623730951

    const val MILLIBELS_PER_DB: Double = 100.0

    /** The widest level a band may store, the portable gain limit, so a stored level can never fail a band. */
    const val MAX_LEVEL_MB: Int = EqualizerBandLevels.MAX_LEVEL_MB

    /** A stored level has to name a centre this closely to count as this layout, not another host's bands. */
    private const val CENTRE_TOLERANCE_HZ = 0.5

    val BAND_COUNT: Int get() = BAND_CENTRES_HZ.size

    /** Every band at 0 dB, the level list a fresh equalizer holds. */
    fun flatLevelsMb(): List<Int> = List(BAND_CENTRES_HZ.size) { 0 }

    /** One peaking band per centre at [levelsMb]; a list shorter than the layout reads its missing bands as 0 dB. */
    fun bandsFromLevelsMb(levelsMb: List<Int>): List<EqualizerBand> =
        BAND_CENTRES_HZ.mapIndexed { index, centreHz ->
            EqualizerBand(
                type = EqualizerBandType.PEAKING,
                frequencyHz = centreHz,
                gainDb = levelMbOf(levelsMb.getOrNull(index) ?: 0) / MILLIBELS_PER_DB,
                q = BAND_Q,
            )
        }

    /**
     * The panel levels [bands] hold, or [flatLevelsMb] when the list is not this layout: another host stored
     * other bands, and a level per centre would then describe a different equalizer than the one playing.
     */
    fun levelsMbOf(bands: List<EqualizerBand>): List<Int> {
        if (bands.size != BAND_CENTRES_HZ.size) return flatLevelsMb()
        bands.forEachIndexed { index, band ->
            if (abs(band.frequencyHz - BAND_CENTRES_HZ[index]) > CENTRE_TOLERANCE_HZ) return flatLevelsMb()
        }
        return bands.map { levelMbOf(it.gainDb) }
    }

    /** The millibels one displayed gain stores. */
    fun levelMbOf(gainDb: Double): Int =
        (gainDb * MILLIBELS_PER_DB).roundToInt().coerceIn(-MAX_LEVEL_MB, MAX_LEVEL_MB)
}
