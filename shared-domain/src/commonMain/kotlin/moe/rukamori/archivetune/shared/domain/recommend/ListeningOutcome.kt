package moe.rukamori.archivetune.shared.domain.recommend

/**
 * How one listen ended, classified from the only two facts every host records: the played time and, when the provider
 * reported one, the track duration.
 *
 * The boundaries are the published skip distribution, not a product guess: a quarter of plays are abandoned in the
 * first five seconds, a third within thirty, and just under half before the track finishes.
 * https://musicmachinery.com/2014/05/02/the-skip/
 * https://www.aicrowd.com/challenges/spotify-sequential-skip-prediction-challenge
 *
 * A play that reaches the advertised duration within five seconds, or at least ninety percent of it, is complete.
 * The tolerance is checked before the quick-skip floor so a three-second jingle played to its end is not a skip.
 * A duration of [UNKNOWN_DURATION_MS] (the Android `SongEntity.duration` default) or a non-positive value means there
 * is no completion ratio at all: such a play can only be partial once it passes thirty seconds, and it is never
 * reported as [COMPLETED].
 */
enum class ListeningOutcome {
    COMPLETED,
    PARTIAL,
    EARLY_SKIP,
    QUICK_SKIP;

    /** The signed weight the profile gives this outcome. Skips are negative so the profile is skip-aware. */
    val affinityWeight: Double
        get() =
            when (this) {
                COMPLETED -> 1.0
                PARTIAL -> 0.4
                EARLY_SKIP -> -0.3
                QUICK_SKIP -> -0.8
            }

    companion object {
        const val QUICK_SKIP_MS = 5_000L
        const val EARLY_SKIP_MS = 30_000L
        const val EARLY_SKIP_RATIO = 0.5
        const val COMPLETION_RATIO = 0.9
        const val COMPLETION_TOLERANCE_MS = 5_000L

        /** The baseline's `SongEntity.duration` sentinel for a duration the provider did not report. */
        const val UNKNOWN_DURATION_MS = -1L

        fun classify(playedMs: Long, durationMs: Long?): ListeningOutcome {
            val played = playedMs.coerceAtLeast(0L)
            val duration = durationMs?.takeIf { it > 0L }
            if (duration != null) {
                val ratio = played.toDouble() / duration
                val endedWithinTolerance = played > 0L && duration - played <= COMPLETION_TOLERANCE_MS
                if (ratio >= COMPLETION_RATIO || endedWithinTolerance) return COMPLETED
            }
            if (played < QUICK_SKIP_MS) return QUICK_SKIP
            if (duration == null) return if (played < EARLY_SKIP_MS) EARLY_SKIP else PARTIAL
            val ratio = played.toDouble() / duration
            return if (played < EARLY_SKIP_MS && ratio < EARLY_SKIP_RATIO) EARLY_SKIP else PARTIAL
        }
    }
}
