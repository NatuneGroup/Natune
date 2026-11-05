package moe.rukamori.archivetune.shared.domain.dsp.eq

/**
 * Shape of one equalizer band. Shelves tilt everything below or above their corner frequency.
 *
 * The high-pass shape is deliberately absent: the user equalizer stays with the three shapes every host can
 * present, and the clarity preset carries its own fixed contour.
 */
enum class EqualizerBandType { PEAKING, LOW_SHELF, HIGH_SHELF }

/**
 * One equalizer band. [rightFrequencyHz], [rightGainDb] and [rightQ] override the design for the right
 * channel of each stereo pair, which is how a host corrects drivers that do not match; a band without them
 * filters both channels identically.
 */
data class EqualizerBand(
    val type: EqualizerBandType,
    val frequencyHz: Double,
    val gainDb: Double,
    val q: Double = 1.0,
    val rightFrequencyHz: Double? = null,
    val rightGainDb: Double? = null,
    val rightQ: Double? = null,
) {
    init {
        require(frequencyHz.isFinite() && frequencyHz > 0.0) { "frequency must be a positive number" }
        require(gainDb.isFinite() && gainDb in -MAX_GAIN_DB..MAX_GAIN_DB) { "gain must be within ±$MAX_GAIN_DB dB" }
        require(q.isFinite() && q > 0.0) { "q must be positive" }
        require(rightFrequencyHz == null || (rightFrequencyHz.isFinite() && rightFrequencyHz > 0.0)) {
            "rightFrequencyHz must be a positive number"
        }
        require(rightGainDb == null || (rightGainDb.isFinite() && rightGainDb in -MAX_GAIN_DB..MAX_GAIN_DB)) {
            "rightGainDb must be within ±$MAX_GAIN_DB dB"
        }
        require(rightQ == null || (rightQ.isFinite() && rightQ > 0.0)) { "rightQ must be positive" }
    }

    /** True when the right channel needs its own design. */
    val hasRightOverride: Boolean
        get() = rightGainDb != null || rightFrequencyHz != null || rightQ != null

    /** The right channel's band, or null when both channels share this one. */
    fun rightBand(): EqualizerBand? = if (!hasRightOverride) {
        null
    } else {
        copy(
            frequencyHz = rightFrequencyHz ?: frequencyHz,
            gainDb = rightGainDb ?: gainDb,
            q = rightQ ?: q,
            rightFrequencyHz = null,
            rightGainDb = null,
            rightQ = null,
        )
    }

    companion object {
        const val MAX_GAIN_DB = 24.0
    }
}

/**
 * Cascade of biquad bands, one stage per band. A band at 0 dB keeps its stage: its design is the identity,
 * so dragging a band through 0 dB retunes that stage in place instead of resetting the others. [process]
 * returns without touching the samples while every band is at 0 dB on both channels, which keeps a flat
 * equalizer bit-exact — including a band whose left gain is flat while its right-channel override is not.
 *
 * A retune can glide. With a glide of N frames the band gains walk from their old value to the new one over
 * N frames of *processing*, one small gain step per [glideChunkFrames] samples, so a slider drag or a preset
 * change never lands as a single coefficient jump. An immediate retune (a glide of zero) is what the legacy
 * portable chain asks for; [moe.rukamori.archivetune.shared.domain.dsp.DspGraph] glides by default.
 *
 * A stage is redesigned whenever anything that defines its coefficients changes — its type, frequency, width,
 * gain or right-channel override — not when its gain differs. The sample rate is this instance's, so a format
 * change is a new equalizer, which is what [moe.rukamori.archivetune.shared.domain.dsp.DspGraph.configure] builds.
 *
 * The cascade is built for [MAX_BANDS] up front, so a host may install a band list of another width while it
 * renders: a retune only changes state, and no stage is created on the playback thread.
 */
