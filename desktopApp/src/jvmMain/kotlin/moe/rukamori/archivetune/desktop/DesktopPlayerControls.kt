package moe.rukamori.archivetune.desktop

import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import moe.rukamori.archivetune.shared.data.repository.QueueHandoffState
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.queue.QueueAdvancePolicy
import moe.rukamori.archivetune.shared.domain.queue.QueueRepeatMode
import moe.rukamori.archivetune.shared.domain.repository.QueueState
import moe.rukamori.archivetune.shared.domain.settings.PlayerVolumeKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.RepeatModeKey
import moe.rukamori.archivetune.shared.domain.settings.SeekExtraSeconds
import moe.rukamori.archivetune.shared.ui.player.PlayerAction
import moe.rukamori.archivetune.shared.ui.player.PlayerActionSupport
import moe.rukamori.archivetune.shared.ui.player.PlayerHandoff
import moe.rukamori.archivetune.shared.ui.player.PlayerNativeControls
import moe.rukamori.archivetune.shared.ui.player.PlayerNativeState
import moe.rukamori.archivetune.shared.ui.player.PlayerRepeat

/** The seek step of one double tap, before the progressive multiplier. The same step as the Android player. */
private const val SEEK_STEP_MS = 5_000L

/** Taps inside this window grow the step while the progressive seek preference is on. The same window as Android. */
private const val SEEK_REPEAT_WINDOW_MS = 1_000L

private const val NANOS_PER_MILLI = 1_000_000L

/** How long the observed state stays subscribed after the player leaves composition. */
private const val STATE_STOP_TIMEOUT_MS = 5_000L

/**
 * The desktop's native controls over the one session graph. Skip and repeat go through the queue owner and the settings
 * store, seek goes through the one player, like goes through the library owner, and volume goes to the engine output
 * and the stored player volume. Shuffle goes through the queue owner. Song actions without an owner on this host are
 * reported and never shown as done.
 */
internal class DesktopPlayerControls(
    private val graph: DesktopSessionGraph,
    private val sleepSheet: DesktopSleepTimerSheetState,
    private val playerMenu: DesktopPlayerMenuState,
    /** The installed SpatialFlow typeface. The shared player draws its title in it while that style is selected. */
    private val spatialFont: StateFlow<FontFamily?> = MutableStateFlow(null),
) : PlayerNativeControls {
    private var lastSeekAt = 0L
    private var seekMultiplier = 1

    /** Liked state of the queue's current song, read from the owner's liked list. Not liked when no song is current. */
    private val liked: Flow<Boolean> = graph.queue.state
        .map { queue -> queue.entries.firstOrNull { it.occurrenceId == queue.currentOccurrenceId }?.item?.ref }
        .distinctUntilChanged()
        .flatMapLatest { ref -> if (ref == null) flowOf(false) else graph.libraryContent.isLiked(ref) }

    /**
     * The queue, playback, settings, like and handoff owners as one observed state. The typeface is combined afterwards
     * because it is not part of the queue's state and changes only when the user installs or removes it.
     */
    private val observed: Flow<PlayerNativeState> = combine(
        graph.queue.state,
        graph.player.snapshot,
        graph.settings.snapshots,
        liked,
        graph.handoff,
    ) { queue, playback, preferences, isLiked, handoff ->
        desktopNativeState(queue, playback, preferences, isLiked, handoff)
    }

    override val state: StateFlow<PlayerNativeState> = combine(observed, spatialFont) { native, font ->
        native.copy(spatialFont = font)
    }.stateIn(
        graph.scope,
        SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS),
        desktopNativeState(graph.queue.state.value, graph.player.snapshot.value, graph.settings.snapshots.value),
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
                graph.report("shuffle was not applied: ${failure.javaClass.simpleName}")
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
                graph.report("repeat was not saved: ${failure.javaClass.simpleName}")
            }
        }
    }

    override fun setVolume(volume: Float) {
        graph.setVolume(volume)
    }

    override fun seekStep(forward: Boolean) {
        val playback = graph.player.snapshot.value ?: return
        if (!playback.isSeekable) return
        val now = System.nanoTime() / NANOS_PER_MILLI
        val progressive = graph.settings.snapshots.value?.get(SeekExtraSeconds) ?: false
        seekMultiplier = if (progressive && now - lastSeekAt < SEEK_REPEAT_WINDOW_MS) seekMultiplier + 1 else 1
        lastSeekAt = now
        val step = SEEK_STEP_MS * seekMultiplier
        val position = graph.player.positionMs() ?: playback.positionMs
        graph.player.seekTo(desktopSeekTarget(position, if (forward) step else -step, playback.durationMs ?: 0L))
    }

    override fun onAction(action: PlayerAction, occurrenceId: String?) {
        val handled = desktopPlayerAction(
            action = action,
            occurrenceId = occurrenceId,
            like = ::toggleLike,
            share = ::share,
            more = ::openMenu,
            sleep = { sleepSheet.open = true },
        )
        if (!handled) graph.report("player action ${action.name} is not connected on the desktop host")
    }

    /** Opens the player menu on the song of [occurrenceId]. An occurrence the queue no longer holds opens nothing. */
    private fun openMenu(occurrenceId: String?) {
        val item = graph.queue.state.value.entries.firstOrNull { it.occurrenceId == occurrenceId }?.item ?: return
        playerMenu.open(item)
    }

    /**
     * The Now Playing sheet's Share: copies the playing song's catalogue link, as the mobile hosts' share sheet does,
     * and says what happened over the window. A local file or a provider catalog with no public link copies nothing.
     */
    private fun share(occurrenceId: String?) {
        val item = graph.queue.state.value.entries.firstOrNull { it.occurrenceId == occurrenceId }?.item ?: return
        shareDesktopSong(item.ref, graph::notify)
    }

    /**
     * Likes or unlikes the song of [occurrenceId] through the library owner. The liked state is read again at the tap,
     * so a tap never flips a value observed before the list changed. A tap on an occurrence the queue no longer holds
     * does nothing.
     */
    private fun toggleLike(occurrenceId: String?) {
        val item = graph.queue.state.value.entries.firstOrNull { it.occurrenceId == occurrenceId }?.item ?: return
        graph.scope.launch {
            try {
                val currentlyLiked = graph.libraryContent.isLiked(item.ref).first()
                graph.libraryContent.setLiked(item, !currentlyLiked)
                // A like changes only the button's own tint, so the log carries the state change the way a failure does.
                graph.report(if (currentlyLiked) "the playing song is no longer liked" else "the playing song is liked")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("the like was not saved: ${failure.javaClass.simpleName}")
            }
        }
    }
}

