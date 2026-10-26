package moe.rukamori.archivetune.desktop.playback

/** What the reader is doing, as `ae_video_state` reports it. */
internal enum class VideoArtworkState { OPENING, READY, ENDED, FAILED }

/** One presented frame's shape, read back from the reader after a copy. */
internal class VideoArtworkFrameInfo(
    val sequence: Long,
    val width: Int,
    val height: Int,
    val state: VideoArtworkState,
)

/**
 * One native video reader. It downloads and decodes only while active, and shows the frame that matches the clock the
 * caller sets, so the picture follows the audio engine. Every call holds the object's lock, so none can reach a handle
 * that [close] has freed.
 */
internal class NativeVideoArtwork private constructor(private var handle: Long) : AutoCloseable {
    private val info = LongArray(INFO_FIELDS)

    @Synchronized
    fun setClock(positionMs: Long, playing: Boolean, speed: Float) {
        val current = handle.takeIf { it != 0L } ?: return
        VideoArtworkNative.setClock(current, positionMs.coerceAtLeast(0L) * MICROS_PER_MILLI, playing, speed)
    }

    @Synchronized
    fun setActive(active: Boolean) {
        val current = handle.takeIf { it != 0L } ?: return
        VideoArtworkNative.setActive(current, active)
    }

    /**
     * Copies a new frame to [address] when one is presented since [knownSequence]. Returns the frame info and whether
     * the copy happened; a false copy with a larger frame than [capacity] tells the caller to grow its buffer.
     */
    @Synchronized
    fun copyFrame(knownSequence: Long, address: Long, capacity: Long): Pair<VideoArtworkFrameInfo, Int>? {
        val current = handle.takeIf { it != 0L } ?: return null
        val result = VideoArtworkNative.copyFrame(current, knownSequence, address, capacity, info)
        val state = VideoArtworkState.entries.getOrElse(info[3].toInt()) { VideoArtworkState.FAILED }
        return VideoArtworkFrameInfo(info[0], info[1].toInt(), info[2].toInt(), state) to result
    }

    @Synchronized
    fun lastError(): String = handle.takeIf { it != 0L }?.let(VideoArtworkNative::lastError).orEmpty()

    @Synchronized
    override fun close() {
        val current = handle
        handle = 0L
        if (current != 0L) VideoArtworkNative.close(current)
    }

    companion object {
        private const val INFO_FIELDS = 5
        private const val MICROS_PER_MILLI = 1_000L

        /** Null when the engine library is missing or the reader could not start. */
        fun open(url: String, userAgent: String?, maxHeight: Int): NativeVideoArtwork? {
            PlaybackEngineLibrary.ensureLoaded()
            val handle = VideoArtworkNative.open(url, userAgent, maxHeight)
            return if (handle == 0L) null else NativeVideoArtwork(handle)
        }
    }
}
