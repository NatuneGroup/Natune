@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import moe.rukamori.archivetune.shared.domain.playback.PlaybackErrorKind
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailure
import moe.rukamori.archivetune.shared.domain.playback.toPlaybackErrorInfo
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The actions of the playback error dialog. Close, Copy and Retry are always shown. [login] and [poTokenLogin] are the
 * YouTube Music recovery buttons and stay hidden while null, as they do on Android when the failure needs no sign-in.
 * [sendFeedback] receives the copied details and is null while no feedback service is configured.
 */
class PlaybackErrorActions(
    val retry: () -> Unit,
    val close: () -> Unit,
    val copyToClipboard: (String) -> Unit,
    val login: (() -> Unit)? = null,
    val poTokenLogin: (() -> Unit)? = null,
    val sendFeedback: ((String) -> Unit)? = null,
)

/** The Android dialog's kind override for hosts that know a failure needs a YouTube Music sign-in step. */
enum class PlaybackErrorLoginState { NONE, LOGIN_REFRESH_REQUIRED, CONFIRMATION_REQUIRED }

/**
 * The Android playback error dialog: it cannot be dismissed by a tap outside or Back, because the player stopped on the
 * error and only Retry or Close decides what happens next. Wide windows put the header beside the details, narrow ones
 * stack them. [trackTitle] and [sourceName] add the song and the provider to the copied details on hosts that resolve
 * sources themselves; the Android dialog has no such lines because its error already names the stream.
 */
@Composable
fun PlaybackErrorDialog(
    failure: PlaybackFailure,
    actions: PlaybackErrorActions,
    trackTitle: String? = null,
    sourceName: String? = null,
    loginState: PlaybackErrorLoginState = PlaybackErrorLoginState.NONE,
) {
    val info = remember(failure) { failure.toPlaybackErrorInfo() }
    val kind = when (loginState) {
        PlaybackErrorLoginState.LOGIN_REFRESH_REQUIRED -> PlaybackErrorKind.LOGIN_REFRESH_REQUIRED
        PlaybackErrorLoginState.CONFIRMATION_REQUIRED -> PlaybackErrorKind.CONFIRMATION_REQUIRED
        PlaybackErrorLoginState.NONE -> info.kind
    }
    val fallbackUnknown = stringResource(Res.string.m12_error_unknown)
    val noInternet = stringResource(Res.string.m12_error_no_internet)
    val timeout = stringResource(Res.string.m12_error_timeout)
    val noStream = stringResource(Res.string.m12_error_no_stream)
    val malformed = stringResource(Res.string.m12_error_malformed_stream)
    val streamBlocked = stringResource(Res.string.m12_error_stream_blocked)
    val streamBlockedReason = stringResource(Res.string.m12_error_stream_blocked_reason)
    val codeLabel = stringResource(Res.string.m12_playback_error_code)
    val httpLabel = stringResource(Res.string.m12_playback_error_http)
    val messageLabel = stringResource(Res.string.m12_playback_error_message)
    val causeLabel = stringResource(Res.string.m12_playback_error_cause)
    val trackLabel = stringResource(Res.string.m12_playback_error_track)
    val sourceLabel = stringResource(Res.string.m12_playback_error_source)
    val loginRefreshTitle = stringResource(Res.string.m12_playback_login_refresh_required)
    val confirmationTitle = stringResource(Res.string.m12_playback_confirmation_required)
    val loginRefreshReason = stringResource(Res.string.m12_playback_requires_youtube_music_login_refresh)
    val confirmationReason = stringResource(Res.string.m12_playback_requires_youtube_music_confirmation)
    val title = when (kind) {
        PlaybackErrorKind.LOGIN_REFRESH_REQUIRED -> loginRefreshTitle
        PlaybackErrorKind.CONFIRMATION_REQUIRED -> confirmationTitle
        PlaybackErrorKind.NO_INTERNET -> noInternet
        PlaybackErrorKind.TIMEOUT -> timeout
        PlaybackErrorKind.NO_STREAM -> noStream
        PlaybackErrorKind.MALFORMED_STREAM -> malformed
        PlaybackErrorKind.STREAM_BLOCKED -> streamBlocked
        PlaybackErrorKind.DECODER, PlaybackErrorKind.HTTP, PlaybackErrorKind.UNKNOWN -> fallbackUnknown
    }
    val reason = when (kind) {
        PlaybackErrorKind.LOGIN_REFRESH_REQUIRED -> loginRefreshReason
        PlaybackErrorKind.CONFIRMATION_REQUIRED -> confirmationReason
        PlaybackErrorKind.DECODER -> "$fallbackUnknown ($codeLabel ${info.code})"
        PlaybackErrorKind.HTTP -> "$fallbackUnknown ($httpLabel ${info.httpStatus})"
        PlaybackErrorKind.STREAM_BLOCKED -> streamBlockedReason
        PlaybackErrorKind.UNKNOWN -> failure.message.trim().ifEmpty { fallbackUnknown }
        else -> title
    }
    val details = remember(failure, reason, trackTitle, sourceName, codeLabel, httpLabel, messageLabel, causeLabel) {
        buildString {
            appendLine(reason)
            appendLine("$codeLabel: ${info.code}")
            info.httpStatus?.let { appendLine("$httpLabel: $it") }
            trackTitle?.takeIf { it.isNotBlank() }?.let { appendLine("$trackLabel: $it") }
            sourceName?.takeIf { it.isNotBlank() }?.let { appendLine("$sourceLabel: $it") }
            val message = failure.message.trim()
            if (message.isNotEmpty() && message != reason) {
                appendLine()
                appendLine("$messageLabel: $message")
            }
            failure.causes.forEach { cause ->
                appendLine()
                appendLine("$causeLabel: $cause")
            }
        }.trim()
    }
    // Android confirms a copy with a toast; a shared dialog has no toast host, so the line shows inside it instead.
    var copies by remember { mutableIntStateOf(0) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copies) {
        if (copies == 0) return@LaunchedEffect
        copied = true
        delay(COPIED_NOTICE_MS)
        copied = false
    }
    val content = PlaybackErrorContent(
        title = title,
        reason = reason,
        details = details,
        copied = copied,
        actions = actions,
        onCopy = {
            actions.copyToClipboard(details)
            copies += 1
        },
    )

    BasicAlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = DIALOG_MAX_WIDTH)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight),
                shape = AlertDialogDefaults.shape,
                color = AlertDialogDefaults.containerColor,
                tonalElevation = AlertDialogDefaults.TonalElevation,
            ) {
                BoxWithConstraints {
                    if (maxWidth >= EXPANDED_MIN_WIDTH && maxHeight >= EXPANDED_MIN_HEIGHT) {
                        PlaybackErrorExpanded(content)
                    } else {
                        PlaybackErrorCompact(content)
                    }
                }
            }
        }
    }
}

