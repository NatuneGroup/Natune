/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.glass.StaticBusyFraction
import moe.rukamori.archivetune.shared.ui.glass.motionAllowed
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Owner-reported capture state. The screen never reads the microphone, starts capture or infers a match;
 * every outcome below comes from the recognition owner.
 */
sealed interface RecognitionStatusUi {
    /** No capture is running and no outcome is pending. */
    data object Idle : RecognitionStatusUi

    /** The owner is capturing or matching the current request. */
    data class Running(val phase: RecognitionPhaseUi) : RecognitionStatusUi

    /**
     * The owner matched [track]. The screen calls [MusicRecognitionActions.openDetails] once for it, so the
     * owner must move the status off [Recognized] when that happens; otherwise details would reopen on re-entry.
     */
    data class Recognized(val track: RecognizedTrackUi) : RecognitionStatusUi

    /** The last attempt produced no result. [RecognitionErrorUi.PERMISSION_REQUIRED] offers Allow instead of retry. */
    data class Failed(val error: RecognitionErrorUi) : RecognitionStatusUi
}

enum class RecognitionPhaseUi { LISTENING, PROCESSING }

enum class RecognitionErrorUi { PERMISSION_REQUIRED, NO_MATCH, RECORDING_FAILED, SIGNATURE_FAILED, RECOGNITION_FAILED }

/**
 * An owner-resolved match. [metadata] and [lyricsPreview] arrive already formatted, and blank optional values
 * are dropped by the owner, so the screen renders them verbatim.
 */
data class RecognizedTrackUi(
    /** Stable identity used by the details route: the track id, or the normalized title, artist and ISRC. */
    val stableKey: String,
    val title: String,
    val artist: String,
    val album: String?,
    val artworkUrl: String?,
    val metadata: String,
    val label: String?,
    val lyricsPreview: String?,
    val shazamUrl: String?,
    val isrc: String?,
    val searchQuery: String,
)

data class RecognitionHistoryItemUi(
    val stableKey: String,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val metadata: String,
    /** Formatted for the locale by the owner, for example a medium date with a short time. */
    val recognizedAt: String,
    val shazamUrl: String?,
    val searchQuery: String,
)

data class MusicRecognitionUi(
    val status: RecognitionStatusUi,
    /** Every saved recognition, newest first. */
    val history: List<RecognitionHistoryItemUi>,
    /** [history] filtered by [historyQuery] with the owner's matching policy; the screen does not re-filter. */
    val filteredHistory: List<RecognitionHistoryItemUi>,
    val historyVisible: Boolean,
    val historyQuery: String,
    val settingsVisible: Boolean,
    val backgroundRecognitionEnabled: Boolean,
    /** False in the FOSS distribution: the switch stays visible but disabled, with the explanation. */
    val backgroundRecognitionAvailable: Boolean,
    val reducedMotion: Boolean = false,
    /** True when the saved history could not be read or its latest save failed. Shown in place of a silent empty list. */
    val historyUnavailable: Boolean = false,
    /** True after a failed background-setting save. The switch already shows the restored value; this says why. */
    val backgroundRecognitionSaveFailed: Boolean = false,
)

/**
 * The owner decides what each operation means. Clearing the history query is the owner's job when
 * [historyVisibility] is false, matching the baseline dismissal.
 */
data class MusicRecognitionActions(
    val back: () -> Unit,
    /** The owner handles microphone permission, ignores the request while running, and starts capture. */
    val listen: () -> Unit,
    /** The owner cancels the running capture and reports [RecognitionStatusUi.Idle]. */
    val cancel: () -> Unit,
    val historyVisibility: (Boolean) -> Unit,
    val historyQuery: (String) -> Unit,
    val settingsVisibility: (Boolean) -> Unit,
    /** The owner ignores the request while unavailable and restores the persisted value if saving fails. */
    val backgroundRecognition: (Boolean) -> Unit,
    /** Receives a trimmed, non-blank query. The host opens online search for it. */
    val search: (String) -> Unit,
    /** Receives a link. The host opens it with the platform handler. */
    val openUri: (String) -> Unit,
    /**
     * Receives the stable key of a [RecognitionStatusUi.Recognized] result. The host opens details, and the
     * owner moves the status off [RecognitionStatusUi.Recognized].
     */
    val openDetails: (String) -> Unit,
)

