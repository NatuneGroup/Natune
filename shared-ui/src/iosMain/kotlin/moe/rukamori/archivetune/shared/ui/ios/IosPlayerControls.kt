package moe.rukamori.archivetune.shared.ui.ios

import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.queue.QueueAdvancePolicy
import moe.rukamori.archivetune.shared.domain.queue.QueueRepeatMode
import moe.rukamori.archivetune.shared.domain.repository.QueueState
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.RepeatModeKey
import moe.rukamori.archivetune.shared.domain.settings.SeekExtraSeconds
import moe.rukamori.archivetune.shared.ui.player.PlayerAction
import moe.rukamori.archivetune.shared.ui.player.PlayerNativeControls
import moe.rukamori.archivetune.shared.ui.player.PlayerNativeState
import moe.rukamori.archivetune.shared.ui.player.PlayerRepeat

/** The seek step of one double tap, before the progressive multiplier. The same step as the Android player. */
private const val SEEK_STEP_MS = 5_000L

/** Taps inside this window grow the step while the progressive seek preference is on. The same window as Android. */
private const val SEEK_REPEAT_WINDOW_MS = 1_000L

/** How long the observed state stays subscribed after the player leaves composition. */
private const val STATE_STOP_TIMEOUT_MS = 5_000L

/**
 * The iOS native controls over the one session graph. Skip and repeat go through the queue owner and the settings
 * store, seek goes through the one player, like goes through the library owner, and shuffle goes through the queue
 * owner. The system owns the output volume on iOS, so the volume row stays hidden. Song actions without an owner on
 * this host are reported and never shown as done.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class IosPlayerControls(private val graph: IosSessionGraph) : PlayerNativeControls {
    private val clock = TimeSource.Monotonic
    private var lastSeekAt: TimeSource.Monotonic.ValueTimeMark? = null
    private var seekMultiplier = 1

    private val liked: Flow<Boolean> = graph.queue.state
        .map { queue -> queue.entries.firstOrNull { it.occurrenceId == queue.currentOccurrenceId }?.item?.ref }
        .distinctUntilChanged()
        .flatMapLatest { ref -> if (ref == null) flowOf(false) else graph.libraryContent.isLiked(ref) }

    override val state: StateFlow<PlayerNativeState> = combine(
        graph.queue.state,
        graph.player.snapshot,
        graph.settings.snapshots,
        liked,
    ) { queue, playback, preferences, isLiked ->
        iosNativeState(queue, playback, preferences, isLiked)
    }.stateIn(
        graph.scope,
        SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS),
        iosNativeState(graph.queue.state.value, graph.player.snapshot.value, graph.settings.snapshots.value),
    )

    override fun previous() {
        graph.scope.launch { graph.skipPrevious() }
    }

    override fun next() {
        graph.scope.launch { graph.skipNext() }
    }

    override fun setShuffle(enabled: Boolean) {
        graph.scope.launch {
            try {
                if (!graph.queue.setShuffle(enabled)) graph.report("shuffle was not applied: a radio queue keeps server order")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("shuffle was not applied: ${failure::class.simpleName}")
            }
        }
    }

    override fun setRepeat(repeat: PlayerRepeat) {
        val stored = repeat.toQueueRepeatMode().storedValue
        graph.scope.launch {
            try {
                graph.settings.edit { set(RepeatModeKey, stored) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("repeat was not saved: ${failure::class.simpleName}")
            }
        }
    }

    override fun setVolume(volume: Float) {
        graph.report("the output volume is owned by the system on iOS")
    }

    override fun seekStep(forward: Boolean) {
        val playback = graph.player.snapshot.value ?: return
        if (!playback.isSeekable) return
        val progressive = graph.settings.snapshots.value?.get(SeekExtraSeconds) ?: false
        val previous = lastSeekAt
        val repeated = previous != null && previous.elapsedNow().inWholeMilliseconds < SEEK_REPEAT_WINDOW_MS
        seekMultiplier = if (progressive && repeated) seekMultiplier + 1 else 1
        lastSeekAt = clock.markNow()
        val step = SEEK_STEP_MS * seekMultiplier
        val position = graph.player.positionMs() ?: playback.positionMs
        graph.player.seekTo(iosSeekTarget(position, if (forward) step else -step, playback.durationMs ?: 0L))
    }

    override fun onAction(action: PlayerAction, occurrenceId: String?) {
        when (action) {
            // The root closes the sheet for COLLAPSE before an action reaches the host.
            PlayerAction.COLLAPSE -> Unit
            PlayerAction.LIKE -> toggleLike(occurrenceId)
            else -> graph.report("player action ${action.name} is not connected on the iOS host")
        }
    }

    private fun toggleLike(occurrenceId: String?) {
        val item = graph.queue.state.value.entries.firstOrNull { it.occurrenceId == occurrenceId }?.item ?: return
        graph.scope.launch {
            try {
                val currentlyLiked = graph.libraryContent.isLiked(item.ref).first()
                graph.libraryContent.setLiked(item, !currentlyLiked)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("the like was not saved: ${failure::class.simpleName}")
            }
        }
    }
}

/**
 * The observed native state for [playback]. Previous and next follow the queue owner's own rules: the same successor
 * for the repeat mode in settings, and the same restart threshold as the graph's previous skip.
 */
private fun iosNativeState(
    queue: QueueState,
    playback: PlaybackSnapshot?,
    preferences: PreferenceSnapshot?,
    liked: Boolean = false,
): PlayerNativeState {
    val mode = iosRepeatMode(preferences)
    val current = queue.currentOccurrenceId
    val index = current?.let { queue.playOrder.indexOf(it) } ?: -1
    return PlayerNativeState(
        occurrenceId = playback?.occurrenceId,
        playbackGeneration = playback?.playbackGeneration,
        canPrevious = playback != null && index >= 0 &&
            (index > 0 || playback.positionMs > IosSessionGraph.RESTART_THRESHOLD_MS),
        canNext = playback != null && current != null &&
            QueueAdvancePolicy.successor(mode, queue.playOrder, current) != null,
        canSeek = playback?.isSeekable == true,
        shuffle = queue.shuffleEnabled,
        repeat = mode.toPlayerRepeat(),
        liked = liked,
        volume = null,
        canvasAvailable = false,
        palette = emptyList(),
    )
}

/** The seek target the Android player computes: never before zero, and never past a duration the engine reports. */
private fun iosSeekTarget(currentPositionMs: Long, deltaMs: Long, durationMs: Long): Long {
    val current = currentPositionMs.coerceAtLeast(0L)
    val target = (if (deltaMs > 0L && current > Long.MAX_VALUE - deltaMs) Long.MAX_VALUE else current + deltaMs)
        .coerceAtLeast(0L)
    return if (durationMs > 0L) target.coerceAtMost(durationMs) else target
}

private fun QueueRepeatMode.toPlayerRepeat(): PlayerRepeat = when (this) {
    QueueRepeatMode.OFF -> PlayerRepeat.OFF
    QueueRepeatMode.ONE -> PlayerRepeat.ONE
    QueueRepeatMode.ALL -> PlayerRepeat.ALL
}

private fun PlayerRepeat.toQueueRepeatMode(): QueueRepeatMode = when (this) {
    PlayerRepeat.OFF -> QueueRepeatMode.OFF
    PlayerRepeat.ONE -> QueueRepeatMode.ONE
    PlayerRepeat.ALL -> QueueRepeatMode.ALL
}
