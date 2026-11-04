package moe.rukamori.archivetune.shared.domain.dsp

import kotlin.math.max
import kotlin.math.roundToInt
import moe.rukamori.archivetune.shared.domain.dsp.bitperfect.SoftwareStagePlan
import moe.rukamori.archivetune.shared.domain.dsp.clarity.ClarityStage
import moe.rukamori.archivetune.shared.domain.dsp.eq.PortableEqualizer
import moe.rukamori.archivetune.shared.domain.dsp.tempo.FrameFifo
import moe.rukamori.archivetune.shared.domain.dsp.tempo.TempoPitchStage

/** The interleaved float format a graph runs at. A host reconfigures the graph when either number changes. */
data class DspFormat(val sampleRateHz: Int, val channelCount: Int) {
    init {
        require(sampleRateHz >= MIN_SAMPLE_RATE_HZ) { "sampleRateHz must be at least $MIN_SAMPLE_RATE_HZ" }
        require(channelCount > 0) { "channelCount must be positive" }
    }

    companion object {
        const val MIN_SAMPLE_RATE_HZ = 8_000
        const val DEFAULT_SAMPLE_RATE_HZ = 48_000
        const val DEFAULT_CHANNEL_COUNT = 2
    }
}

/**
 * The one ordered software chain. Every platform adapter feeds decoded interleaved float32 samples in and
 * takes processed ones out; none of them owns tone math any more.
 *
 * Order, as the adapter contract requires:
 * normalization and preamp → equalizer cascade → clarity contour → DC blocker → soft-knee limiter →
 * tempo/pitch → transition gain → full-scale clamp → optional TPDF dither.
 *
 * Each stage is gated twice: the [SoftwareStagePlan] says what the route allows (a bit-perfect route allows
 * nothing) and [DspSettings] says what the listener asked for. A bypassed plan — or settings that would
 * change no sample — hands the input through bit-exactly, and entering bypass clears every stage's history
 * so a later engaged block cannot inherit it.
 *
 * Block processing is allocation-free once the block size has settled: [push] works in preallocated
 * scratch, [pull] copies out of one frame FIFO, and only the time-stretch stage can hold output back. A
 * caller therefore sizes its output buffer with [requiredOutputCapacity] and drains with [pull] until
 * [pendingFrames] is zero. Only [configure] allocates, and a host calls it off the audio thread.
 */
