package moe.rukamori.archivetune.desktop.playback

/**
 * The JNI entry points of `libarchivetune_engine`, one for one with `archivetune_engine_jni.c`.
 *
 * The C side binds these by name and signature, so a rename or a parameter change here needs the
 * same change in that file. Callers use [NativePlaybackEngine], which owns the handle and decodes
 * the snapshot; nothing else should call these directly.
 */
internal object PlaybackEngineNative {
    @JvmStatic external fun abiVersion(): Int

    /**
     * Opens a real output device at [sampleRate], starts it and closes it again. Null means the host can make a sound;
     * a string is the refusal the first play would report, so the window can tell the user before they press play.
     * This is the one binding that does not need an engine handle.
     */
    @JvmStatic external fun probeOutputDevice(sampleRate: Int): String?

    @JvmStatic external fun create(
        outputMode: Int,
        backend: Int,
        sampleRate: Int,
        ringMs: Int,
        prebufferMs: Int,
    ): Long

    @JvmStatic external fun destroy(handle: Long)

    @JvmStatic external fun load(handle: Long, source: String, startUs: Long, playWhenReady: Boolean): Long

    /**
     * Queues a host lease for the next load. The engine reads the source through [host]'s callbacks and owns [host] from
     * this call on: it closes [host] exactly once, including when it refuses the request, so the caller must not reuse it.
     */
    @JvmStatic external fun loadStream(handle: Long, host: NativeStreamLease, startUs: Long, playWhenReady: Boolean): Long

    @JvmStatic external fun play(handle: Long)

    @JvmStatic external fun pause(handle: Long)

    @JvmStatic external fun seek(handle: Long, positionUs: Long)

    @JvmStatic external fun setSpeed(handle: Long, speed: Float)

    @JvmStatic external fun stop(handle: Long)

    /** Fills [out] with nine values: flags, error code, load, seek, error sequence, position, duration, buffered, speed bits. */
    @JvmStatic external fun snapshot(handle: Long, out: LongArray)

    @JvmStatic external fun lastError(handle: Long): String

    /** Manual output mode only: writes interleaved stereo frames and returns how many were written. */
    @JvmStatic external fun render(handle: Long, out: FloatArray, frames: Int): Int
}
