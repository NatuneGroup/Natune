package moe.rukamori.archivetune.ui.screens.library

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.shared.data.provider.YouTubeUploadFailure
import moe.rukamori.archivetune.shared.data.provider.YouTubeUploadedSong
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.LargeFrostedTopAppBar
import moe.rukamori.archivetune.ui.component.LocalMenuState
import moe.rukamori.archivetune.ui.component.YouTubeListItem
import moe.rukamori.archivetune.ui.component.shimmer.ListItemPlaceHolder
import moe.rukamori.archivetune.ui.component.shimmer.ShimmerHost
import moe.rukamori.archivetune.ui.menu.YouTubeSongMenu
import moe.rukamori.archivetune.uploads.UploadedSongsViewModel
import moe.rukamori.archivetune.uploads.YouTubeUploadStage
import moe.rukamori.archivetune.uploads.YouTubeUploadTask
import moe.rukamori.archivetune.uploads.messageRes
import moe.rukamori.archivetune.uploads.toSongItem

/** Document types the picker offers. The upload policy still checks the extension before any byte is sent. */
private val uploadMimeTypes = arrayOf("audio/*")

private const val LOADING_KEY = "uploads_loading"

/**
 * The signed-in account's uploaded songs, with the transfers that are still running above them.
 * Playback follows the shelf order, and deleting an upload always asks first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadedSongsScreen(
    navController: NavController,
    viewModel: UploadedSongsViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val menuState = LocalMenuState.current
    val context = LocalContext.current
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()

    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val failure by viewModel.failure.collectAsStateWithLifecycle()
    val hasMore by viewModel.hasMore.collectAsStateWithLifecycle()
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()

    val title = stringResource(R.string.uploads)
    val queueItems = remember(songs) { songs.map { song -> song.toSongItem().toMediaItem() } }
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var pendingDelete by remember { mutableStateOf<YouTubeUploadedSong?>(null) }
    val bottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding() + 20.dp

    val pickFile =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(viewModel::upload)
        }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            val arg = message.arg
            val text =
                if (arg == null) {
                    context.getString(message.textRes)
                } else {
                    context.getString(message.textRes, arg)
                }
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == LOADING_KEY } }
            .collect { shouldLoadMore ->
                if (shouldLoadMore) viewModel.loadMore()
            }
    }

    pendingDelete?.let { song ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(text = stringResource(R.string.delete_upload_confirm_title)) },
            text = { Text(text = stringResource(R.string.delete_upload_confirm_message, song.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        viewModel.delete(song)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(text = stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(text = stringResource(R.string.cancel_button))
                }
            },
        )
    }

    Scaffold(
        modifier =
            Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeFrostedTopAppBar(
                titleRes = R.string.uploads,
                onBack = navController::navigateUp,
                actions = {
                    IconButton(onClick = { pickFile.launch(uploadMimeTypes) }, onLongClick = {}) {
                        Icon(
                            painter = painterResource(R.drawable.add),
                            contentDescription = stringResource(R.string.upload_file),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            contentPadding = PaddingValues(top = 12.dp, bottom = bottomPadding),
        ) {
            items(items = tasks, key = { task -> "upload_task_${task.id}" }) { task ->
                UploadTaskRow(task)
            }

            failure?.let { reason ->
                item(key = "uploads_failure") {
                    UploadFailureRow(reason = reason, onRetry = viewModel::refresh)
                }
            }

            if (songs.isEmpty() && loading) {
                item(key = "uploads_placeholders") {
                    ShimmerHost(modifier = Modifier.fillMaxWidth()) {
                        repeat(6) { ListItemPlaceHolder() }
                    }
                }
            } else if (songs.isEmpty() && failure == null) {
                item(key = "uploads_empty") {
                    EmptyUploadsRow(onUpload = { pickFile.launch(uploadMimeTypes) })
                }
            }

            items(items = songs, key = { song -> "uploaded_${song.videoId}" }) { song ->
                val songItem = remember(song) { song.toSongItem() }
                YouTubeListItem(
                    item = songItem,
                    isActive = mediaMetadata?.id == songItem.id,
                    isPlaying = isPlaying,
                    trailingContent = {
                        IconButton(
                            onClick = {
                                menuState.show {
                                    YouTubeSongMenu(
                                        song = songItem,
                                        navController = navController,
                                        onDismiss = menuState::dismiss,
                                        onDeleteUpload = { pendingDelete = song },
                                    )
                                }
                            },
                            onLongClick = {},
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.more_vert),
                                contentDescription = null,
                            )
                        }
                    },
                    modifier =
                        Modifier.clickable {
                            if (songItem.id == mediaMetadata?.id) {
                                playerConnection.player.togglePlayPause()
                            } else {
                                playerConnection.playShelfFrom(queueItems, songItem.id, title)
                            }
                        },
                )
            }

            if (hasMore) {
                item(key = LOADING_KEY) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@Composable
private fun UploadTaskRow(task: YouTubeUploadTask) {
    val percent = task.percent
    val stageText =
        when {
            task.stage == YouTubeUploadStage.UPLOADING && percent != null ->
                stringResource(R.string.upload_task_uploading, percent)

            task.stage == YouTubeUploadStage.PROCESSING -> stringResource(R.string.upload_task_processing)
            else -> stringResource(R.string.upload_task_queued)
        }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        Text(
            text = task.name.takeIf { it.isNotEmpty() } ?: stringResource(R.string.uploads),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stageText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (task.stage == YouTubeUploadStage.UPLOADING && percent != null) {
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
    }
}

@Composable
private fun UploadFailureRow(
    reason: YouTubeUploadFailure,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(reason.messageRes()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        if (reason != YouTubeUploadFailure.SIGN_IN_REQUIRED) {
            Button(onClick = onRetry) {
                Text(text = stringResource(R.string.retry))
            }
        }
    }
}

@Composable
private fun EmptyUploadsRow(onUpload: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.uploads_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onUpload) {
            Text(text = stringResource(R.string.upload_file))
        }
    }
}