data class MusicRecognitionDetailsActions(
    val back: () -> Unit,
    /** The host opens the main recognition screen with a listen request, as the baseline "Listen again" does. */
    val listenAgain: () -> Unit,
    /** Receives a trimmed, non-blank query. The host opens online search for it. */
    val search: (String) -> Unit,
    val openUri: (String) -> Unit,
)

/** Host-supplied cover art for a URL. The host's image loader decodes it; the screen only sizes and clips it. */
typealias RecognitionArtwork = @Composable (url: String, modifier: Modifier) -> Unit

/**
 * Recognition capture state, history and settings. [autoStartRequest] is a listen-again token: each new token starts
 * exactly one listen, and a token already consumed by this entry (for example after returning from details) never
 * starts capture again.
 */
@Composable
fun MusicRecognitionScreen(
    state: MusicRecognitionUi,
    actions: MusicRecognitionActions,
    artwork: RecognitionArtwork,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = LocalShellContentPadding.current,
    autoStartRequest: Long? = null,
) {
    var consumedAutoStart by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(autoStartRequest) {
        val request = autoStartRequest
        if (request != null && request != consumedAutoStart) {
            consumedAutoStart = request
            actions.listen()
        }
    }
    // A recognized occurrence opens details once for this entry, so returning from details does not reopen it. The
    // owner must also move the status off Recognized, which is what stops a fresh entry from admitting a stale result.
    var admittedDetails by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.status) {
        val status = state.status
        if (status is RecognitionStatusUi.Recognized && status.track.stableKey != admittedDetails) {
            admittedDetails = status.track.stableKey
            actions.openDetails(status.track.stableKey)
        }
    }

    Column(modifier.fillMaxSize()) {
        RecognitionHeader(title = stringResource(Res.string.m4_recognition_title), back = actions.back) {
            FilledTonalIconButton(
                onClick = { actions.historyVisibility(true) },
                modifier = Modifier.testTag("recognition-history"),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.m4_icon_history),
                    contentDescription = stringResource(Res.string.m4_recognition_history),
                )
            }
            FilledTonalIconButton(
                onClick = { actions.settingsVisibility(true) },
                modifier = Modifier.testTag("recognition-settings"),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.m4_icon_settings),
                    contentDescription = stringResource(Res.string.m4_recognition_settings),
                )
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = contentPadding,
        ) {
            item(key = "recognition-state") {
                RecognitionWidth { wide ->
                    AnimatedContent(
                        targetState = state.status,
                        modifier = Modifier.fillMaxWidth(),
                        transitionSpec = {
                            if (state.reducedMotion) {
                                EnterTransition.None togetherWith ExitTransition.None
                            } else {
                                (fadeIn() + scaleIn(initialScale = StateTransitionInitialScale)) togetherWith (fadeOut() + scaleOut(targetScale = StateTransitionTargetScale))
                            }
                        },
                        contentKey = { it.transitionKey() },
                        label = "MusicRecognitionState",
                    ) { status ->
                        when (status) {
                            RecognitionStatusUi.Idle -> RecognitionTaskState(
                                phase = null,
                                listen = actions.listen,
                                cancel = actions.cancel,
                            )

                            is RecognitionStatusUi.Running -> RecognitionTaskState(
                                phase = status.phase,
                                listen = actions.listen,
                                cancel = actions.cancel,
                            )

                            is RecognitionStatusUi.Failed -> RecognitionErrorState(
                                error = status.error,
                                listen = actions.listen,
                            )

                            is RecognitionStatusUi.Recognized -> RecognitionResultContent(
                                track = status.track,
                                wide = wide,
                                artwork = artwork,
                                listenAgain = actions.listen,
                                search = { status.track.searchQuery.trimmedOrNull()?.let(actions.search) },
                                openUri = actions.openUri,
                            )
                        }
                    }
                }
            }
        }
    }

    if (state.historyVisible) RecognitionHistorySheet(state = state, actions = actions, artwork = artwork)
    if (state.settingsVisible) RecognitionSettingsSheet(state = state, actions = actions)
}

/**
 * One saved recognition. [track] is null when the owner no longer holds the result for the route key,
 * in which case the screen offers only a way back.
 */