class PortableEqualizer(
    private val sampleRateHz: Int,
    private val channels: Int,
    private val glideChunkFrames: Int = DEFAULT_GLIDE_CHUNK_FRAMES,
) {
    // Every stage the chain can ever run is built here, for the widest band list it accepts. A settings move
    // then changes state instead of allocating: the host prepares nothing on the playback thread.
    private val stages: List<BiquadFilter> = List(MAX_BANDS) { BiquadFilter(channels) }
    private var bandCount = 0
    private var bands: List<EqualizerBand> = emptyList()
    private var targetGainsDb = DoubleArray(MAX_BANDS)
    private var currentGainsDb = DoubleArray(MAX_BANDS)
    private var appliedGainsDb = DoubleArray(MAX_BANDS) { Double.NaN }

    /**
     * The gain each band's right channel runs at: the override when the band has one, otherwise the same gain
     * as its left channel. A right-only boost is a design that is not the identity, so it decides engagement,
     * gliding and bypass exactly as a left-channel gain does.
     */
    private var targetRightGainsDb = DoubleArray(MAX_BANDS)
    private var currentRightGainsDb = DoubleArray(MAX_BANDS)
    private var appliedRightGainsDb = DoubleArray(MAX_BANDS) { Double.NaN }

    /** The band each stage was last designed from, so any coefficient-defining change retunes that stage. */
    private var appliedBands = arrayOfNulls<EqualizerBand>(MAX_BANDS)
    private var glideRemainingFrames = 0

    init {
        require(channels > 0) { "channels must be positive" }
        require(glideChunkFrames > 0) { "glideChunkFrames must be positive" }
    }

    val isEngaged: Boolean
        get() {
            if (glideRemainingFrames > 0) return true
            for (index in 0 until bandCount) {
                if (currentGainsDb[index] != 0.0 || targetGainsDb[index] != 0.0) return true
                if (currentRightGainsDb[index] != 0.0 || targetRightGainsDb[index] != 0.0) return true
            }
            return false
        }

    /** True while a retune is still walking towards its target gains. */
    val isGliding: Boolean
        get() = glideRemainingFrames > 0

    /**
     * Replaces the bands. When the band count is unchanged the existing stages keep their history, so a
     * slider drag does not click, including a drag that crosses 0 dB. A different count starts every stage
     * from silence, because the bands no longer correspond to the same stages.
     */
    fun setBands(bands: List<EqualizerBand>, glideFrames: Int = 0) {
        require(glideFrames >= 0) { "glideFrames must not be negative" }
        require(bands.size <= MAX_BANDS) { "at most $MAX_BANDS bands" }
        if (bands == this.bands) return
        val countChanged = bands.size != this.bands.size
        this.bands = bands
        bandCount = bands.size
        if (countChanged) {
            // The bands no longer correspond to the same stages, so the whole cascade starts from silence.
            stages.forEach { it.reset() }
            appliedGainsDb.fill(Double.NaN)
            appliedRightGainsDb.fill(Double.NaN)
            appliedBands.fill(null)
        }
        bands.forEachIndexed { index, band ->
            targetGainsDb[index] = band.gainDb
            targetRightGainsDb[index] = rightGainOf(band)
        }
        if (glideFrames == 0 || countChanged) {
            for (index in 0 until bandCount) {
                currentGainsDb[index] = targetGainsDb[index]
                currentRightGainsDb[index] = targetRightGainsDb[index]
            }
            glideRemainingFrames = 0
        } else {
            glideRemainingFrames = glideFrames
        }
        retune()
    }

    fun process(samples: FloatArray, frames: Int) {
        require(frames >= 0 && samples.size >= frames * channels) { "samples hold fewer than $frames frames" }
        if (!isEngaged) return
        var remaining = frames
        var startFrame = 0
        while (remaining > 0) {
            val chunk = minOf(remaining, glideChunkFrames)
            advanceGlide(chunk)
            retune()
            for (index in 0 until bandCount) stages[index].process(samples, chunk, startFrame)
            startFrame += chunk
            remaining -= chunk
        }
    }

    fun reset() {
        stages.forEach { it.reset() }
        for (index in 0 until bandCount) {
            currentGainsDb[index] = targetGainsDb[index]
            currentRightGainsDb[index] = targetRightGainsDb[index]
        }
        appliedGainsDb.fill(Double.NaN)
        appliedRightGainsDb.fill(Double.NaN)
        appliedBands.fill(null)
        glideRemainingFrames = 0
        retune()
    }

    private fun advanceGlide(chunk: Int) {
        if (glideRemainingFrames <= 0) return
        val fraction = minOf(1.0, chunk.toDouble() / glideRemainingFrames)
        for (index in 0 until bandCount) {
            currentGainsDb[index] += (targetGainsDb[index] - currentGainsDb[index]) * fraction
            currentRightGainsDb[index] += (targetRightGainsDb[index] - currentRightGainsDb[index]) * fraction
        }
        glideRemainingFrames -= chunk
        if (glideRemainingFrames <= 0) {
            glideRemainingFrames = 0
            for (index in 0 until bandCount) {
                currentGainsDb[index] = targetGainsDb[index]
                currentRightGainsDb[index] = targetRightGainsDb[index]
            }
        }
    }

    /**
     * Designs every stage whose design input changed. The gain alone is not that input: a preset can move a
     * band's frequency, its shape, its width or its right-channel override while the gain stays the same, and a
     * gain-only cache would keep the previous coefficients for the whole stream. Both channels' gains are part
     * of that input, so a right-only change retunes and keeps rendering.
     */
    private fun retune() {
        for (index in 0 until bandCount) {
            val gain = currentGainsDb[index]
            val rightGain = currentRightGainsDb[index]
            val band = bands[index]
            if (appliedGainsDb[index] == gain &&
                appliedRightGainsDb[index] == rightGain &&
                appliedBands[index] == band
            ) {
                continue
            }
            // A flat band on a stage with no history yet is the identity, so taking the shared constant keeps a
            // band-width change allocation-free. A stage that still carries history is designed normally: a
            // glide that lands at 0 dB has to ring out instead of dropping its state.
            val flatAtRest = gain == 0.0 && rightGain == 0.0 &&
                (appliedBands[index] == null ||
                    (appliedGainsDb[index] == 0.0 && appliedRightGainsDb[index] == 0.0))
            appliedGainsDb[index] = gain
            appliedRightGainsDb[index] = rightGain
            appliedBands[index] = band
            if (flatAtRest) {
                stages[index].setCoefficients(BiquadCoefficients.IDENTITY)
            } else {
                stages[index].setCoefficients(
                    next = design(band.type, band.frequencyHz, gain, band.q),
                    rightChannel = rightCoefficients(band, rightGain),
                )
            }
        }
    }

    /** The gain a band's right channel runs at: its override, or the left gain when it has none. */
    private fun rightGainOf(band: EqualizerBand): Double = band.rightGainDb ?: band.gainDb

    /**
     * The right channel's design on its glided [gainDb], or null when both channels share the left one. The
     * band's own override values are read directly, so a retune allocates no intermediate band copy.
     */
    private fun rightCoefficients(band: EqualizerBand, gainDb: Double): BiquadCoefficients? {
        if (!band.hasRightOverride) return null
        return design(
            type = band.type,
            frequencyHz = band.rightFrequencyHz ?: band.frequencyHz,
            gainDb = gainDb,
            q = band.rightQ ?: band.q,
        )
    }

    private fun design(
        type: EqualizerBandType,
        frequencyHz: Double,
        gainDb: Double,
        q: Double,
    ): BiquadCoefficients = when (type) {
        EqualizerBandType.PEAKING -> BiquadDesign.peaking(sampleRateHz, frequencyHz, gainDb, q)
        EqualizerBandType.LOW_SHELF -> BiquadDesign.lowShelf(sampleRateHz, frequencyHz, gainDb, q)
        EqualizerBandType.HIGH_SHELF -> BiquadDesign.highShelf(sampleRateHz, frequencyHz, gainDb, q)
    }

    companion object {
        /**
         * The widest band list the chain accepts — `DspSettings.MAX_EQUALIZER_BANDS`. The stage pool and its
         * gain arrays are built for this width once, so installing another band list allocates nothing.
         */
        const val MAX_BANDS = 16

        /** The chunk a glide steps on: about 1.3 ms at 48 kHz, short enough to be inaudible, long enough to be cheap. */
        const val DEFAULT_GLIDE_CHUNK_FRAMES = 64
    }
}
