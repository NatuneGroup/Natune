package moe.rukamori.archivetune.desktop.playback

import moe.rukamori.archivetune.shared.domain.dsp.eq.BiquadCoefficients
import moe.rukamori.archivetune.shared.domain.dsp.eq.BiquadDesign
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBand
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBandType

/** The mixer's timeline, schedule and active deck, read in one locked step. */
internal data class TwoDeckMixerState(
    /** Output frames the device has consumed. This is the only clock a handoff is scheduled on. */
    val timelineFrames: Long,
    /** Increments each time the incoming deck becomes the active one. */
    val commitSequence: Long,
    /** Armed handoff bounds on the timeline; both are zero when nothing is armed. */
    val startFrame: Long,
    val endFrame: Long,
    val activeDeck: Int,
    val incomingDeck: Int,
    val armed: Boolean,
    val bypass: Boolean,
    val bandCount: Int,
    /** The active deck's consumed media position and duration (-1 when unknown), and its speed. */
    val activePositionUs: Long,
    val activeDurationUs: Long,
    val activeSpeed: Float,
)

/**
 * One output device shared by two manual engine decks, with the handle's lifetime.
 *
 * The device callback is the only clock, so a handoff lands on the same output frame however the device
 * splits its callbacks. [deck] returns borrowed engines that the caller drives with the usual
 * [NativePlaybackEngine] calls. Closing the mixer stops the device before it frees the decks, and the decks
 * must not be used afterwards.
 */
internal class NativeTwoDeckMixer private constructor(
    private val handle: Long,
    val sampleRateHz: Int,
) : AutoCloseable {
    private var closed = false
    private val decks: Array<NativePlaybackEngine> = arrayOf(
        NativePlaybackEngine.borrow(TwoDeckMixerNative.deck(handle, 0)),
        NativePlaybackEngine.borrow(TwoDeckMixerNative.deck(handle, 1)),
    )

    /** The borrowed engine for [index] (0 or 1). */
    fun deck(index: Int): NativePlaybackEngine {
        require(index == 0 || index == 1) { "deck index must be 0 or 1, was $index" }
        return decks[index]
    }

    /**
     * Schedules [incoming] to take over at [startFrame] over [overlapFrames] (zero is a gapless splice).
     * Returns false when the mixer refuses: a handoff is already armed, the start is in the past, or the
     * mixer is bypassed.
     */
    fun arm(incoming: Int, startFrame: Long, overlapFrames: Long): Boolean =
        TwoDeckMixerNative.arm(handle, incoming, startFrame, overlapFrames) == RESULT_OK

    /** Drops an armed handoff that has not started. The active deck keeps the output. */
    fun cancel() = TwoDeckMixerNative.cancel(handle)

    /** Makes an armed handoff active at once. Returns false when nothing was armed. */
    fun promote(): Boolean = TwoDeckMixerNative.promote(handle)

    fun setDeckGain(deck: Int, gain: Float) = TwoDeckMixerNative.setDeckGain(handle, deck, gain)

    fun setBypass(bypass: Boolean) = TwoDeckMixerNative.setBypass(handle, bypass)

    /**
     * Replaces the equalizer with [packedSections], laid out as [equalizerCoefficients] returns them. An empty
     * array turns the equalizer off. Returns false when the mixer refuses the layout, such as more than [MAX_BANDS]
     * sections.
     */
    fun setEqualizer(packedSections: DoubleArray): Boolean =
        TwoDeckMixerNative.setEqualizer(handle, packedSections) == RESULT_OK

    fun snapshot(): TwoDeckMixerState {
        val values = LongArray(SNAPSHOT_FIELDS)
        TwoDeckMixerNative.snapshot(handle, values)
        return TwoDeckMixerState(
            timelineFrames = values[0],
            commitSequence = values[1],
            startFrame = values[2],
            endFrame = values[3],
            activeDeck = values[4].toInt(),
            incomingDeck = values[5].toInt(),
            armed = values[6] != 0L,
            bypass = values[7] != 0L,
            bandCount = values[8].toInt(),
            activePositionUs = values[9],
            activeDurationUs = values[10],
            activeSpeed = Float.fromBits(values[11].toInt()),
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        TwoDeckMixerNative.destroy(handle)
    }

    companion object {
        /** The most equalizer sections the mixer holds. A host with more bands must refuse them. */
        const val MAX_BANDS: Int = 16

        const val DEFAULT_SAMPLE_RATE_HZ: Int = 48_000
        private const val DEFAULT_RING_MS = 2_000
        private const val DEFAULT_PREBUFFER_MS = 200
        private const val RESULT_OK = 0
        private const val SNAPSHOT_FIELDS = 12

        /**
         * Opens the output device and both decks. Throws [IllegalStateException] with the engine's own message
         * when the library or the output device is unavailable.
         */
        fun create(
            backend: EngineBackend,
            sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
            ringMs: Int = DEFAULT_RING_MS,
            prebufferMs: Int = DEFAULT_PREBUFFER_MS,
        ): NativeTwoDeckMixer {
            PlaybackEngineLibrary.ensureLoaded()
            val handle = TwoDeckMixerNative.create(backend.nativeValue, sampleRateHz, ringMs, prebufferMs)
            check(handle != 0L) { "the audio mixer did not start" }
            return NativeTwoDeckMixer(handle, sampleRateHz)
        }
    }
}

private const val COEFFICIENTS_PER_BAND = 5

/**
 * Packs the bands as biquad sections in the layout the mixer takes: b0, b1, b2, a1, a2 per band. A flat equalizer,
 * with every band at 0 dB, packs nothing and leaves the stream untouched, as the portable equalizer bypasses it.
 * Otherwise every band keeps its section, 0 dB included: a 0 dB design is the identity, and the mixer resets its
 * filter history whenever the section count changes, so dragging a band through 0 dB must not change that count.
 */
internal fun equalizerCoefficients(sampleRateHz: Int, bands: List<EqualizerBand>): DoubleArray {
    require(bands.size <= NativeTwoDeckMixer.MAX_BANDS) {
        "the equalizer has ${bands.size} bands; the mixer holds ${NativeTwoDeckMixer.MAX_BANDS}"
    }
    if (bands.all { it.gainDb == 0.0 }) return DoubleArray(0)
    val packed = DoubleArray(bands.size * COEFFICIENTS_PER_BAND)
    bands.forEachIndexed { index, band ->
        val section = designBand(sampleRateHz, band)
        val offset = index * COEFFICIENTS_PER_BAND
        packed[offset] = section.b0
        packed[offset + 1] = section.b1
        packed[offset + 2] = section.b2
        packed[offset + 3] = section.a1
        packed[offset + 4] = section.a2
    }
    return packed
}

private fun designBand(sampleRateHz: Int, band: EqualizerBand): BiquadCoefficients =
    when (band.type) {
        EqualizerBandType.PEAKING -> BiquadDesign.peaking(sampleRateHz, band.frequencyHz, band.gainDb, band.q)
        EqualizerBandType.LOW_SHELF -> BiquadDesign.lowShelf(sampleRateHz, band.frequencyHz, band.gainDb, band.q)
        EqualizerBandType.HIGH_SHELF -> BiquadDesign.highShelf(sampleRateHz, band.frequencyHz, band.gainDb, band.q)
    }
