package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.runtime.*
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import kotlin.math.roundToLong
import androidx.compose.runtime.setValue

enum class PlayerPanel { ARTWORK, LYRICS, QUEUE }

/** Remains mounted above style dispatch. A draft is invalidated by track/seek generation changes. */
@Stable
class PlayerInteractionState {
    var panel by mutableStateOf(PlayerPanel.ARTWORK)
    var fullscreenLyrics by mutableStateOf(false)
    var previewMs by mutableStateOf<Long?>(null)
        private set
    private var draftIdentity: Triple<String, Long, Long>? = null
    private var invalidated = false

    fun preview(fraction: Float, state: PlayerState) {
        reconcile(state.playback)
        if (invalidated) return
        val playback = state.playback ?: return
        val duration = playback.durationMs?.takeIf { it > 0L } ?: return
        if (!state.canSeek || !playback.isSeekable || !fraction.isFinite()) return
        if (draftIdentity == null) draftIdentity = playback.identity()
        previewMs = (duration.toDouble() * fraction.coerceIn(0f, 1f)).roundToLong().coerceIn(0L, duration)
    }

    fun commit(state: PlayerState, seek: (Long) -> Unit) {
        val draft = previewMs
        val snapshot = state.playback
        val valid = !invalidated && snapshot != null && draftIdentity == snapshot.identity() && state.canSeek && snapshot.isSeekable
        cancelPreview()
        if (draft != null && valid) seek(draft.coerceIn(0L, snapshot.durationMs ?: Long.MAX_VALUE))
    }

    fun reconcile(snapshot: PlaybackSnapshot?) {
        if (draftIdentity != null && draftIdentity != snapshot?.identity()) {
            previewMs = null
            // The pointer still belongs to its original identity until release/cancel, not this track.
            invalidated = true
        }
    }

    fun cancelPreview() { previewMs = null; draftIdentity = null; invalidated = false }

    fun backOrCollapse(collapse: () -> Unit) {
        if (!back()) collapse()
    }

    /** Style page/fullscreen, then shared panel, then the caller collapses the whole player. */
    fun back(): Boolean = when {
        fullscreenLyrics -> { fullscreenLyrics = false; true }
        panel != PlayerPanel.ARTWORK -> { panel = PlayerPanel.ARTWORK; true }
        else -> false
    }
}

private fun PlaybackSnapshot.identity() = Triple(occurrenceId, playbackGeneration, discontinuityGeneration)

/** Pure loop-breaker used by both vertical and horizontal artwork pagers. */
fun settledArtworkOccurrence(
    ids: List<String>,
    currentOccurrenceId: String?,
    settledPage: Int,
    programmatic: Boolean,
    userDragged: Boolean,
): String? = ids.getOrNull(settledPage)?.takeIf {
    !programmatic && userDragged && it != currentOccurrenceId
}
