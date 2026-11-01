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
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsCredits
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsLine
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.ui.IosNativeLyrics
import moe.rukamori.archivetune.shared.ui.IosNativeLyricsViewFactory
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsRenderRows
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsRomanizationState
import moe.rukamori.archivetune.shared.ui.lyrics.LyricsSurfaceSettings
import moe.rukamori.archivetune.shared.ui.lyrics.NativeLyricsCredits
import moe.rukamori.archivetune.shared.ui.lyrics.NativeLyricsDocument
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.lyrics_auto_created
import moe.rukamori.archivetune.shared.ui.resources.lyrics_written_by
import org.jetbrains.compose.resources.stringResource

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
    /** The document's credits, for the writers footer and the machine-made badges. */
    credits: LyricsCredits? = null,
) {
    key(sessionKey, playback, romanization, factory) {
        val bridge = remember { IosNativeLyrics() }
        val presentation by romanization.presentation.collectAsState()
        val phonetics = if (presentation.sessionKey == sessionKey) presentation.phonetics else emptyList()
        val rows = remember(lines, phonetics, settings.backgroundVocals) {
            LyricsRenderRows.build(lines, phonetics, splitBackground = settings.backgroundVocals)
        }
        val color = MaterialTheme.colorScheme.onSurface.toArgb()
        val glow = MaterialTheme.colorScheme.primary.toArgb()
        // The localized strings resolve here, in Compose; the native document carries the finished text.
        val writers = credits?.songwriters?.takeIf { it.isNotEmpty() }?.joinToString(", ")
        val writtenBy = writers?.let { stringResource(Res.string.lyrics_written_by, it) }
        val autoCreated =
            if (credits != null && (credits.translationAutoCreated || credits.transliterationAutoCreated)) {
                stringResource(Res.string.lyrics_auto_created)
            } else {
                null
            }
        val nativeCredits = credits?.takeIf { !it.isEmpty }?.let {
            NativeLyricsCredits(writtenBy, autoCreated, it.translationAutoCreated, it.transliterationAutoCreated)
        }
        val document = remember(sessionKey, rows, settings, color, glow, nativeCredits) {
            NativeLyricsDocument.from(sessionKey, rows, settings, color, glow, nativeCredits)
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
