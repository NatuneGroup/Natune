package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitViewController
import kotlinx.coroutines.flow.StateFlow
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsLine
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.ui.IosNativeLyrics
import moe.rukamori.archivetune.shared.ui.IosNativeLyricsViewFactory
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsRenderRows
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsRomanizationState
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsSurfaceSettings
import moe.rukamori.archivetune.shared.ui.lyrics.NativeLyricsDocument

/**
 * The Enhanced lyric look drawn by the native SwiftUI view, hosted inside the player. The rows, readings, clock and
 * seeking are the shared ones; only the drawing is native. The view takes its own touches, so its scroll never
 * waits on the player's gestures.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun IosNativeLyricsSurface(
    factory: IosNativeLyricsViewFactory,
    lines: List<LyricsLine>,
    sessionKey: String,
    playback: StateFlow<PlaybackSnapshot?>,
    romanization: LyricsRomanizationState,
    settings: LyricsSurfaceSettings,
    onSeek: (Long) -> Unit,
    visible: Boolean,
    modifier: Modifier,
) {
    key(sessionKey, playback, romanization, factory) {
        val bridge = remember { IosNativeLyrics() }
        val presentation by romanization.presentation.collectAsState()
        val phonetics = if (presentation.sessionKey == sessionKey) presentation.phonetics else emptyList()
        val rows = remember(lines, phonetics) { LyricsRenderRows.build(lines, phonetics, splitBackground = true) }
        val color = MaterialTheme.colorScheme.onSurface.toArgb()
        val document = remember(sessionKey, rows, settings, color) {
            NativeLyricsDocument.from(sessionKey, rows, settings, color)
        }
        val latestSeek by rememberUpdatedState(onSeek)
        SideEffect { bridge.present(document, rows, settings.syncOffsetMs, visible) { latestSeek(it) } }
        LaunchedEffect(bridge, playback) {
            playback.collect { snapshot -> snapshot?.let(bridge::accept) }
        }
        UIKitViewController(
            factory = { factory(bridge) },
            modifier = modifier.fillMaxSize(),
            onRelease = { bridge.close() },
            properties = UIKitInteropProperties(
                interactionMode = UIKitInteropInteractionMode.NonCooperative,
                isNativeAccessibilityEnabled = true,
            ),
        )
    }
}
