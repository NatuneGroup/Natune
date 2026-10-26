/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.ui.screens.recognition

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import moe.rukamori.archivetune.shared.data.recognition.MusicRecognitionOwner
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionBackgroundSettingPort
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionCapturePort
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionClock
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionDiagnostics
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionHistoryPort
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionMatchPort
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionPermissionPort
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import moe.rukamori.archivetune.shared.ui.screens.MusicRecognitionActions
import moe.rukamori.archivetune.shared.ui.screens.MusicRecognitionDetailsActions
import moe.rukamori.archivetune.shared.ui.screens.MusicRecognitionDetailsScreen
import moe.rukamori.archivetune.shared.ui.screens.MusicRecognitionScreen
import moe.rukamori.archivetune.shared.ui.screens.RecognitionArtwork

/**
 * Creates the route's owner from injected ports and closes it when the route leaves composition. The ports are borrowed
 * and are never closed here. Pass stable instances, remembered by the host, because a new port instance recreates the
 * owner and loses its state.
 */
@Composable
fun rememberMusicRecognitionOwner(
    capture: RecognitionCapturePort,
    matcher: RecognitionMatchPort,
    permission: RecognitionPermissionPort,
    history: RecognitionHistoryPort,
    backgroundSettings: RecognitionBackgroundSettingPort,
    clock: RecognitionClock,
    diagnostics: RecognitionDiagnostics,
    backgroundRecognitionAvailable: Boolean,
): MusicRecognitionOwner {
    val scope = rememberCoroutineScope()
    val owner =
        remember(
            scope,
            capture,
            matcher,
            permission,
            history,
            backgroundSettings,
            clock,
            diagnostics,
            backgroundRecognitionAvailable,
        ) {
            MusicRecognitionOwner(
                scope = scope,
                capture = capture,
                matcher = matcher,
                permission = permission,
                historyStore = history,
                backgroundSettings = backgroundSettings,
                clock = clock,
                diagnostics = diagnostics,
                backgroundRecognitionAvailable = backgroundRecognitionAvailable,
            )
        }
    DisposableEffect(owner) {
        onDispose { owner.close() }
    }
    return owner
}

/**
 * Owner-backed actions for the recognition screen. [openDetails] settles the owner's Recognized status before the host
 * navigates. A host that replaces this action must call [MusicRecognitionOwner.onDetailsOpened] itself, or the same
 * result can be admitted again when the route is re-entered.
 */
fun MusicRecognitionOwner.toMusicRecognitionActions(
    back: () -> Unit,
    search: (String) -> Unit,
    openUri: (String) -> Unit,
    openDetails: (String) -> Unit,
): MusicRecognitionActions =
    MusicRecognitionActions(
        back = back,
        listen = { listen() },
        cancel = { cancel() },
        historyVisibility = { visible -> setHistoryVisible(visible) },
        historyQuery = { query -> setHistoryQuery(query) },
        settingsVisibility = { visible -> setSettingsVisible(visible) },
        backgroundRecognition = { enabled -> setBackgroundRecognitionEnabled(enabled) },
        search = search,
        openUri = openUri,
        openDetails = { stableKey ->
            onDetailsOpened(stableKey)
            openDetails(stableKey)
        },
    )

/**
 * The recognition route, drawn from the real owner. [autoStartRequest] is consumed by [MusicRecognitionScreen] itself,
 * once per token, so this binding must not consume it again. Navigation stays in the host callbacks.
 */
@Composable
fun BoundMusicRecognitionScreen(
    owner: MusicRecognitionOwner,
    formatter: RecognitionTimeFormatter,
    reducedMotion: Boolean,
    artwork: RecognitionArtwork,
    back: () -> Unit,
    search: (String) -> Unit,
    openUri: (String) -> Unit,
    openDetails: (String) -> Unit,
    modifier: Modifier = Modifier,
    autoStartRequest: Long? = null,
    contentPadding: PaddingValues = LocalShellContentPadding.current,
) {
    val state by owner.state.collectAsState()
    val ui = remember(state, formatter, reducedMotion) { state.toMusicRecognitionUi(formatter, reducedMotion) }
    val actions =
        remember(owner, back, search, openUri, openDetails) {
            owner.toMusicRecognitionActions(back, search, openUri, openDetails)
        }
    MusicRecognitionScreen(
        state = ui,
        actions = actions,
        artwork = artwork,
        modifier = modifier,
        contentPadding = contentPadding,
        autoStartRequest = autoStartRequest,
    )
}

/**
 * One saved recognition, resolved by stable identity. A missing identity stays missing: the screen offers only a way
 * back, never a neighboring or positional result.
 */
@Composable
fun BoundMusicRecognitionDetailsScreen(
    owner: MusicRecognitionOwner,
    stableKey: String,
    artwork: RecognitionArtwork,
    back: () -> Unit,
    listenAgain: () -> Unit,
    search: (String) -> Unit,
    openUri: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = LocalShellContentPadding.current,
) {
    val state by owner.state.collectAsState()
    val track = remember(state, stableKey) { state.detailsFor(stableKey)?.toRecognizedTrackUi() }
    val actions =
        remember(back, listenAgain, search, openUri) {
            MusicRecognitionDetailsActions(back = back, listenAgain = listenAgain, search = search, openUri = openUri)
        }
    MusicRecognitionDetailsScreen(
        track = track,
        actions = actions,
        artwork = artwork,
        modifier = modifier,
        contentPadding = contentPadding,
    )
}