private class PlaybackErrorContent(
    val title: String,
    val reason: String,
    val details: String,
    val copied: Boolean,
    val actions: PlaybackErrorActions,
    val onCopy: () -> Unit,
)

@Composable
private fun PlaybackErrorCompact(content: PlaybackErrorContent) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        PlaybackErrorHeader(content.title, content.reason, Modifier.fillMaxWidth())
        PlaybackErrorDetails(content.details, Modifier.fillMaxWidth().weight(1f, fill = false))
        PlaybackErrorButtons(content, Modifier.fillMaxWidth())
    }
}

@Composable
private fun PlaybackErrorExpanded(content: PlaybackErrorContent) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        PlaybackErrorHeader(content.title, content.reason, Modifier.weight(0.42f))
        Column(
            modifier = Modifier.weight(0.58f),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            PlaybackErrorDetails(content.details, Modifier.fillMaxWidth().weight(1f, fill = false))
            PlaybackErrorButtons(content, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PlaybackErrorHeader(title: String, reason: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            modifier = Modifier.size(56.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.errorContainer,
        ) {
            Icon(
                painter = painterResource(Res.drawable.m12_icon_player_error),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(14.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PlaybackErrorDetails(details: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(Res.string.m12_details),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun PlaybackErrorButtons(content: PlaybackErrorContent, modifier: Modifier = Modifier) {
    val actions = content.actions
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = actions.close) {
                PlaybackErrorButtonContent(Res.drawable.m12_icon_player_close, stringResource(Res.string.m12_close))
            }
            actions.login?.let { login ->
                OutlinedButton(onClick = login) {
                    PlaybackErrorButtonContent(Res.drawable.m12_icon_player_login, stringResource(Res.string.m12_login))
                }
            }
            actions.poTokenLogin?.let { poTokenLogin ->
                OutlinedButton(onClick = poTokenLogin) {
                    PlaybackErrorButtonContent(
                        Res.drawable.m12_icon_player_token,
                        stringResource(Res.string.m12_playback_login_po_token),
                    )
                }
            }
            actions.sendFeedback?.let { send ->
                OutlinedButton(onClick = { send(content.details) }) {
                    PlaybackErrorButtonContent(Res.drawable.m12_icon_send, stringResource(Res.string.m12_feedback_send))
                }
            }
            FilledTonalButton(onClick = content.onCopy) {
                PlaybackErrorButtonContent(Res.drawable.m12_icon_player_select_all, stringResource(Res.string.m12_copy))
            }
            Button(onClick = actions.retry) {
                PlaybackErrorButtonContent(Res.drawable.m12_icon_player_replay, stringResource(Res.string.m12_retry))
            }
        }
        if (content.copied) {
            Text(
                text = stringResource(Res.string.m12_copied),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
private fun PlaybackErrorButtonContent(icon: DrawableResource, label: String) {
    Icon(
        painter = painterResource(icon),
        contentDescription = null,
        modifier = Modifier.size(ButtonDefaults.IconSize),
    )
    Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
    Text(text = label)
}

private val DIALOG_MAX_WIDTH: Dp = 760.dp
private val EXPANDED_MIN_WIDTH: Dp = 600.dp
private val EXPANDED_MIN_HEIGHT: Dp = 360.dp
private const val COPIED_NOTICE_MS = 2_000L
