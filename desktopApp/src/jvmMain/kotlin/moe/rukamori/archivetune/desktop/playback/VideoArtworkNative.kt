package moe.rukamori.archivetune.desktop.playback

/**
 * The JNI entry points of the video artwork reader in `libarchivetune_engine`, one for one with
 * `archivetune_video_jni.c`. Callers use [NativeVideoArtwork], which owns the handle.
 */
internal object VideoArtworkNative {
    /** Starts an inactive reader for the HTTPS stream [url]. Zero when the reader could not start. */
    @JvmStatic external fun open(url: String, userAgent: String?, maxHeight: Int): Long

    @JvmStatic external fun setClock(handle: Long, positionUs: Long, playing: Boolean, speed: Float)

    @JvmStatic external fun setActive(handle: Long, active: Boolean)

    /**
     * Copies the presented BGRA frame to [address] when its sequence differs from [knownSequence]. Fills [info] with
     * sequence, width, height, state and presentation time. Returns 1 when it copied, 0 when nothing is new, and -1
     * when [capacity] is too small.
     */
    @JvmStatic external fun copyFrame(handle: Long, knownSequence: Long, address: Long, capacity: Long, info: LongArray): Int

    @JvmStatic external fun lastError(handle: Long): String

    @JvmStatic external fun close(handle: Long)
}
