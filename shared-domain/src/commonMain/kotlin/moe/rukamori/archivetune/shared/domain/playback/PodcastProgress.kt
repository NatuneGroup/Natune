package moe.rukamori.archivetune.shared.domain.playback

import kotlinx.coroutines.flow.Flow

data class PodcastProgress(
    val episodeId: String,
    val positionMs: Long,
    val durationMs: Long?,
    val finished: Boolean,
    val updatedAtMs: Long,
) {
    val resumePositionMs: Long get() = if (finished) 0L else positionMs.coerceAtLeast(0L)
    val fraction: Float? get() = durationMs?.takeIf { it > 0L }?.let {
        if (finished) 1f else (positionMs.toDouble() / it).toFloat().coerceIn(0f, 1f)
    }
}

interface PodcastProgressStore {
    suspend fun read(episodeId: String): PodcastProgress?
    suspend fun write(progress: PodcastProgress)
    fun observeAll(): Flow<List<PodcastProgress>>
}

object PodcastProgressPolicy {
    const val WRITE_EVERY_MS = 5_000L

    fun row(episodeId: String, positionMs: Long, durationMs: Long?, ended: Boolean, nowMs: Long): PodcastProgress {
        val duration = durationMs?.takeIf { it > 0L }
        val position = positionMs.coerceAtLeast(0L).let { if (duration == null) it else it.coerceAtMost(duration) }
        // Subtraction avoids overflow for long/invalid durations. Zero is not a played short episode.
        val finished = ended || (position > 0L && duration != null &&
            (position >= duration - duration / 20 || duration - position <= 30_000L))
        return PodcastProgress(episodeId, if (finished) 0L else position, duration, finished, nowMs)
    }
}

/** Real engine observations only. The occurrence includes repeats; [episodeId] is provider-qualified. */
data class PodcastProgressSample(
    val occurrenceId: String,
    val episodeId: String,
    val positionMs: Long,
    val durationMs: Long?,
    val advancing: Boolean,
    val seekable: Boolean,
    val discontinuity: Long,
    val monotonicMs: Long,
    val ended: Boolean = false,
)
