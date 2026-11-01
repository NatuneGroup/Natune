package moe.rukamori.archivetune.playback

/**
 * The primary player's buffer posture. Unmetered links keep the long-standing defaults; a metered
 * or cellular link raises only the start and rebuffer thresholds, because the same-jitter bursty
 * stall that Apple's larger forPlaybackAfterRebuffer guards against is exactly what cellular radio
 * produces — while the min/max watermarks stay unchanged so a slow link cannot grow the heap it
 * holds. Lean (low-RAM or power-save) keeps its own smaller profile unchanged either way, since a
 * constrained device never buys more buffer.
 */
internal object PlaybackBufferPolicy {
    const val PRIMARY_MIN_BUFFER_MS = 20_000
    const val PRIMARY_MAX_BUFFER_MS = 60_000
    const val PRIMARY_BUFFER_FOR_PLAYBACK_MS = 150
    const val PRIMARY_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 750

    const val METERED_BUFFER_FOR_PLAYBACK_MS = 1_000
    const val METERED_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 2_000

    const val CONSERVATIVE_MIN_BUFFER_MS = 8_000
    const val CONSERVATIVE_MAX_BUFFER_MS = 24_000
    const val CONSERVATIVE_BUFFER_FOR_PLAYBACK_MS = 250
    const val CONSERVATIVE_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 1_000

    data class Durations(
        val minBufferMs: Int,
        val maxBufferMs: Int,
        val forPlaybackMs: Int,
        val forPlaybackAfterRebufferMs: Int,
    )

    fun primary(lean: Boolean, metered: Boolean): Durations = when {
        lean -> Durations(
            CONSERVATIVE_MIN_BUFFER_MS,
            CONSERVATIVE_MAX_BUFFER_MS,
            CONSERVATIVE_BUFFER_FOR_PLAYBACK_MS,
            CONSERVATIVE_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
        metered -> Durations(
            PRIMARY_MIN_BUFFER_MS,
            PRIMARY_MAX_BUFFER_MS,
            METERED_BUFFER_FOR_PLAYBACK_MS,
            METERED_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
        else -> Durations(
            PRIMARY_MIN_BUFFER_MS,
            PRIMARY_MAX_BUFFER_MS,
            PRIMARY_BUFFER_FOR_PLAYBACK_MS,
            PRIMARY_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
    }
}
