package moe.rukamori.archivetune.desktop.integration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot

/**
 * A pause this long releases the output device even while the window stays up: a deck parked for
 * minutes is indistinguishable from one that never held the device, and the next play reopens it in
 * milliseconds. The delay stays generous so a pause resumed soon never notices the park.
 */
internal const val PAUSED_OUTPUT_RELEASE_DELAY_MS = 15L * 60_000L

/**
 * The shorter grace while the window is effectively off-screen — minimized, hidden to the tray,
 * occluded by the compositor or suspended with the session. Spotify's STOPPED_ON_BACKGROUND fires
 * on the OS background transition itself; the grace only keeps a quick hide-and-show from churning
 * the device open and shut.
 */
internal const val BACKGROUND_OUTPUT_RELEASE_DELAY_MS = 60_000L

/**
 * Whether a paused occurrence may park its output device. Playing, buffering and ended snapshots
 * never park, and a non-seekable live stream keeps the device because its parked position could not
 * be honoured when the device reopens.
 */
internal fun pausedOutputReleasable(snapshot: PlaybackSnapshot?): Boolean =
    snapshot != null && !snapshot.playWhenReady && !snapshot.isEnded &&
        !snapshot.isBuffering && snapshot.isSeekable

/**
 * The inputs a release wait depends on: whether the snapshot can park at all, whether the window is
 * effectively off-screen, and which occurrence is paused. Position ticks of the same paused
 * occurrence share one key, so the wait is armed and cancelled on these edges only.
 */
private fun pausedLifecycleKey(input: Pair<PlaybackSnapshot?, DesktopWindowActivity>) =
    Triple(pausedOutputReleasable(input.first), !input.second.animate, input.first?.occurrenceId)

/**
 * Releases the desktop output device once a pause outlives its grace — [PAUSED_OUTPUT_RELEASE_DELAY_MS]
 * with the window up, [BACKGROUND_OUTPUT_RELEASE_DELAY_MS] while it is effectively off-screen —
 * following Spotify's STOPPED_ON_BACKGROUND behaviour of dropping player resources the session can
 * no longer justify. Playback itself is never touched: the port parks the device and the next play
 * or seek reopens it at the same position, so a resumed pause is indistinguishable from one that
 * kept the device.
 *
 * Each new pause or background edge replaces the pending wait ([collectLatest]); the paused
 * snapshot's position emissions share one key and never restart it. [close] cancels the collector
 * and any armed wait.
 */
class DesktopOutputLifecycle(
    snapshots: StateFlow<PlaybackSnapshot?>,
    activity: StateFlow<DesktopWindowActivity>,
    scope: CoroutineScope,
    private val release: () -> Unit,
    private val pausedDelayMs: Long = PAUSED_OUTPUT_RELEASE_DELAY_MS,
    private val backgroundDelayMs: Long = BACKGROUND_OUTPUT_RELEASE_DELAY_MS,
) : AutoCloseable {
    private val job: Job = scope.launch {
        combine(snapshots, activity, ::Pair)
            .distinctUntilChanged { a, b -> pausedLifecycleKey(a) == pausedLifecycleKey(b) }
            .collectLatest { (snapshot, window) ->
                if (!pausedOutputReleasable(snapshot)) return@collectLatest
                delay(if (window.animate) pausedDelayMs else backgroundDelayMs)
                runCatching { release() }
            }
    }

    override fun close() {
        job.cancel()
    }
}
