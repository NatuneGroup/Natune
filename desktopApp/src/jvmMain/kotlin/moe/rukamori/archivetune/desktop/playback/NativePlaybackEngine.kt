package moe.rukamori.archivetune.desktop.playback

import moe.rukamori.archivetune.shared.data.playback.EnginePlaybackState

/** Who pulls audio from the engine: the caller ([MANUAL], used by tests) or a miniaudio device ([DEVICE]). */
internal enum class EngineOutputMode(val nativeValue: Int) {
    MANUAL(0),
    DEVICE(1),
}

/** The miniaudio backend a device-mode engine opens. [NULL] runs on a clock without making sound. */
enum class EngineBackend(val nativeValue: Int) {
    DEFAULT(0),
    NULL(1),
}

/**
 * One native engine instance, with typed calls and the handle's lifetime.
 *
 * The engine serialises its own commands, so the calls may come from any thread. An owned engine
 * must be closed exactly once and must not be used afterwards. A borrowed deck from
 * [NativeTwoDeckMixer] is valid only until that mixer closes, and closing the wrapper leaves the
 * native engine alive for its mixer to free.
 */
internal class NativePlaybackEngine private constructor(
    private val handle: Long,
    private val owned: Boolean,
) : AutoCloseable {
    private var closed = false

    /** Replaces the current source and returns its load generation, or 0 when the engine rejects it. */
    fun load(source: String, startUs: Long, playWhenReady: Boolean): Long =
        PlaybackEngineNative.load(handle, source, startUs, playWhenReady)

    /**
     * Replaces the current source with [source], as [engineSourceOpen] chose it for the locator. A leased source is
     * read through its callbacks, and the engine closes it exactly once, even when it refuses the request.
     */
    fun load(source: EngineSource, startUs: Long, playWhenReady: Boolean): Long = when (source) {
        is EngineSource.Direct -> load(source.source, startUs, playWhenReady)
        is EngineSource.Leased -> PlaybackEngineNative.loadStream(handle, source.lease, startUs, playWhenReady)
    }

    fun play() = PlaybackEngineNative.play(handle)

    fun pause() = PlaybackEngineNative.pause(handle)

    fun seek(positionUs: Long) = PlaybackEngineNative.seek(handle, positionUs)

    fun setSpeed(speed: Float) = PlaybackEngineNative.setSpeed(handle, speed)

    fun stop() = PlaybackEngineNative.stop(handle)

    fun snapshot(): EnginePlaybackState {
        val values = LongArray(SNAPSHOT_FIELDS)
        PlaybackEngineNative.snapshot(handle, values)
        return EnginePlaybackState(
            flags = values[0].toInt(),
            errorCode = values[1].toInt(),
            loadGeneration = values[2],
            seekGeneration = values[3],
            errorSequence = values[4],
            positionUs = values[5],
            durationUs = values[6],
            bufferedUs = values[7],
            playbackSpeed = Float.fromBits(values[8].toInt()),
        )
    }

    fun lastError(): String = PlaybackEngineNative.lastError(handle)

    /** Manual mode only. Returns the number of frames written to [out]. */
    fun render(out: FloatArray, frames: Int): Int = PlaybackEngineNative.render(handle, out, frames)

    override fun close() {
        if (closed) return
        closed = true
        if (owned) PlaybackEngineNative.destroy(handle)
    }

    companion object {
        private const val SNAPSHOT_FIELDS = 9
        private const val DEFAULT_SAMPLE_RATE = 48_000
        private const val DEFAULT_RING_MS = 2_000
        private const val DEFAULT_PREBUFFER_MS = 200

        /**
         * Wraps a deck that a [NativeTwoDeckMixer] owns. The wrapper never frees it, so a deck is
         * freed exactly once, by the mixer that created it.
         */
        internal fun borrow(handle: Long): NativePlaybackEngine {
            check(handle != 0L) { "the playback deck is not available" }
            return NativePlaybackEngine(handle, owned = false)
        }

        /**
         * Starts an engine. Throws [IllegalStateException] with the engine's own message when the
         * output device cannot open, or when the library is missing.
         */
        fun create(
            outputMode: EngineOutputMode,
            backend: EngineBackend,
            sampleRate: Int = DEFAULT_SAMPLE_RATE,
            ringMs: Int = DEFAULT_RING_MS,
            prebufferMs: Int = DEFAULT_PREBUFFER_MS,
        ): NativePlaybackEngine {
            PlaybackEngineLibrary.ensureLoaded()
            val handle = PlaybackEngineNative.create(
                outputMode.nativeValue,
                backend.nativeValue,
                sampleRate,
                ringMs,
                prebufferMs,
            )
            check(handle != 0L) { "the playback engine did not start" }
            return NativePlaybackEngine(handle, owned = true)
        }
    }
}
