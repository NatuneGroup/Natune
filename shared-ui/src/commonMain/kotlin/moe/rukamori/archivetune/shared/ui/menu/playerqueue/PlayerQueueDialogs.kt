package moe.rukamori.archivetune.shared.ui.menu.playerqueue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import moe.rukamori.archivetune.shared.ui.resources.*

/**
 * The dialog surface the baseline's `DefaultDialog` drew: the alert shape and tonal container, inset 24 dp from the
 * window and clear of the IME and the navigation bar. [title] sits above the body in the alert headline style, and
 * [buttons] wrap at the end edge in the label style and primary tint, as the baseline's did.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlayerQueueDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: (@Composable () -> Unit)? = null,
    buttons: (@Composable () -> Unit)? = null,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .imePadding()
                    .navigationBarsPadding(),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.heightIn(max = maxHeight),
                shape = AlertDialogDefaults.shape,
                color = AlertDialogDefaults.containerColor,
                tonalElevation = AlertDialogDefaults.TonalElevation,
                shadowElevation = 6.dp,
            ) {
                Column(modifier = modifier.padding(24.dp)) {
                    Column(horizontalAlignment = horizontalAlignment) {
                        if (title != null) {
                            CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.titleContentColor) {
                                ProvideTextStyle(MaterialTheme.typography.headlineSmall) {
                                    Box(Modifier.align(Alignment.Start)) {
                                        title()
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                        content()
                    }
                    if (buttons != null) {
                        Spacer(modifier = Modifier.height(24.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
                                ProvideTextStyle(value = MaterialTheme.typography.labelLarge) {
                                    buttons()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A dialog whose body is a lazy list, as the baseline's `ListDialog` was. Used for the artist picker. */
@Composable
internal fun PlayerQueueListDialog(
    onDismiss: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .imePadding()
                    .navigationBarsPadding(),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.heightIn(max = maxHeight),
                shape = AlertDialogDefaults.shape,
                color = AlertDialogDefaults.containerColor,
                tonalElevation = AlertDialogDefaults.TonalElevation,
                shadowElevation = 6.dp,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(vertical = 24.dp),
                ) {
                    LazyColumn(content = content)
                }
            }
        }
    }
}

/**
 * The finite YouTube playlist loader dialog. It shows only while the host reports a loading or incomplete playlist with
 * the dialog flag set. Dismissing a loading playlist cancels the load, as the baseline's `YouTubePlaylistLoadDialog` did.
 */
@Composable
internal fun PlayerQueueYouTubePlaylistDialog(
    state: PlayerQueuePlaylistLoadState,
    actions: PlayerQueueMenuActions,
) {
    if (!state.showDialog || (!state.loading && !state.incomplete)) return
    PlayerQueueDialog(
        onDismiss = {
            if (state.loading) actions.cancelPlaylistLoading()
            actions.dismissPlaylistLoadError()
        },
        buttons = {
            if (state.incomplete) {
                TextButton(onClick = { actions.retryPlaylistLoading() }) {
                    Text(stringResource(Res.string.m5pq_youtube_playlist_retry))
                }
                if (!state.wholeAction) {
                    TextButton(onClick = { actions.reloadFinitePlaylist() }) {
                        Text(stringResource(Res.string.m5pq_youtube_playlist_reload))
                    }
                }
            }
            TextButton(
                onClick = {
                    if (state.loading) actions.cancelPlaylistLoading()
                    actions.dismissPlaylistLoadError()
                },
            ) {
                Text(stringResource(Res.string.m5pq_cancel))
            }
        },
    ) {
        Text(
            text =
                stringResource(
                    if (state.loading) {
                        Res.string.m5pq_youtube_playlist_loading
                    } else {
                        Res.string.m5pq_youtube_playlist_incomplete
                    },
                ),
        )
    }
}

/** The inline recovery row shown at the top of the menu while a finite YouTube playlist loads or stays incomplete. */
@Composable
internal fun PlayerQueueYouTubePlaylistRecoveryActions(
    state: PlayerQueuePlaylistLoadState,
    actions: PlayerQueueMenuActions,
) {
    if (!state.loading && !state.incomplete) return
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text =
                stringResource(
                    if (state.loading) {
                        Res.string.m5pq_youtube_playlist_loading
                    } else {
                        Res.string.m5pq_youtube_playlist_incomplete
                    },
                ),
        )
        if (state.loading) {
            TextButton(onClick = { actions.cancelPlaylistLoading() }) {
                Text(stringResource(Res.string.m5pq_youtube_playlist_cancel))
            }
        } else {
            TextButton(onClick = { actions.retryPlaylistLoading() }) {
                Text(stringResource(Res.string.m5pq_youtube_playlist_retry))
            }
            if (!state.wholeAction) {
                TextButton(onClick = { actions.reloadFinitePlaylist() }) {
                    Text(stringResource(Res.string.m5pq_youtube_playlist_reload))
                }
            }
        }
    }
}
