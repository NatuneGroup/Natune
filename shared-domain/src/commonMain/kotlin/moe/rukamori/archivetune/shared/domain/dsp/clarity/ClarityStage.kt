package moe.rukamori.archivetune.shared.domain.dsp.clarity

import moe.rukamori.archivetune.shared.domain.dsp.eq.BiquadCoefficients
import moe.rukamori.archivetune.shared.domain.dsp.eq.BiquadDesign
import moe.rukamori.archivetune.shared.domain.dsp.eq.BiquadFilter

/**
 * Studio Master Clarity as a portable stage, ported stage for stage from the native chain: the six-section
 * contour, then the harmonic exciter fed from the dry signal, then a mono bass and a stereo width pass, then
 * the makeup gain.
 *
 * The headphone crossfeed of the native chain is deliberately not ported: it was never wired into playback
 * because on phone speakers it reads as phasey coloration.
 */
class ClarityStage(private val sampleRateHz: Int, private val channels: Int) {
    // Every preset carries the same six sections, so the contour is built once here rather than on the first
    // preset a host enables: installing a preset then only retunes stages that already exist.
    private val contour = List(ClarityPreset.CONTOUR_SECTIONS) { BiquadFilter(channels) }
    private val exciter = BiquadFilter(channels)
    private val side = BiquadFilter(1)
    private var dry = FloatArray(0)

    var preset: ClarityPreset? = null
        private set

    init {
        require(sampleRateHz >= BiquadDesign.MIN_SAMPLE_RATE_HZ) { "sampleRateHz must be at least ${BiquadDesign.MIN_SAMPLE_RATE_HZ}" }
        require(channels > 0) { "channels must be positive" }
    }

    // Both filters are fixed by the sample rate, so their designs are built with the stage, not with a preset.
    private val exciterCoefficients =
        BiquadDesign.highPass(sampleRateHz, ClarityPreset.EXCITER_HZ, ClarityPreset.BUTTERWORTH_Q)
    private val sideCoefficients =
        BiquadDesign.highPass(sampleRateHz, ClarityPreset.MONO_BASS_HZ, ClarityPreset.BUTTERWORTH_Q)

    val isEngaged: Boolean
        get() = preset != null

    /** Installs a preset, or removes the contour with null. Coefficients keep their state, so a preset move glides. */
    fun setPreset(preset: ClarityPreset?) {
        if (preset == this.preset) return
        this.preset = preset
        if (preset == null) {
            contour.forEach { it.reset() }
            exciter.reset()
            side.reset()
            return
        }
        val bands = preset.bands
        bands.forEachIndexed { index, band -> contour[index].setCoefficients(design(band)) }
        exciter.setCoefficients(exciterCoefficients)
        side.setCoefficients(sideCoefficients)
    }

    fun process(samples: FloatArray, frames: Int) {
        val preset = preset ?: return
        val count = frames * channels
        require(frames >= 0 && samples.size >= count) { "samples hold fewer than $frames frames" }
        if (count == 0) return
        ensureDry(count)
        samples.copyInto(dry, 0, 0, count)

        // Subsonic removal carries a linear post gain that belongs to this stage alone.
        contour[0].process(samples, frames)
        val subsonic = preset.subsonicGain.toFloat()
        if (subsonic != 1f) for (index in 0 until count) samples[index] *= subsonic
        for (index in 1 until contour.size) contour[index].process(samples, frames)

        addExciter(samples, count, preset.exciterAmount.toFloat())
        if (channels == 2) widen(samples, frames, preset.sideGain.toFloat())
        val makeup = ClarityPreset.MAKEUP_GAIN.toFloat()
        if (makeup != 1f) for (index in 0 until count) samples[index] *= makeup
    }

    fun reset() {
        contour.forEach { it.reset() }
        exciter.reset()
        side.reset()
    }

    /** Harmonic sheen: a cubed term taken from the dry highs, added at exciter level. */
    private fun addExciter(samples: FloatArray, count: Int, amount: Float) {
        var index = 0
        repeat(count / channels) {
            for (channel in 0 until channels) {
                val high = exciter.tick(dry[index], channel)
                samples[index] += (high - high * high * high * 0.25f) * amount
                index++
            }
        }
    }

    /** Mid/side widening with the bass kept centred, the native chain's anti-blur. */
    private fun widen(samples: FloatArray, frames: Int, sideGain: Float) {
        var index = 0
        repeat(frames) {
            val left = samples[index]
            val right = samples[index + 1]
            val mid = (left + right) * 0.5f
            val rawSide = (left - right) * 0.5f
            val wideSide = side.tick(rawSide, 0) * sideGain * ClarityPreset.STEREO_WIDTH.toFloat()
            samples[index] = mid + wideSide
            samples[index + 1] = mid - wideSide
            index += 2
        }
    }

    private fun ensureDry(samples: Int) {
        if (dry.size < samples) dry = FloatArray(samples)
    }

    private fun design(band: ClarityBand): BiquadCoefficients = when (band.type) {
        ClarityFilterType.HIGH_PASS -> BiquadDesign.highPass(sampleRateHz, band.frequencyHz, band.q)
        ClarityFilterType.PEAKING -> BiquadDesign.peaking(sampleRateHz, band.frequencyHz, band.gainDb, band.q)
        ClarityFilterType.HIGH_SHELF -> BiquadDesign.highShelfSlope(sampleRateHz, band.frequencyHz, band.gainDb, band.slope)
    }
}
