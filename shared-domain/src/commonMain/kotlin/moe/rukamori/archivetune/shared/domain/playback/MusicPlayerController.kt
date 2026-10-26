package moe.rukamori.archivetune.shared.domain.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The app-facing player. The UI and queue logic send commands here and read one anchored
 * [snapshot]; they never talk to an engine directly.
 *
 * The controller keeps the newest snapshot by [PlaybackClock.accept], so a stale observation that
 * arrives late cannot move the displayed position backwards or resurrect a previous occurrence.
 */
class MusicPlayerController(
    private val port: PlaybackPort,
    scope: CoroutineScope,
    private val clock: MonotonicClock = SystemMonotonicClock,
) {
    private val anchor = MutableStateFlow<PlaybackSnapshot?>(null)

    /** The accepted snapshot that [positionMs] interpolates from. Null before the first load. */
    val snapshot: StateFlow<PlaybackSnapshot?> = anchor.asStateFlow()

    /** Failures forwarded from the port, for error UI and retry decisions. */
    val failures: SharedFlow<PlaybackFailure> = port.failures

    private val collector: Job = scope.launch {
        port.snapshots.collect { incoming ->
            if (incoming != null) anchor.update { current -> PlaybackClock.accept(current, incoming) }
        }
    }

    fun load(request: PlaybackRequest, startPositionMs: Long = 0L, playWhenReady: Boolean = true) {
        port.submit(PlaybackCommand.Load(request, startPositionMs, playWhenReady))
    }

    fun play() = port.submit(PlaybackCommand.Play)

    fun pause() = port.submit(PlaybackCommand.Pause)

    fun seekTo(positionMs: Long) = port.submit(PlaybackCommand.SeekTo(positionMs))

    fun setPlaybackSpeed(speed: Float) = port.submit(PlaybackCommand.SetSpeed(speed))

    fun setPlaybackPitch(pitch: Float) = port.submit(PlaybackCommand.SetPitch(pitch))

    fun stop() = port.submit(PlaybackCommand.Stop)

    /** The position at [nowNanos] on the [MonotonicClock] base, or null before the first load. */
    fun positionMs(nowNanos: Long = clock.nowNanos()): Long? =
        anchor.value?.let { PlaybackClock.positionMs(it, nowNanos) }

    /** Stops collecting and closes the port. The controller must not be used afterwards. */
    fun close() {
        collector.cancel()
        port.close()
    }
}
