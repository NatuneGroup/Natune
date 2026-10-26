/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.playback

/**
 * One read of a native playback engine, with the same fields as its C snapshot.
 *
 * The engine counts loads and seeks from zero at each load, so [loadGeneration] identifies the
 * request the read belongs to and [seekGeneration] only means something within that request.
 * Positions and durations are microseconds, as the engine reports them. A negative duration or
 * buffered value means the engine does not know it.
 */
data class EnginePlaybackState(
    val flags: Int,
    val errorCode: Int,
    val loadGeneration: Long,
    val seekGeneration: Long,
    val errorSequence: Long,
    val positionUs: Long,
    val durationUs: Long,
    val bufferedUs: Long,
    val playbackSpeed: Float,
) {
    val hasSource: Boolean get() = has(FLAG_HAS_SOURCE)
    val playWhenReady: Boolean get() = has(FLAG_PLAY_WHEN_READY)
    val isAdvancing: Boolean get() = has(FLAG_ADVANCING)
    val isBuffering: Boolean get() = has(FLAG_BUFFERING)
    val isEnded: Boolean get() = has(FLAG_ENDED)
    val isSeekable: Boolean get() = has(FLAG_SEEKABLE)

    private fun has(flag: Int): Boolean = flags and flag != 0

    companion object {
        /** Bit values of the engine's `ae_flag` enumeration. */
        const val FLAG_HAS_SOURCE: Int = 1 shl 0
        const val FLAG_PLAY_WHEN_READY: Int = 1 shl 1
        const val FLAG_ADVANCING: Int = 1 shl 2
        const val FLAG_BUFFERING: Int = 1 shl 3
        const val FLAG_ENDED: Int = 1 shl 4
        const val FLAG_SEEKABLE: Int = 1 shl 5

        /** Values of the engine's `ae_error` enumeration. */
        const val ERROR_NONE: Int = 0
        const val ERROR_OPEN: Int = 1
        const val ERROR_DECODE: Int = 2
        const val ERROR_OUTPUT: Int = 3
    }
}