class DspGraph(
    sampleRateHz: Int = DspFormat.DEFAULT_SAMPLE_RATE_HZ,
    channelCount: Int = DspFormat.DEFAULT_CHANNEL_COUNT,
    private val equalizerGlideMs: Double = DEFAULT_EQUALIZER_GLIDE_MS,
) {
    var format: DspFormat = DspFormat(sampleRateHz, channelCount)
        private set

    private var equalizer = PortableEqualizer(format.sampleRateHz, format.channelCount)
    private var clarity = ClarityStage(format.sampleRateHz, format.channelCount)
    private var dcBlocker = DcBlocker(format.sampleRateHz, format.channelCount)
    private var limiter = SoftKneeLimiter(format.sampleRateHz, format.channelCount)
    private var tempo = TempoPitchStage(format.channelCount, format.sampleRateHz)
    private var transition = TransitionGainRamp(format.sampleRateHz, format.channelCount)
    private var output = FrameFifo(format.channelCount)
    private var dither: TpdfDither? = null

    private var work = FloatArray(0)
    private var tempoScratch = FloatArray(0)
    private var settings = DspSettings()
    private var inputGain = 1f
    private var tempoEngaged = false
    private var handsThrough = settings.isNeutral
    private var inputEnded = false

    init {
        require(equalizerGlideMs >= 0.0) { "equalizerGlideMs must not be negative" }
    }

    /** The plan currently bypassing the chain. A bypassed graph copies its input and touches no stage. */
    var isBypassed: Boolean = false
        private set

    /** Frames waiting in [pull]. */
    val pendingFrames: Int
        get() = output.count

    /** True while the tempo substage is the one that can change the frame count. */
    val isTempoEngaged: Boolean
        get() = tempoEngaged

    /**
     * Rebuilds the chain for a new stream format. Allocates, so a host calls it from its control path and
     * keeps the previous graph's output drained first.
     */
    fun configure(format: DspFormat) {
        if (format == this.format) return
        this.format = format
        equalizer = PortableEqualizer(format.sampleRateHz, format.channelCount)
        clarity = ClarityStage(format.sampleRateHz, format.channelCount)
        dcBlocker = DcBlocker(format.sampleRateHz, format.channelCount)
        limiter = SoftKneeLimiter(format.sampleRateHz, format.channelCount)
        tempo = TempoPitchStage(format.channelCount, format.sampleRateHz)
        transition = TransitionGainRamp(format.sampleRateHz, format.channelCount)
        output = FrameFifo(format.channelCount)
        dither = null
        work = FloatArray(0)
        tempoScratch = FloatArray(0)
        settings = DspSettings()
        inputGain = 1f
        tempoEngaged = false
        handsThrough = settings.isNeutral
        inputEnded = false
        isBypassed = false
    }

    /**
     * Applies one track's settings under one plan. Cheap enough for a settings change or a track boundary,
     * and it keeps every stage's history, so a gain or preset move glides instead of clicking.
     */
    fun apply(settings: DspSettings, plan: SoftwareStagePlan) {
        this.settings = settings
        if (plan.bypass) {
            if (!isBypassed) resetStages()
            isBypassed = true
            handsThrough = false
            transition.reset(settings.transitionGain)
            return
        }
        val wasBypassed = isBypassed
        isBypassed = false
        inputGain = if (plan.normalization && settings.normalizationEnabled) settings.linearInputGain else 1f
        equalizer.setBands(
            bands = if (plan.equalizer && settings.equalizerEnabled) settings.equalizerBands else emptyList(),
            glideFrames = glideFrames(),
        )
        // The clarity contour is a tone stage, so the plan's equalizer flag gates it with the user's equalizer.
        clarity.setPreset(if (plan.equalizer) settings.clarity else null)
        limiter.setCeilingDb(settings.limiterCeilingDb)
        tempo.configure(
            speed = if (plan.tempoPitch) settings.speed else 1.0,
            pitchSemitones = if (plan.tempoPitch) settings.pitchSemitones else 0.0,
        )
        tempoEngaged = !tempo.isPassthrough
        if (wasBypassed) transition.reset(settings.transitionGain) else transition.setGain(settings.transitionGain)
        val ditherSettings = settings.dither
        if (ditherSettings.enabled) {
            val stage = dither ?: TpdfDither(ditherSettings.bitDepth, ditherSettings.seed).also { dither = it }
            if (stage.bitDepth != ditherSettings.bitDepth) stage.setFormat(ditherSettings.bitDepth)
        }
        handsThrough = settings.isNeutral && transition.isSteady && transition.gain == 1f
    }

    /**
     * Pushes [frames] frames of input. Output becomes available through [pull]; the frame count can differ
     * only while tempo/pitch is engaged.
     */
    fun push(input: FloatArray, frames: Int) {
        check(!inputEnded) { "push after finish" }
        val channels = format.channelCount
        require(frames >= 0 && input.size >= frames * channels) { "input holds fewer than $frames frames" }
        if (frames == 0) return
        if (isBypassed || handsThrough) {
            output.append(input, frames)
            return
        }
        val samples = frames * channels
        ensureWork(samples)
        input.copyInto(work, 0, 0, samples)
        if (inputGain != 1f) {
            for (index in 0 until samples) work[index] *= inputGain
        }
        equalizer.process(work, frames)
        clarity.process(work, frames)
        if (settings.dcBlockerEnabled) dcBlocker.process(work, frames)
        if (settings.limiterEnabled) limiter.process(work, frames)
        if (tempoEngaged) {
            tempo.push(work, frames)
            drainTempo()
        } else {
            postProcess(work, frames)
        }
    }

    /** Convenience for a one-shot host or a test: one push and one drain. */
    fun process(input: FloatArray, frames: Int, destination: FloatArray, capacityFrames: Int): Int {
        push(input, frames)
        return pull(destination, capacityFrames)
    }

    /** Copies up to [capacityFrames] processed frames into [destination] and returns how many were copied. */
    fun pull(destination: FloatArray, capacityFrames: Int): Int {
        val channels = format.channelCount
        require(capacityFrames >= 0 && destination.size >= capacityFrames * channels) {
            "destination holds fewer than $capacityFrames frames"
        }
        if (capacityFrames == 0) return 0
        return output.drainTo(destination, minOf(capacityFrames, output.count))
    }

    /** Ends the stream and releases the tail the tempo stage holds back. [pull] still drains what is left. */
    fun finish() {
        if (inputEnded) return
        inputEnded = true
        if (isBypassed || handsThrough) return
        tempo.endOfStream()
        if (tempoEngaged) drainTempo()
    }

    /** Clears every stage's history and the output FIFO. A host calls this at a seek or a route change. */
    fun reset() {
        output.clear()
        inputEnded = false
        resetStages()
        transition.reset(settings.transitionGain)
    }

    /**
     * A safe output-buffer size for [inputFrames], covering the whole stretch range plus the stretch
     * stage's lookahead. A host may allocate one buffer of this size per block and never grow it.
     */
    fun requiredOutputCapacity(inputFrames: Int): Int =
        pendingFrames + inputFrames * STRETCH_NUMERATOR + format.sampleRateHz / 20

    private fun resetStages() {
        equalizer.reset()
        clarity.reset()
        dcBlocker.reset()
        limiter.reset()
        tempo.reset()
    }

    private fun drainTempo() {
        val channels = format.channelCount
        // The pump is asked for a fixed capacity, not for `pendingFrames`: the stretch stage may hold
        // frames the resampler cannot yet turn into output, and a zero-sized request would never move them.
        while (true) {
            val capacity = max(tempo.pendingFrames, TEMPO_DRAIN_FRAMES)
            ensureTempoScratch(capacity * channels)
            val copied = tempo.pullReady(tempoScratch, capacity)
            if (copied <= 0) return
            postProcess(tempoScratch, copied)
        }
    }

    private fun postProcess(samples: FloatArray, frames: Int) {
        val channels = format.channelCount
        transition.apply(samples, frames)
        val count = frames * channels
        for (index in 0 until count) samples[index] = samples[index].coerceIn(-1f, 1f)
        if (settings.dither.enabled) dither?.process(samples, frames, channels)
        output.append(samples, frames)
    }

    private fun glideFrames(): Int =
        if (equalizerGlideMs <= 0.0) 0 else (format.sampleRateHz * equalizerGlideMs / 1_000.0).roundToInt()

    private fun ensureWork(samples: Int) {
        if (work.size < samples) work = FloatArray(max(samples, work.size * 2))
    }

    private fun ensureTempoScratch(samples: Int) {
        if (tempoScratch.size < samples) tempoScratch = FloatArray(max(samples, tempoScratch.size * 2))
    }

    companion object {
        /** A coefficient glide of 20 ms is long enough to hide a preset jump and short enough to feel instant. */
        const val DEFAULT_EQUALIZER_GLIDE_MS = 20.0

        /** The slowest supported tempo stretches by two, so an output buffer needs at most twice the input. */
        private const val STRETCH_NUMERATOR = 2

        /** One tempo pump request. Large enough that a stretch stage always has room to hand over its output. */
        private const val TEMPO_DRAIN_FRAMES = 1_024
    }
}
