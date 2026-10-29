package moe.rukamori.archivetune.shared.domain.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Playback-thread confined progress state, shared by Media3 and the portable snapshot driver. Reads and writes
 * share one FIFO, so replaying an episode cannot race its preceding flush. A slow resume read never blocks engine
 * events, and stale reads cannot seek a later occurrence. [finish] queues the final flush before closing the FIFO.
 */
class PodcastProgressRecorder(
    scope: CoroutineScope,
    private val store: PodcastProgressStore,
    private val seekTo: (Long) -> Unit,
    private val report: (String) -> Unit,
    private val nowMs: () -> Long,
) {
    private enum class Phase { READING, READY, SEEKING, TRACKING }
    private class Episode(val first: PodcastProgressSample) {
        var latest = first
        var phase = Phase.READING
        var saved: PodcastProgress? = null
        var queued: PodcastProgress? = null
        var writeTime = first.monotonicMs
        var discontinuity = first.discontinuity
        var seekGeneration = first.discontinuity
        var closed = false
        var userSeek = false
        var hasAdvanced = false
    }
    private sealed interface Command {
        class Read(val episode: Episode) : Command
        class Write(val episode: Episode, val row: PodcastProgress) : Command
    }
    private val pending = Channel<Command>(Channel.UNLIMITED)
    private var episode: Episode? = null
    private var stopped = false

    private val writer = scope.launch {
        for (command in pending) {
            try {
                when (command) {
                    is Command.Write -> store.write(command.row)
                    is Command.Read -> read(command.episode)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report("podcast progress storage failed: ${failure::class.simpleName}")
                if (command is Command.Write && command.episode.queued === command.row) {
                    // A paused episode must be able to retry the same row after a transient storage failure.
                    command.episode.queued = null
                }
                if (command is Command.Read) {
                    command.episode.phase = Phase.READY
                    if (!command.episode.closed) track(command.episode)
                }
            }
        }
    }

    fun observe(sample: PodcastProgressSample?) {
        if (stopped) return
        val previous = episode
        if (sample == null || previous == null || previous.first.occurrenceId != sample.occurrenceId ||
            previous.first.episodeId != sample.episodeId
        ) {
            endEpisode()
            if (sample == null) return
            val opened = Episode(sample)
            episode = opened
            pending.trySend(Command.Read(opened))
        }
        val open = episode ?: return
        val current = sample ?: return
        val moved = current.discontinuity == open.latest.discontinuity && current.positionMs > open.latest.positionMs
        open.hasAdvanced = open.hasAdvanced || (current.positionMs > 0L && (current.advancing || moved))
        open.latest = current.copy(durationMs = current.durationMs ?: open.latest.durationMs)
        if (open.phase == Phase.READING && sample.discontinuity != open.first.discontinuity) open.userSeek = true
        track(open)
    }

    /** Captures a pause/seek/stop immediately; unchanged rows are not written over and over while paused. */
    fun flush() {
        episode?.let(::queue)
    }

    /** Non-blocking service-stop boundary. Its returned job completes only after all queued storage work drains. */
    fun finish(): Job {
        if (!stopped) {
            endEpisode()
            stopped = true
            pending.close()
        }
        return writer
    }

    suspend fun close() {
        finish().join()
    }

    private suspend fun read(open: Episode) {
        open.saved = store.read(open.first.episodeId)
        open.queued = open.saved
        open.phase = Phase.READY
        if (open.closed) {
            // A new, very short listen may end before its first read returns. Never replace an unresumed bookmark.
            if (open.saved?.resumePositionMs == null || open.saved?.resumePositionMs == 0L ||
                open.userSeek || open.latest.ended
            ) {
                row(open)?.let { store.write(it) }
            }
        } else {
            track(open)
        }
    }

    private fun track(open: Episode) {
        val sample = open.latest
        when (open.phase) {
            Phase.READING -> return
            Phase.READY -> {
                val saved = open.saved
                val resume = saved?.resumePositionMs ?: 0L
                val target = sample.durationMs?.takeIf { it > 0L }?.let { resume.coerceAtMost(it) } ?: resume
                val needsSeek = !open.userSeek && saved != null &&
                    (target > 0L || (saved.finished && sample.positionMs > 0L))
                if (needsSeek) {
                    if (!sample.seekable) return
                    open.phase = Phase.SEEKING
                    open.seekGeneration = sample.discontinuity
                    open.hasAdvanced = false
                    // Set the phase first: Media3 may acknowledge a seek synchronously.
                    seekTo(target)
                    return
                }
                open.phase = Phase.TRACKING
            }
            Phase.SEEKING -> {
                if (sample.discontinuity == open.seekGeneration) return
                open.phase = Phase.TRACKING
                open.discontinuity = sample.discontinuity
                open.writeTime = sample.monotonicMs
            }
            Phase.TRACKING -> Unit
        }
        val discontinuous = sample.discontinuity != open.discontinuity
        open.discontinuity = sample.discontinuity
        if (discontinuous) open.userSeek = true
        if (!sample.advancing || discontinuous || sample.ended ||
            sample.monotonicMs - open.writeTime >= PodcastProgressPolicy.WRITE_EVERY_MS
        ) queue(open)
    }

    private fun endEpisode() {
        episode?.let {
            queue(it)
            it.closed = true
        }
        episode = null
    }

    private fun row(open: Episode): PodcastProgress? {
        val sample = open.latest
        if (!open.hasAdvanced && !open.userSeek && !sample.ended) return null
        return PodcastProgressPolicy.row(
            sample.episodeId, sample.positionMs, sample.durationMs, sample.ended, nowMs(),
        )
    }

    private fun queue(open: Episode) {
        if (open.phase != Phase.TRACKING) return
        val row = row(open) ?: return
        val last = open.queued
        if (last != null && row.positionMs == last.positionMs && row.durationMs == last.durationMs && row.finished == last.finished) return
        if (pending.trySend(Command.Write(open, row)).isSuccess) {
            open.queued = row
            open.writeTime = open.latest.monotonicMs
        }
    }
}
