package moe.rukamori.archivetune.shared.ui.host

import kotlin.math.abs
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.data.persistence.PortablePodcastProgressRow
import moe.rukamori.archivetune.shared.data.persistence.RoomPodcastProgressStore
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.QueueState

/**
 * Remembers where each podcast episode stopped and resumes a replay there. It reads only the snapshots the player
 * publishes, so there is no ticker: a row is queued once the position has moved [WRITE_EVERY_MS] since the last write,
 * and again on pause and on a track change. Rows go through one unbounded channel, so writes keep their order and a
 * finished episode's row is never replaced by the row of the next episode before it is written. A replay waits until
 * the saved position is read and the seek to it lands, so the first position of the track never overwrites the
 * progress it is about to resume.
 */
class PodcastProgressDriver(
    private val scope: CoroutineScope,
    private val player: MusicPlayerController,
    private val queue: StateFlow<QueueState>,
    private val store: RoomPodcastProgressStore,
    private val report: (String) -> Unit,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private enum class Phase { READING, SEEKING, TRACKING }

    private val pending = Channel<PortablePodcastProgressRow>(Channel.UNLIMITED)
    private var occurrenceId: String? = null
    private var episodeKey: String? = null
    private var resumeRead: Deferred<Long>? = null
    private var phase = Phase.READING
    private var seekGeneration = 0L
    private var durationMs: Long? = null
    private var position = 0L
    private var queuedAt = 0L

    // Declared after every field it reads, because the collector may run before the constructor returns.
    private val writer = scope.launch {
        for (row in pending) {
            try {
                store.write(row)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report("podcast progress write failed: ${failure::class.simpleName}")
            }
        }
    }

    private val job = scope.launch {
        player.snapshot.collect { snapshot ->
            try {
                observe(snapshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report("podcast progress failed: ${failure::class.simpleName}")
            }
        }
    }

    /** Stops observing, queues the open episode's position, and waits until the queued writes land. */
    suspend fun close() {
        job.cancelAndJoin()
        flushOpenEpisode()
        pending.close()
        writer.join()
    }

    private fun observe(snapshot: PlaybackSnapshot?) {
        if (snapshot == null) {
            flushOpenEpisode()
            forgetOccurrence()
            return
        }
        if (snapshot.occurrenceId != occurrenceId) {
            flushOpenEpisode()
            forgetOccurrence()
            occurrenceId = snapshot.occurrenceId
        }
        // The queue can publish an occurrence after its first snapshot, so a non-episode is retried on the next one.
        val key = episodeKey ?: openEpisode(snapshot.occurrenceId) ?: return
        if (phase == Phase.READING) {
            val read = resumeRead ?: return
            if (!read.isCompleted) return
            val resumeAt = read.getCompleted()
            if (resumeAt > 0L) {
                // The engine ignores a seek it cannot make yet, and that would leave the replay waiting forever.
                if (!snapshot.isSeekable) return
                seekGeneration = snapshot.discontinuityGeneration
                phase = Phase.SEEKING
                player.seekTo(resumeAt)
                return
            }
            phase = Phase.TRACKING
        }
        if (phase == Phase.SEEKING) {
            // A seek is a discontinuity, so its generation changes once the engine has applied it.
            if (snapshot.discontinuityGeneration == seekGeneration) return
            phase = Phase.TRACKING
        }
        durationMs = snapshot.durationMs ?: durationMs
        position = player.positionMs() ?: snapshot.positionMs
        if (!snapshot.isAudioAdvancing) {
            flushOpenEpisode()
        } else if (abs(position - queuedAt) >= WRITE_EVERY_MS) {
            queue(key, position)
        }
    }

    /** Queues the open episode's position when it moved since the last queued write. */
    private fun flushOpenEpisode() {
        val key = episodeKey ?: return
        if (phase != Phase.TRACKING || position == queuedAt) return
        queue(key, position)
    }

    private fun queue(key: String, at: Long) {
        queuedAt = at
        pending.trySend(rowFor(key, at))
    }

    private fun rowFor(key: String, at: Long): PortablePodcastProgressRow {
        val duration = durationMs
        val finished = duration != null && duration > 0L &&
            (at * 100L >= duration * FINISH_PERCENT || duration - at <= FINISH_TAIL_MS)
        return PortablePodcastProgressRow(
            episodeId = key,
            // A finished episode restarts from the top when it is played again, so it keeps no resume point.
            positionMs = if (finished) 0L else at,
            durationMs = duration,
            finished = finished,
            updatedAtMs = nowMs(),
        )
    }

    /** Starts the saved-position read when the queue holds [occurrence] as a podcast episode. */
    private fun openEpisode(occurrence: String): String? {
        val key = episodeKeyOf(occurrence) ?: return null
        episodeKey = key
        resumeRead = readResume(key)
        return key
    }

    /** The saved position to resume at, zero for a finished, unknown or unreadable episode. */
    private fun readResume(key: String): Deferred<Long> = scope.async {
        try {
            val row = store.read(key)
            if (row == null || row.finished) 0L else row.positionMs
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            report("podcast progress read failed: ${failure::class.simpleName}")
            0L
        }
    }

    private fun forgetOccurrence() {
        occurrenceId = null
        episodeKey = null
        resumeRead?.cancel()
        resumeRead = null
        phase = Phase.READING
        seekGeneration = 0L
        durationMs = null
        position = 0L
        queuedAt = 0L
    }

    /** The provider-qualified key of [occurrence] when it is a podcast episode, null for every other item. */
    private fun episodeKeyOf(occurrence: String): String? {
        val ref = queue.value.entries.firstOrNull { it.occurrenceId == occurrence }?.item?.ref ?: return null
        return if (ref.kind == ContentKind.EPISODE) "${ref.provider}:${ref.id}" else null
    }

    private companion object {
        const val WRITE_EVERY_MS = 5_000L

        /** An episode counts as heard once this share of it has played... */
        const val FINISH_PERCENT = 95L

        /** ...or once it is this close to the end, which covers closing credits and trailing silence. */
        const val FINISH_TAIL_MS = 30_000L
    }
}