@Composable
fun MusicRecognitionDetailsScreen(
    track: RecognizedTrackUi?,
    actions: MusicRecognitionDetailsActions,
    artwork: RecognitionArtwork,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = LocalShellContentPadding.current,
) {
    Column(modifier.fillMaxSize()) {
        RecognitionHeader(title = stringResource(Res.string.m4_recognition_title), back = actions.back)
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = contentPadding,
        ) {
            item(key = "recognition-details") {
                RecognitionWidth { wide ->
                    if (track == null) {
                        Text(
                            text = stringResource(Res.string.m4_recognition_details_unavailable),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        RecognitionResultContent(
                            track = track,
                            wide = wide,
                            artwork = artwork,
                            listenAgain = actions.listenAgain,
                            search = { track.searchQuery.trimmedOrNull()?.let(actions.search) },
                            openUri = actions.openUri,
                        )
                    }
                }
            }
        }
    }
}

/** Keeps the top bar identical across the two recognition routes; [trailing] adds the main screen's actions. */
@Composable
private fun RecognitionHeader(
    title: String,
    back: () -> Unit,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = back, modifier = Modifier.testTag("recognition-back")) {
            Icon(
                painter = painterResource(Res.drawable.m4_icon_arrow_back),
                contentDescription = stringResource(Res.string.m4_back),
            )
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing()
    }
}

/**
 * Centers the content and caps its width: a single column on phones, a wider two-pane result on larger
 * windows. The breakpoint matches the baseline medium window class.
 */
@Composable
private fun RecognitionWidth(content: @Composable (wide: Boolean) -> Unit) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        val wide = maxWidth >= WideLayoutBreakpoint
        Column(Modifier.widthIn(max = if (wide) 1040.dp else 680.dp).fillMaxWidth()) {
            content(wide)
        }
    }
}

