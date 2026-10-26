package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot

/**
 * Display projection, not a queue owner. [occurrenceIds] is the controller's current PLAY ORDER.
 * The host maps these identities to its existing repository metadata; duplicate media stay distinct.
 */
data class PlayerState(
    val playback: PlaybackSnapshot?,
    val title: String,
    val artist: String,
    val occurrenceIds: List<String>,
    val positionMs: Long = playback?.positionMs ?: 0L,
    val canPrevious: Boolean = false,
    val canNext: Boolean = false,
    val canSeek: Boolean = true,
    val shuffle: Boolean = false,
    val repeat: PlayerRepeat = PlayerRepeat.OFF,
    val liked: Boolean = false,
    val volume: Float? = null,
    val canvasAvailable: Boolean = false,
    val palette: List<Color> = emptyList(),
    val spatialFont: FontFamily? = null,
    val active: Boolean = true,
    val reducedMotion: Boolean = false,
    val handoff: PlayerHandoff? = null,
)

/**
 * An armed transition from the playing occurrence to its successor. The blend starts at [fadeStartPositionMs] of the
 * outgoing song and lasts [overlapMs]; a gapless handoff has no overlap and no blend.
 */
data class PlayerHandoff(
    val fromOccurrenceId: String,
    val toOccurrenceId: String,
    val fadeStartPositionMs: Long,
    val overlapMs: Long,
) {
    fun progressAt(positionMs: Long): Float =
        if (overlapMs <= 0L) 0f else ((positionMs - fadeStartPositionMs).toFloat() / overlapMs).coerceIn(0f, 1f)
}

enum class PlayerRepeat { OFF, ALL, ONE }
enum class PlayerAction { PREVIOUS, NEXT, SHUFFLE, REPEAT, LIKE, SHARE, MORE, SLEEP, TITLE, ARTIST, COLLAPSE, SEEK_BACKWARD, SEEK_FORWARD }

/** Required callbacks deliberately have no no-op defaults. Queue dispatch stays occurrence-based. */
class PlayerActions(
    val playPause: () -> Unit,
    val seekTo: (Long) -> Unit,
    val selectOccurrence: (String) -> Unit,
    val setVolume: (Float) -> Unit,
    val onAction: (PlayerAction) -> Unit,
    val onLyricsVisibility: (Boolean) -> Unit,
)

/** Reuses the accepted engine/controller contract; next/previous/repeat remain the queue owner's job. */
fun controllerPlayerActions(
    controller: MusicPlayerController,
    selectOccurrence: (String) -> Unit,
    setVolume: (Float) -> Unit,
    onAction: (PlayerAction) -> Unit,
    onLyricsVisibility: (Boolean) -> Unit,
): PlayerActions = PlayerActions(
    playPause = {
        val snapshot = controller.snapshot.value
        if (snapshot?.isEnded == true) {
            controller.seekTo(0L)
            controller.play()
        } else if (snapshot?.playWhenReady == true) controller.pause() else controller.play()
    },
    seekTo = controller::seekTo,
    selectOccurrence = selectOccurrence,
    setVolume = setVolume,
    onAction = onAction,
    onLyricsVisibility = onLyricsVisibility,
)

/**
 * All media/lyrics/queue surfaces are supplied by their real owners. No decoder, renderer, queue
 * controller, preference store or navigation stack is created by a player look.
 * [canvas] must suspend decoding when its active argument is false.
 */
class PlayerSlots(
    val artwork: @Composable (occurrenceId: String?, modifier: Modifier) -> Unit,
    val canvas: @Composable (occurrenceId: String?, active: Boolean, modifier: Modifier) -> Unit,
    val customBackground: @Composable (uri: String, modifier: Modifier) -> Unit,
    val lyrics: @Composable (modifier: Modifier) -> Unit,
    val compactLyrics: @Composable (modifier: Modifier) -> Unit,
    val queue: @Composable (modifier: Modifier) -> Unit,
)
