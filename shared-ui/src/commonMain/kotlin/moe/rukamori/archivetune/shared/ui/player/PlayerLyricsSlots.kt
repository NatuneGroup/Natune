package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsLine
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.ui.lyrics.*
import androidx.compose.runtime.getValue

class PlayerLyricsSlots internal constructor(
    val full: @Composable (Modifier) -> Unit,
    val compact: @Composable (Modifier) -> Unit,
)

/**
 * Adapts the committed M3 renderers to [PlayerSlots] without another renderer, clock or scroll owner.
 * The host supplies the controller's SAME playback flow, session presentation/settings/actions and
 * actual per-surface visibility/resumed state, including offscreen pager pages.
 */
fun playerLyricsSlots(
    lines: List<LyricsLine>,
    sessionKey: String,
    playback: StateFlow<PlaybackSnapshot?>,
    romanization: LyricsRomanizationState,
    settings: LyricsSurfaceSettings,
    actions: LyricsSurfaceActions,
    compactStyle: LyricsCompactStyle,
    fullVisible: Boolean,
    compactVisible: Boolean,
    resumed: Boolean,
): PlayerLyricsSlots = PlayerLyricsSlots(
    full = { modifier ->
        key(sessionKey,playback,romanization) {
            LyricsFullscreenRenderer(lines,sessionKey,playback,romanization,settings,actions,
                modifier,visible=fullVisible,resumed=resumed)
        }
    },
    compact = { modifier ->
        key(sessionKey,playback,romanization) {
            val presentation by romanization.presentation.collectAsState()
            val phonetics=if(presentation.sessionKey==sessionKey) presentation.phonetics else emptyList()
            val rows=remember(lines,phonetics) { LyricsRenderRows.build(lines,phonetics) }
            LyricsCompactStrip(rows,playback,compactStyle,actions,modifier,
                tapToSeek=settings.tapToSeek,syncOffsetMs=settings.syncOffsetMs,
                reduceMotion=settings.reduceMotion,visible=compactVisible,resumed=resumed)
        }
    },
)
