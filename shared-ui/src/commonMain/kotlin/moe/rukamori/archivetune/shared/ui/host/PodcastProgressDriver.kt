package moe.rukamori.archivetune.shared.ui.host

import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.playback.PodcastProgressRecorder
import moe.rukamori.archivetune.shared.domain.playback.PodcastProgressSample
import moe.rukamori.archivetune.shared.domain.playback.PodcastProgressStore
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.QueueState

/**
 * Adapts the real desktop/iOS snapshots to the same recorder Android uses. Queue changes also recheck the current
 * observation: its episode metadata can arrive after the first engine snapshot, including while paused.
 */
class PodcastProgressDriver(
    scope: CoroutineScope,
    private val player: MusicPlayerController,
    private val queue: StateFlow<QueueState>,
    store: PodcastProgressStore,
    report: (String) -> Unit,
    nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val recorder = PodcastProgressRecorder(scope, store, player::seekTo, report, nowMs)
    private val job = scope.launch {
        combine(player.snapshot, queue) { snapshot, _ -> snapshot }.collect { snapshot ->
            recorder.observe(snapshot?.sample())
        }
    }

    /** Stops observing, queues the open episode's position, and waits until the queued writes land. */
    suspend fun close() {
        job.cancelAndJoin()
        recorder.observe(player.snapshot.value?.sample())
        recorder.close()
    }

    private fun PlaybackSnapshot.sample(): PodcastProgressSample? {
        val ref = queue.value.entries.firstOrNull { it.occurrenceId == occurrenceId }?.item?.ref ?: return null
        if (ref.kind != ContentKind.EPISODE) return null
        return PodcastProgressSample(
            occurrenceId = "$playbackGeneration:$occurrenceId",
            episodeId = "${ref.provider}:${ref.id}",
            positionMs = player.positionMs() ?: positionMs,
            durationMs = durationMs,
            advancing = isAudioAdvancing,
            seekable = isSeekable,
            discontinuity = discontinuityGeneration,
            monotonicMs = sampleMonotonicNanos / 1_000_000L,
            ended = isEnded,
        )
    }
}
