/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.data.persistence.RoomListeningHistory
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.QueueState
import moe.rukamori.archivetune.shared.domain.settings.HistoryDuration
import moe.rukamori.archivetune.shared.domain.settings.PauseListenHistoryKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot

/**
 * Records one listen when its play time crosses the history threshold, and writes that play time again when the listen
 * ends, changes or is paused. The driver reads only the snapshots the player already publishes. Play time grows between
 * two consecutive snapshots of one playback generation that both report audio advancing, so a pause, a seek, a stall or
 * a discontinuity is never counted as listening. There is no second clock and no progress ticker.
 */
internal class DesktopListenDriver(
    scope: CoroutineScope,
    private val player: MusicPlayerController,
    private val queue: StateFlow<QueueState>,
    private val history: RoomListeningHistory,
    private val preferences: StateFlow<PreferenceSnapshot?>,
    private val report: (String) -> Unit,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private var previous: PlaybackSnapshot? = null
    private var tracked: TrackedListen? = null

    // Declared after every field it reads, because the collector may run before the constructor returns.
    private val job = scope.launch {
        player.snapshot.collect { snapshot ->
            try {
                observe(snapshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report("listen history failed: ${failure.javaClass.simpleName}")
            }
        }
    }

    /** Stops observing, then writes the open listen's play time so shutdown keeps what was heard. */
    suspend fun close() {
        job.cancelAndJoin()
        finishTracked()
    }

    private suspend fun observe(snapshot: PlaybackSnapshot?) {
        val before = previous
        previous = snapshot
        if (snapshot == null) {
            finishTracked()
            return
        }
        if (preferences.value?.get(PauseListenHistoryKey) == true) {
            finishTracked()
            return
        }
        val open = tracked
        if (open == null || open.occurrenceId != snapshot.occurrenceId || open.playbackGeneration != snapshot.playbackGeneration) {
            finishTracked()
            tracked = startTracking(snapshot)
        }
        val current = tracked ?: return
        if (before != null && before.occurrenceId == snapshot.occurrenceId &&
            before.playbackGeneration == snapshot.playbackGeneration &&
            before.discontinuityGeneration == snapshot.discontinuityGeneration &&
            before.isAudioAdvancing && snapshot.isAudioAdvancing
        ) {
            val elapsedMs = (snapshot.sampleMonotonicNanos - before.sampleMonotonicNanos) / NANOS_PER_MS
            if (elapsedMs in 1L..MAX_SAMPLE_GAP_MS) current.playedMs += elapsedMs
        }
        if (snapshot.isEnded) {
            finishTracked()
            return
        }
        if (current.eventId == null && current.playedMs >= current.thresholdMs) {
            current.eventId = history.startListen(current.occurrence, nowMs(), current.playedMs)
        }
    }

    /** The occurrence is tracked only when the queue still holds it, it is not a podcast and history is not paused. */
    private fun startTracking(snapshot: PlaybackSnapshot): TrackedListen? {
        val settings = preferences.value ?: return null
        val occurrence = queue.value.entries.firstOrNull { it.occurrenceId == snapshot.occurrenceId } ?: return null
        val kind = occurrence.item.ref.kind
        if (kind == ContentKind.PODCAST || kind == ContentKind.EPISODE) return null
        val seconds = (settings[HistoryDuration] ?: HISTORY_DURATION_DEFAULT_SECONDS)
            .coerceIn(HISTORY_DURATION_MIN_SECONDS, HISTORY_DURATION_MAX_SECONDS)
        return TrackedListen(
            occurrence = occurrence,
            occurrenceId = snapshot.occurrenceId,
            playbackGeneration = snapshot.playbackGeneration,
            thresholdMs = seconds * MS_PER_SECOND,
        )
    }

    private suspend fun finishTracked() {
        val closing = tracked ?: return
        tracked = null
        val eventId = closing.eventId ?: return
        history.finishListen(eventId, closing.playedMs)
    }

    private class TrackedListen(
        val occurrence: ContentOccurrence,
        val occurrenceId: String,
        val playbackGeneration: Long,
        val thresholdMs: Long,
    ) {
        var playedMs: Long = 0L
        var eventId: String? = null
    }

    private companion object {
        /** The baseline's history duration default and bounds, in seconds. The setting is clamped to them on read. */
        const val HISTORY_DURATION_DEFAULT_SECONDS = 30
        const val HISTORY_DURATION_MIN_SECONDS = 1
        const val HISTORY_DURATION_MAX_SECONDS = 60
        const val MS_PER_SECOND = 1_000L
        const val NANOS_PER_MS = 1_000_000L

        /** A larger gap means the collector fell behind a stall; the time inside it is not counted as heard. */
        const val MAX_SAMPLE_GAP_MS = 5_000L
    }
}
