package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.flow.StateFlow
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot

/** Observed native capabilities, not optimistic UI intent or another queue/playback owner. */
data class PlayerNativeState(
    val occurrenceId: String?,
    val playbackGeneration: Long?,
    val canPrevious: Boolean,
    val canNext: Boolean,
    val canSeek: Boolean,
    val shuffle: Boolean,
    val repeat: PlayerRepeat,
    val liked: Boolean,
    val volume: Float?,
    val canvasAvailable: Boolean,
    val palette: List<Color>,
    val spatialFont: FontFamily? = null,
    val handoff: PlayerHandoff? = null,
    /** The actions this host answers; a surface renders only these, so it cannot show a dead control. */
    val actionSupport: PlayerActionSupport = PlayerActionSupport.ALL,
)

/**
 * The native/session owner adapts its existing controls here. In particular, previous/repeat/shuffle
 * and progressive seek retain that owner's policies; the player never rebuilds or reorders a queue.
 * [onAction] receives an occurrence, not a media id, for menus/navigation/share/like.
 */
interface PlayerNativeControls {
    val state: StateFlow<PlayerNativeState>
    fun previous()
    fun next()
    fun setShuffle(enabled: Boolean)
    fun setRepeat(repeat: PlayerRepeat)
    fun setVolume(volume: Float)
    fun seekStep(forward: Boolean)
    fun onAction(action: PlayerAction, occurrenceId: String?)
    /** Hosts may own a separate lyrics action menu; the default retains the existing real More action. */
    fun onLyricsMenu(occurrenceId: String?) = onAction(PlayerAction.MORE, occurrenceId)
}

internal fun PlayerNativeState.matches(playback: PlaybackSnapshot?): Boolean =
    playback != null && occurrenceId == playback.occurrenceId && playbackGeneration == playback.playbackGeneration

fun PlayerState.withNativeState(native: PlayerNativeState, reducedMotion: Boolean): PlayerState {
    val matching = native.matches(playback)
    return copy(
        canPrevious = matching && native.canPrevious,
        canNext = matching && native.canNext,
        canSeek = matching && native.canSeek && playback?.isSeekable == true,
        shuffle = native.shuffle,
        repeat = native.repeat,
        liked = matching && native.liked,
        volume = native.volume?.takeIf { it.isFinite() }?.coerceIn(0f, 1f),
        canvasAvailable = matching && native.canvasAvailable,
        palette = if (matching) native.palette else emptyList(),
        spatialFont = native.spatialFont,
        reducedMotion = reducedMotion,
        handoff = native.handoff?.takeIf { matching && it.fromOccurrenceId == playback?.occurrenceId },
        actionSupport = native.actionSupport,
    )
}

/** Read the latest observed values on every action; rapid taps do not toggle stale captured state. */
fun dispatchNativePlayerAction(
    controller: MusicPlayerController,
    controls: PlayerNativeControls,
    action: PlayerAction,
) {
    val playback = controller.snapshot.value
    if (action == PlayerAction.COLLAPSE || action == PlayerAction.SLEEP) {
        controls.onAction(action, playback?.occurrenceId)
        return
    }
    val native = controls.state.value
    if (!native.matches(playback)) return
    when (action) {
        PlayerAction.PREVIOUS -> if (native.canPrevious) controls.previous()
        PlayerAction.NEXT -> if (native.canNext) controls.next()
        PlayerAction.SHUFFLE -> controls.setShuffle(!native.shuffle)
        PlayerAction.REPEAT -> controls.setRepeat(when (native.repeat) {
            PlayerRepeat.OFF -> PlayerRepeat.ALL
            PlayerRepeat.ALL -> PlayerRepeat.ONE
            PlayerRepeat.ONE -> PlayerRepeat.OFF
        })
        PlayerAction.SEEK_BACKWARD, PlayerAction.SEEK_FORWARD ->
            if (native.canSeek && playback?.isSeekable == true) controls.seekStep(action == PlayerAction.SEEK_FORWARD)
        PlayerAction.LIKE, PlayerAction.SHARE, PlayerAction.MORE, PlayerAction.TITLE, PlayerAction.ARTIST,
        PlayerAction.OUTPUT -> controls.onAction(action, playback?.occurrenceId)
        PlayerAction.COLLAPSE, PlayerAction.SLEEP -> Unit // Handled without a track above.
    }
}
