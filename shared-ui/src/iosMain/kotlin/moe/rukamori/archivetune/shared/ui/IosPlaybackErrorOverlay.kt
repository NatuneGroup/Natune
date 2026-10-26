package moe.rukamori.archivetune.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import moe.rukamori.archivetune.shared.ui.player.PlaybackErrorActions
import moe.rukamori.archivetune.shared.ui.player.PlaybackErrorDialog
import moe.rukamori.archivetune.shared.ui.theme.ArchiveTuneTheme
import platform.UIKit.UIPasteboard

/**
 * The playback error dialog of the iOS host, over whatever the root shows. Retry hands the failed occurrence to the
 * queue composition that installed [IosHost.installPlaybackRetry]; until one has, Retry only closes the dialog, because
 * this host owns no queue to load the song from. iOS has no feedback service, so Send feedback is not shown.
 */
@Composable
internal fun IosPlaybackErrorOverlay(host: IosHost) {
    val failure by host.playbackFailure.collectAsState()
    val current = failure ?: return
    ArchiveTuneTheme {
        PlaybackErrorDialog(
            failure = current,
            actions = PlaybackErrorActions(
                retry = {
                    host.closePlaybackFailure()
                    host.retryPlayback(current.occurrenceId)
                },
                close = host::closePlaybackFailure,
                copyToClipboard = { details -> UIPasteboard.generalPasteboard.string = details },
            ),
        )
    }
}
