/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.lyrics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsLine
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot

/** The fullscreen renderer on a themed surface, placed the way a host places it. */
@Composable
internal fun FullscreenLyricsHarness(
    lines: List<LyricsLine>,
    playback: StateFlow<PlaybackSnapshot?>,
    romanization: LyricsRomanizationState,
    settings: LyricsSurfaceSettings,
    actions: LyricsSurfaceActions,
    listState: LazyListState,
) {
    MaterialTheme {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            LyricsFullscreenRenderer(
                lines = lines,
                sessionKey = TEST_SESSION,
                playback = playback,
                romanization = romanization,
                settings = settings,
                actions = actions,
                listState = listState,
            )
        }
    }
}

internal fun offRomanization(): LyricsRomanizationState =
    LyricsRomanizationState(LyricsRomanizationPresentation.off(TEST_SESSION))

internal fun noopLyricsActions(): LyricsSurfaceActions =
    LyricsSurfaceActions(onSeek = {}, onShareCard = {})