@Composable
private fun RecognitionTaskState(
    phase: RecognitionPhaseUi?,
    listen: () -> Unit,
    cancel: () -> Unit,
) {
    val busy = phase != null
    val title = when (phase) {
        RecognitionPhaseUi.LISTENING -> stringResource(Res.string.m4_recognition_listening)
        RecognitionPhaseUi.PROCESSING -> stringResource(Res.string.m4_recognition_processing)
        null -> null
    }
    val icon = when (phase) {
        RecognitionPhaseUi.LISTENING -> Res.drawable.m4_icon_listening
        RecognitionPhaseUi.PROCESSING -> Res.drawable.m4_icon_cached
        null -> Res.drawable.m4_icon_mic
    }
    val heroShape = MaterialShapes.Cookie9Sided.toShape()

    Column(
        Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Box(Modifier.size(192.dp), contentAlignment = Alignment.Center) {
            if (phase == RecognitionPhaseUi.LISTENING) {
                if (motionAllowed()) {
                    CircularWavyProgressIndicator(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                } else {
                    // Motion is off: the wave holds a static arc, so no frame clock runs for it.
                    CircularProgressIndicator(
                        progress = { StaticBusyFraction },
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }
            }
            Surface(
                modifier = Modifier.size(152.dp),
                shape = heroShape,
                color = if (busy) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (busy) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (phase == RecognitionPhaseUi.PROCESSING) {
                        if (motionAllowed()) {
                            LoadingIndicator(
                                modifier = Modifier.size(72.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        } else {
                            // Motion is off: the morphing loader holds a static arc, so no frame clock runs for it.
                            CircularProgressIndicator(
                                progress = { StaticBusyFraction },
                                modifier = Modifier.size(72.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    } else {
                        Icon(
                            painter = painterResource(icon),
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                        )
                    }
                }
            }
        }

        title?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }

        if (busy) {
            OutlinedButton(
                onClick = cancel,
                modifier = Modifier.heightIn(min = 48.dp).testTag("recognition-cancel"),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(Res.string.m4_cancel))
            }
        } else {
            Button(
                onClick = listen,
                modifier = Modifier.widthIn(min = 180.dp).heightIn(min = 56.dp).testTag("recognition-listen"),
                shapes = ButtonDefaults.shapes(),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.m4_icon_mic),
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(Res.string.m4_recognition_tap_to_listen))
            }
        }
    }
}

@Composable
private fun RecognitionErrorState(
    error: RecognitionErrorUi,
    listen: () -> Unit,
) {
    val permission = error == RecognitionErrorUi.PERMISSION_REQUIRED
    val title = when (error) {
        RecognitionErrorUi.PERMISSION_REQUIRED -> stringResource(Res.string.m4_recognition_permission_title)
        RecognitionErrorUi.NO_MATCH -> stringResource(Res.string.m4_recognition_no_match)
        RecognitionErrorUi.RECORDING_FAILED,
        RecognitionErrorUi.SIGNATURE_FAILED,
        RecognitionErrorUi.RECOGNITION_FAILED,
        -> stringResource(Res.string.m4_recognition_error)
    }
    val message: String? = when (error) {
        RecognitionErrorUi.PERMISSION_REQUIRED -> stringResource(Res.string.m4_recognition_permission_desc)
        RecognitionErrorUi.SIGNATURE_FAILED -> stringResource(Res.string.m4_recognition_signature_failed)
        RecognitionErrorUi.RECORDING_FAILED,
        RecognitionErrorUi.RECOGNITION_FAILED,
        -> stringResource(Res.string.m4_recognition_recognition_failed)
        RecognitionErrorUi.NO_MATCH -> null
    }

    Column(
        Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Surface(
            modifier = Modifier.size(112.dp),
            shape = CircleShape,
            color = if (permission) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(if (permission) Res.drawable.m4_icon_mic else Res.drawable.m4_icon_search_off),
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = if (permission) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    },
                )
            }
        }

        Surface(
            modifier = Modifier.widthIn(max = 560.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                message?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = listen,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("recognition-retry"),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(
                        stringResource(
                            if (permission) Res.string.m4_recognition_permission_action else Res.string.m4_recognition_listen_again,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun RecognitionResultContent(
    track: RecognizedTrackUi,
    wide: Boolean,
    artwork: RecognitionArtwork,
    listenAgain: () -> Unit,
    search: () -> Unit,
    openUri: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            if (wide) {
                Row(
                    modifier = Modifier.padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    ResultIdentity(track = track, artworkSize = 200.dp, artwork = artwork, modifier = Modifier.weight(0.9f))
                    ResultSupportingContent(track = track, openUri = openUri, modifier = Modifier.weight(1.1f))
                }
            } else {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    ResultIdentity(track = track, artworkSize = 184.dp, artwork = artwork, modifier = Modifier.fillMaxWidth())
                    ResultSupportingContent(track = track, openUri = openUri, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        ResultActions(listenAgain = listenAgain, search = search)
    }
}

@Composable
private fun ResultIdentity(
    track: RecognizedTrackUi,
    artworkSize: Dp,
    artwork: RecognitionArtwork,
    modifier: Modifier = Modifier,
) {
    val artworkShape = MaterialShapes.Cookie4Sided.toShape()
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RecognitionCover(artworkUrl = track.artworkUrl, dimension = artworkSize, shape = artworkShape, artwork = artwork)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            track.album?.takeIf(String::isNotBlank)?.let { MetadataPill(iconRes = Res.drawable.m4_icon_album, text = it) }
            track.metadata.takeIf(String::isNotBlank)?.let { MetadataPill(iconRes = Res.drawable.m4_icon_info, text = it) }
            track.isrc?.let { MetadataPill(iconRes = Res.drawable.m4_icon_link, text = it) }
        }
    }
}

@Composable
private fun ResultSupportingContent(
    track: RecognizedTrackUi,
    openUri: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shazamUrl = track.shazamUrl
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        track.lyricsPreview?.let { lyrics ->
            ResultInformationBlock(
                iconRes = Res.drawable.m4_icon_lyrics,
                title = stringResource(Res.string.m4_recognition_lyrics_preview),
                body = lyrics,
            )
        }
        track.label?.let { label ->
            ResultInformationBlock(iconRes = Res.drawable.m4_icon_info, title = label, body = null)
        }
        if (shazamUrl != null) {
            OutlinedButton(
                onClick = { openUri(shazamUrl) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("recognition-open-shazam"),
                shapes = ButtonDefaults.shapes(),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.m4_icon_link),
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(Res.string.m4_recognition_open_shazam))
            }
        }
    }
}

@Composable
private fun ResultInformationBlock(
    iconRes: DrawableResource,
    title: String,
    body: String?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            body?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ResultActions(
    listenAgain: () -> Unit,
    search: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        FilledTonalButton(
            onClick = listenAgain,
            modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("recognition-listen-again"),
            shapes = ButtonDefaults.shapes(
                shape = ButtonGroupDefaults.connectedLeadingButtonShape,
                pressedShape = ButtonGroupDefaults.connectedLeadingButtonPressShape,
            ),
        ) {
            Icon(
                painter = painterResource(Res.drawable.m4_icon_replay),
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
            Text(
                text = stringResource(Res.string.m4_recognition_listen_again),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Button(
            onClick = search,
            modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("recognition-search"),
            shapes = ButtonDefaults.shapes(
                shape = ButtonGroupDefaults.connectedTrailingButtonShape,
                pressedShape = ButtonGroupDefaults.connectedTrailingButtonPressShape,
            ),
        ) {
            Icon(
                painter = painterResource(Res.drawable.m4_icon_search),
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
            Text(
                text = stringResource(Res.string.m4_search),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MetadataPill(
    iconRes: DrawableResource,
    text: String,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Shows the host's artwork for a URL, or the baseline music-note placeholder when the owner has none. */
@Composable
private fun RecognitionCover(
    artworkUrl: String?,
    dimension: Dp,
    shape: Shape,
    artwork: RecognitionArtwork,
) {
    Surface(
        modifier = Modifier.size(dimension),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        if (artworkUrl != null) {
            artwork(artworkUrl, Modifier.fillMaxSize())
        } else {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(Res.drawable.m4_icon_music_note),
                    contentDescription = null,
                    modifier = Modifier.size(dimension / 3),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RecognitionSettingsSheet(
    state: MusicRecognitionUi,
    actions: MusicRecognitionActions,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val description = stringResource(
        if (state.backgroundRecognitionAvailable) {
            Res.string.m4_recognition_background_description
        } else {
            Res.string.m4_recognition_background_foss_description
        },
    )
    ModalBottomSheet(
        onDismissRequest = { actions.settingsVisibility(false) },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 680.dp)
                .fillMaxWidth()
                .align(Alignment.CenterHorizontally)
                .navigationBarsPadding()
                .padding(horizontal = 8.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.m4_recognition_settings),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = { actions.settingsVisibility(false) }) {
                    Icon(
                        painter = painterResource(Res.drawable.m4_icon_close),
                        contentDescription = stringResource(Res.string.m4_close),
                    )
                }
            }
            // The whole row toggles, but only when the owner can actually change the setting (FOSS cannot).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .toggleable(
                        value = state.backgroundRecognitionEnabled,
                        enabled = state.backgroundRecognitionAvailable,
                        role = Role.Switch,
                        onValueChange = actions.backgroundRecognition,
                    )
                    .testTag("recognition-background-toggle"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.m4_icon_mic),
                    contentDescription = null,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.m4_recognition_background_title),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.backgroundRecognitionSaveFailed) {
                        Text(
                            text = stringResource(Res.string.m4_recognition_background_save_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Switch(
                    checked = state.backgroundRecognitionEnabled,
                    onCheckedChange = null,
                    enabled = state.backgroundRecognitionAvailable,
                )
            }
        }
    }
}

@Composable
private fun RecognitionHistorySheet(
    state: MusicRecognitionUi,
    actions: MusicRecognitionActions,
    artwork: RecognitionArtwork,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { actions.historyVisibility(false) },
        sheetState = sheetState,
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 840.dp)
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.m4_recognition_history),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { actions.historyVisibility(false) }) {
                    Icon(
                        painter = painterResource(Res.drawable.m4_icon_close),
                        contentDescription = stringResource(Res.string.m4_close),
                    )
                }
            }

            // A plain outlined field keeps the filter in the owner's query state, as the other shared list filters do.
            val clearQuery: @Composable () -> Unit = {
                IconButton(onClick = { actions.historyQuery("") }) {
                    Icon(
                        painter = painterResource(Res.drawable.m4_icon_close),
                        contentDescription = stringResource(Res.string.m4_recognition_clear),
                    )
                }
            }
            OutlinedTextField(
                value = state.historyQuery,
                onValueChange = actions.historyQuery,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).testTag("recognition-history-query"),
                singleLine = true,
                placeholder = { Text(stringResource(Res.string.m4_recognition_history_search)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(Res.drawable.m4_icon_search),
                        contentDescription = null,
                    )
                },
                trailingIcon = if (state.historyQuery.isNotEmpty()) clearQuery else null,
            )

            if (state.historyUnavailable && state.history.isNotEmpty()) {
                Text(
                    text = stringResource(Res.string.m4_recognition_history_unavailable_body),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            when {
                state.history.isEmpty() && state.historyUnavailable -> RecognitionHistoryEmptyState(
                    iconRes = Res.drawable.m4_icon_info,
                    title = stringResource(Res.string.m4_recognition_history_unavailable_title),
                    body = stringResource(Res.string.m4_recognition_history_unavailable_body),
                )

                state.history.isEmpty() -> RecognitionHistoryEmptyState(
                    iconRes = Res.drawable.m4_icon_history,
                    title = stringResource(Res.string.m4_recognition_history_empty_title),
                    body = stringResource(Res.string.m4_recognition_history_empty_body),
                )

                state.filteredHistory.isEmpty() -> RecognitionHistoryEmptyState(
                    iconRes = Res.drawable.m4_icon_search_off,
                    title = stringResource(Res.string.m4_recognition_history_no_results_title),
                    body = stringResource(Res.string.m4_recognition_history_no_results_body),
                )

                else -> RecognitionHistoryList(
                    items = state.filteredHistory,
                    artwork = artwork,
                    search = { query -> query.trimmedOrNull()?.let { searchFromSheet(actions, it) } },
                    openUri = actions.openUri,
                )
            }
        }
    }
}

@Composable
private fun RecognitionHistoryList(
    items: List<RecognitionHistoryItemUi>,
    artwork: RecognitionArtwork,
    search: (String) -> Unit,
    openUri: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        itemsIndexed(
            items = items,
            key = { _, item -> item.stableKey },
            contentType = { _, _ -> RecognitionHistoryItemContentType },
        ) { index, item ->
            RecognitionHistoryListItem(
                item = item,
                index = index,
                count = items.size,
                artwork = artwork,
                search = search,
                openUri = openUri,
            )
        }
    }
}

@Composable
private fun RecognitionHistoryListItem(
    item: RecognitionHistoryItemUi,
    index: Int,
    count: Int,
    artwork: RecognitionArtwork,
    search: (String) -> Unit,
    openUri: (String) -> Unit,
) {
    val shazamUrl = item.shazamUrl
    SegmentedListItem(
        onClick = { search(item.searchQuery) },
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        modifier = Modifier.fillMaxWidth(),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        leadingContent = {
            RecognitionCover(
                artworkUrl = item.artworkUrl,
                dimension = 64.dp,
                shape = MaterialTheme.shapes.large,
                artwork = artwork,
            )
        },
        overlineContent = {
            Text(
                text = stringResource(Res.string.m4_recognition_history_recognized_at, item.recognizedAt),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
                Text(
                    text = item.artist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.metadata.isNotEmpty()) {
                    Text(
                        text = item.metadata,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (shazamUrl != null) {
                    IconButton(onClick = { openUri(shazamUrl) }) {
                        Icon(
                            painter = painterResource(Res.drawable.m4_icon_link),
                            contentDescription = stringResource(Res.string.m4_recognition_open_shazam),
                        )
                    }
                }
                IconButton(onClick = { search(item.searchQuery) }) {
                    Icon(
                        painter = painterResource(Res.drawable.m4_icon_search),
                        contentDescription = stringResource(Res.string.m4_search),
                    )
                }
            }
        },
        content = {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@Composable
private fun RecognitionHistoryEmptyState(
    iconRes: DrawableResource,
    title: String,
    body: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.size(64.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Opening a search from the history sheet dismisses it first, as the baseline does. The owner clears the
 * query when visibility turns false, so the next open starts unfiltered.
 */
private fun searchFromSheet(actions: MusicRecognitionActions, query: String) {
    actions.historyVisibility(false)
    actions.search(query)
}

private fun String.trimmedOrNull(): String? = trim().takeIf(String::isNotEmpty)

private fun RecognitionStatusUi.transitionKey(): String = when (this) {
    RecognitionStatusUi.Idle -> "idle"
    is RecognitionStatusUi.Running -> "running-${phase.name}"
    is RecognitionStatusUi.Recognized -> "recognized"
    is RecognitionStatusUi.Failed -> "failed"
}

private const val RecognitionHistoryItemContentType = "recognition_history_item"
private const val StateTransitionInitialScale = 0.96f
private const val StateTransitionTargetScale = 0.98f
private val WideLayoutBreakpoint = 600.dp
