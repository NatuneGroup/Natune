/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.domain.dsp.eq

/** Shape of one equalizer band. Shelves tilt everything below or above their corner frequency. */
enum class EqualizerBandType { PEAKING, LOW_SHELF, HIGH_SHELF }

data class EqualizerBand(
    val type: EqualizerBandType,
    val frequencyHz: Double,
    val gainDb: Double,
    val q: Double = 1.0,
) {
    init {
        require(frequencyHz.isFinite() && frequencyHz > 0.0) { "frequency must be a positive number" }
        require(gainDb.isFinite() && gainDb in -MAX_GAIN_DB..MAX_GAIN_DB) { "gain must be within ±$MAX_GAIN_DB dB" }
        require(q.isFinite() && q > 0.0) { "q must be positive" }
    }

    companion object {
        const val MAX_GAIN_DB = 24.0
    }
}

/**
 * Cascade of biquad bands. A band at 0 dB is left out of the cascade, so a flat equalizer is not merely
 * close to transparent: [process] returns without touching the samples, which keeps bit-perfect exact.
 */
class PortableEqualizer(private val sampleRateHz: Int, private val channels: Int) {
    private val stages = ArrayList<BiquadFilter>()
    private var audibleBands: List<EqualizerBand> = emptyList()

    val isEngaged: Boolean
        get() = audibleBands.isNotEmpty()

    /**
     * Replaces the bands. When the band count is unchanged the existing stages keep their history and only
     * retune, so a slider drag does not click. A different count starts every stage from silence.
     */
    fun setBands(bands: List<EqualizerBand>) {
        val audible = bands.filter { it.gainDb != 0.0 }
        if (audible.size != stages.size) {
            stages.clear()
            repeat(audible.size) { stages.add(BiquadFilter(channels)) }
        }
        audible.forEachIndexed { index, band -> stages[index].setCoefficients(design(band)) }
        audibleBands = audible
    }

    fun process(samples: FloatArray, frames: Int) {
        if (!isEngaged) return
        for (stage in stages) stage.process(samples, frames)
    }

    fun reset() {
        stages.forEach { it.reset() }
    }

    private fun design(band: EqualizerBand): BiquadCoefficients =
        when (band.type) {
            EqualizerBandType.PEAKING -> BiquadDesign.peaking(sampleRateHz, band.frequencyHz, band.gainDb, band.q)
            EqualizerBandType.LOW_SHELF -> BiquadDesign.lowShelf(sampleRateHz, band.frequencyHz, band.gainDb, band.q)
            EqualizerBandType.HIGH_SHELF -> BiquadDesign.highShelf(sampleRateHz, band.frequencyHz, band.gainDb, band.q)
        }
}