/**
 * The desktop's answer for [action]: [like], [share], [more] and [sleep] are the handlers this host owns, and COLLAPSE
 * is the root's own collapse, which closes the sheet before an action reaches the host. False means this host has no
 * owner for the action, so a player surface offers no control for it.
 */
internal fun desktopPlayerAction(
    action: PlayerAction,
    occurrenceId: String?,
    like: (String?) -> Unit,
    share: (String?) -> Unit,
    more: (String?) -> Unit,
    sleep: () -> Unit,
): Boolean = when (action) {
    PlayerAction.COLLAPSE -> true
    PlayerAction.LIKE -> { like(occurrenceId); true }
    PlayerAction.SHARE -> { share(occurrenceId); true }
    PlayerAction.MORE -> { more(occurrenceId); true }
    PlayerAction.SLEEP -> { sleep(); true }
    else -> false
}

/** The actions the typed desktop overrides perform: previous, next, shuffle, repeat and both seek steps. */
internal val DESKTOP_PLAYER_TRANSPORT_ACTIONS: Set<PlayerAction> = setOf(
    PlayerAction.PREVIOUS,
    PlayerAction.NEXT,
    PlayerAction.SHUFFLE,
    PlayerAction.REPEAT,
    PlayerAction.SEEK_BACKWARD,
    PlayerAction.SEEK_FORWARD,
)

/**
 * Every player action this host can perform. Title and artist taps would need a song or artist page route from the
 * player, and the output picker is the Android audio route's, so those stay out and the shared player hides their
 * controls here instead of showing a tap whose only effect could be a diagnostic.
 */
internal val DESKTOP_PLAYER_ACTIONS: Set<PlayerAction> = PlayerAction.entries
    .filter { it in DESKTOP_PLAYER_TRANSPORT_ACTIONS || desktopPlayerAction(it, null, {}, {}, {}, {}) }
    .toSet()

/** One support value for the whole session, so an unchanged capability set never by itself makes a new player state. */
internal val DESKTOP_PLAYER_SUPPORT = PlayerActionSupport(DESKTOP_PLAYER_ACTIONS)

/**
 * The observed native state for [playback]. Previous and next follow the queue owner's own rules: the same successor
 * for the repeat mode in settings, and the same restart threshold as the graph's previous skip. [liked] is the owner's
 * liked state for the current song, and it reads as not liked until that list has been read. [spatialFont] is the
 * installed optional typeface, or null while the player keeps the default family.
 */
internal fun desktopNativeState(
    queue: QueueState,
    playback: PlaybackSnapshot?,
    preferences: PreferenceSnapshot?,
    liked: Boolean = false,
    handoff: QueueHandoffState? = null,
    spatialFont: FontFamily? = null,
): PlayerNativeState {
    val mode = repeatModeOf(preferences)
    val current = queue.currentOccurrenceId
    val index = current?.let { queue.playOrder.indexOf(it) } ?: -1
    return PlayerNativeState(
        occurrenceId = playback?.occurrenceId,
        playbackGeneration = playback?.playbackGeneration,
        canPrevious = playback != null && index >= 0 &&
            (index > 0 || playback.positionMs > DesktopSessionGraph.RESTART_THRESHOLD_MS),
        canNext = playback != null && current != null &&
            QueueAdvancePolicy.successor(mode, queue.playOrder, current) != null,
        canSeek = playback?.isSeekable == true,
        shuffle = queue.shuffleEnabled,
        repeat = mode.toPlayerRepeat(),
        liked = liked,
        volume = preferences?.get(PlayerVolumeKey)?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f,
        canvasAvailable = false,
        palette = emptyList(),
        spatialFont = spatialFont,
        handoff = handoff?.takeIf { it.fromOccurrenceId == playback?.occurrenceId }?.let {
            PlayerHandoff(it.fromOccurrenceId, it.toOccurrenceId, it.fadeStartPositionMs, it.overlapMs)
        },
        actionSupport = DESKTOP_PLAYER_SUPPORT,
    )
}

/** The seek target the Android player computes: never before zero, and never past a duration the engine reports. */
internal fun desktopSeekTarget(currentPositionMs: Long, deltaMs: Long, durationMs: Long): Long {
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
