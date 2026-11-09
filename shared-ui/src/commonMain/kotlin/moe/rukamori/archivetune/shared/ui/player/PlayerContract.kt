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
    /** Playing-from subtitle (queue title); falls back to [albumTitle] for the header line. */
    val queueTitle: String? = null,
    val albumTitle: String? = null,
    val explicit: Boolean = false,
    /** Stream-quality label for codec chips (e.g. "Lossless"); null hides the chip. */
    val formatLabel: String? = null,
    val formatLossless: Boolean = false,
    /** Remaining sleep-timer milliseconds while armed; null means no countdown chip. */
    val sleepTimerMs: Long? = null,
    /** What this host can actually perform; a surface renders no control for an action outside it. */
    val actionSupport: PlayerActionSupport = PlayerActionSupport.ALL,
    /** Queue occurrence shown while its matching playback snapshot is unavailable. */
    val standInOccurrenceId: String? = null,
) {
    val displayedOccurrenceId: String? get() = playback?.occurrenceId ?: standInOccurrenceId
}

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
enum class PlayerAction { PREVIOUS, NEXT, SHUFFLE, REPEAT, LIKE, SHARE, MORE, SLEEP, TITLE, ARTIST, COLLAPSE, SEEK_BACKWARD, SEEK_FORWARD, OUTPUT }

/**
 * The player actions a host has an owner for. A host that leaves one out does not render its control at all, so a
 * surface never offers a tap whose only outcome would be a "not connected" diagnostic. The default is every action,
 * which is what a host that implements the whole contract answers.
 */
class PlayerActionSupport(actions: Collection<PlayerAction>) {
    private val supported: Set<PlayerAction> = actions.toSet()

    operator fun contains(action: PlayerAction): Boolean = action in supported

    companion object {
        val ALL = PlayerActionSupport(PlayerAction.entries)
    }
}

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
    displayedPlayback: () -> PlaybackSnapshot?,
): PlayerActions = PlayerActions(
    playPause = { controller.togglePlayPause(displayedPlayback()) },
    seekTo = controller::seekTo,
    selectOccurrence = selectOccurrence,
    setVolume = setVolume,
    onAction = onAction,
    onLyricsVisibility = onLyricsVisibility,
)

/** Toggle the displayed intent: a stand-in sends Play, and an ended track replays. */
internal fun MusicPlayerController.togglePlayPause(displayedPlayback: PlaybackSnapshot?) {
    if (displayedPlayback?.isEnded == true) {
        seekTo(0L)
        play()
    } else if (displayedPlayback?.playWhenReady == true) pause() else play()
}

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
    /** A native route control when the host supplies one; other hosts retain their existing OUTPUT action. */
    val outputButton: (@Composable (modifier: Modifier, tint: Color, enabled: Boolean) -> Unit)? = null,
)
