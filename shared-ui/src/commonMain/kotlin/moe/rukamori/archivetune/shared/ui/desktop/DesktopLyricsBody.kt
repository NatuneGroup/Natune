package moe.rukamori.archivetune.shared.ui.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.host.HostLyricsDocument
import moe.rukamori.archivetune.shared.ui.host.HostLyricsStatus
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * What the lyrics surface shows: the renderer once a document has lines, and otherwise the state of the lookup.
 *
 * A miss used to be the same empty box as a read in flight or a failed one, so a track no provider knows looked like a
 * broken pane. Every state but [HostLyricsStatus.READY] now names itself and offers the lookup again, which the host
 * turns into one forced call through the providers.
 */
@Composable
fun DesktopLyricsBody(
    document: HostLyricsDocument?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    val status = document?.status ?: HostLyricsStatus.LOADING
    if (status == HostLyricsStatus.READY && document?.hasLines == true) {
        content(modifier)
        return
    }
    Column(
        modifier.fillMaxSize().padding(24.dp).testTag("lyrics-status-${status.name.lowercase()}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (status) {
            HostLyricsStatus.LOADING, HostLyricsStatus.READY -> {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(stringResource(Res.string.m4_loading), Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyMedium)
            }
            HostLyricsStatus.NOT_FOUND -> {
                Text(stringResource(Res.string.desktop_shell_lyrics_empty), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleSmall)
                Text(stringResource(Res.string.desktop_shell_lyrics_empty_hint), textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp))
                RetryButton(onRetry, Modifier.padding(top = 12.dp))
            }
            HostLyricsStatus.FAILED -> {
                Text(stringResource(Res.string.desktop_shell_lyrics_failed), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleSmall)
                Text(stringResource(Res.string.desktop_shell_lyrics_failed_hint), textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp))
                RetryButton(onRetry, Modifier.padding(top = 12.dp))
            }
        }
    }
}

@Composable
private fun RetryButton(onRetry: () -> Unit, modifier: Modifier) {
    Button(onClick = onRetry, modifier = modifier.testTag("lyrics-retry")) {
        Text(stringResource(Res.string.desktop_shell_lyrics_retry))
    }
}
