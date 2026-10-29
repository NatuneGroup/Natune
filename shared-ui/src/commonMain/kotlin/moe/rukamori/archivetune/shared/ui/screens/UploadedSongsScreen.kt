package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.m4_retry
import moe.rukamori.archivetune.shared.ui.resources.m6_cancel
import moe.rukamori.archivetune.shared.ui.resources.uploads_delete
import moe.rukamori.archivetune.shared.ui.resources.uploads_delete_confirm
import moe.rukamori.archivetune.shared.ui.resources.uploads_delete_message
import moe.rukamori.archivetune.shared.ui.resources.uploads_delete_title
import moe.rukamori.archivetune.shared.ui.resources.uploads_empty
import moe.rukamori.archivetune.shared.ui.resources.uploads_failure_delete
import moe.rukamori.archivetune.shared.ui.resources.uploads_failure_empty
import moe.rukamori.archivetune.shared.ui.resources.uploads_failure_http
import moe.rukamori.archivetune.shared.ui.resources.uploads_failure_read
import moe.rukamori.archivetune.shared.ui.resources.uploads_failure_too_large
import moe.rukamori.archivetune.shared.ui.resources.uploads_failure_unknown
import moe.rukamori.archivetune.shared.ui.resources.uploads_failure_unsupported
import moe.rukamori.archivetune.shared.ui.resources.uploads_load_more
import moe.rukamori.archivetune.shared.ui.resources.uploads_sign_in_required
import moe.rukamori.archivetune.shared.ui.resources.uploads_task_accepted
import moe.rukamori.archivetune.shared.ui.resources.uploads_task_already
import moe.rukamori.archivetune.shared.ui.resources.uploads_task_processing
import moe.rukamori.archivetune.shared.ui.resources.uploads_task_queued
import moe.rukamori.archivetune.shared.ui.resources.uploads_task_uploading
import moe.rukamori.archivetune.shared.ui.resources.uploads_title
import moe.rukamori.archivetune.shared.ui.resources.uploads_unavailable_platform
import moe.rukamori.archivetune.shared.ui.resources.uploads_upload_file
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Where one transfer stands. ACCEPTED and ALREADY_THERE are terminal and stay until the shelf is re-read. */
enum class UploadTaskStage { QUEUED, UPLOADING, PROCESSING, ACCEPTED, ALREADY_THERE, FAILED }

/** Why a page, upload or delete could not complete; the screen carries the wording. */
enum class UploadsFailure { SIGN_IN, UNSUPPORTED_TYPE, EMPTY_FILE, TOO_LARGE, HTTP, READ, DELETE, UNKNOWN }

data class UploadTaskUi(
    val id: String,
    val name: String,
    val stage: UploadTaskStage,
    val percent: Int? = null,
    val failure: UploadsFailure? = null,
)

/**
 * UI projection of the uploads owner. [available] is false on a host with no upload transport (iOS today);
 * the screen says so instead of showing an empty library. [failure] is the last page or delete failure.
 */
data class UploadsUi(
    val rows: List<ContentOccurrence> = emptyList(),
    val tasks: List<UploadTaskUi> = emptyList(),
    val available: Boolean = true,
    val signedIn: Boolean = true,
    val loading: Boolean = false,
    val failure: UploadsFailure? = null,
    val hasMore: Boolean = false,
)

/** The owner's operations. [delete] is reached only through the screen's confirmation dialog. */
class UploadsActions(
    val back: () -> Unit,
    val upload: () -> Unit,
    val delete: (ContentOccurrence) -> Unit,
    val play: (selected: ContentOccurrence, visibleQueue: List<ContentOccurrence>) -> Unit,
    val retry: () -> Unit,
    val loadMore: () -> Unit,
)

private fun failureText(failure: UploadsFailure): StringResource =
    when (failure) {
        UploadsFailure.SIGN_IN -> Res.string.uploads_sign_in_required
        UploadsFailure.UNSUPPORTED_TYPE -> Res.string.uploads_failure_unsupported
        UploadsFailure.EMPTY_FILE -> Res.string.uploads_failure_empty
        UploadsFailure.TOO_LARGE -> Res.string.uploads_failure_too_large
        UploadsFailure.HTTP -> Res.string.uploads_failure_http
        UploadsFailure.READ -> Res.string.uploads_failure_read
        UploadsFailure.DELETE -> Res.string.uploads_failure_delete
        UploadsFailure.UNKNOWN -> Res.string.uploads_failure_unknown
    }

@Composable
private fun taskText(task: UploadTaskUi): String =
    when (task.stage) {
        UploadTaskStage.QUEUED -> stringResource(Res.string.uploads_task_queued)
        UploadTaskStage.UPLOADING -> stringResource(Res.string.uploads_task_uploading, task.percent ?: 0)
        UploadTaskStage.PROCESSING -> stringResource(Res.string.uploads_task_processing)
        UploadTaskStage.ACCEPTED -> stringResource(Res.string.uploads_task_accepted)
        UploadTaskStage.ALREADY_THERE -> stringResource(Res.string.uploads_task_already)
        UploadTaskStage.FAILED -> stringResource(failureText(task.failure ?: UploadsFailure.UNKNOWN))
    }

/**
 * The signed-in account's uploaded songs, with the running transfers above them. Rows play like any library
 * song; the delete action always asks first, and the owner reloads the shelf only after the service confirms.
 */
@Composable
fun UploadedSongsScreen(
    state: UploadsUi,
    actions: UploadsActions,
    artwork: ContentArtwork,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = LocalShellContentPadding.current,
) {
    var pendingDelete by remember { mutableStateOf<ContentOccurrence?>(null) }
    LazyColumn(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = contentPadding,
    ) {
        item("header") {
            ManagementHeader(stringResource(Res.string.uploads_title), actions.back)
        }
        if (!state.available) {
            item("unavailable") {
                Text(
                    stringResource(Res.string.uploads_unavailable_platform),
                    Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@LazyColumn
        }
        if (!state.signedIn) {
            item("sign-in") {
                Text(
                    stringResource(Res.string.uploads_sign_in_required),
                    Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@LazyColumn
        }
        item("upload") {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Button(actions.upload, Modifier.testTag("uploads-pick-file")) {
                    Text(stringResource(Res.string.uploads_upload_file))
                }
            }
        }
        items(state.tasks, key = { "upload-task-${it.id}" }) { task ->
            UploadTaskRow(task)
        }
        state.failure?.let { failure ->
            item("failure") {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(stringResource(failureText(failure)), color = MaterialTheme.colorScheme.error)
                    TextButton(actions.retry) { Text(stringResource(Res.string.m4_retry)) }
                }
            }
        }
        if (state.loading && state.rows.isEmpty()) {
            item("load") { LoadFeedback(loading = true, failed = false, empty = false, retry = actions.retry) }
        } else if (state.rows.isEmpty() && state.failure == null && state.tasks.isEmpty()) {
            item("empty") {
                Text(
                    stringResource(Res.string.uploads_empty),
                    Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(state.rows, key = { "uploaded-${it.occurrenceId}" }) { row ->
            ListItem(
                headlineContent = { Text(row.item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(
                        listOf(row.item.subtitle, row.item.album.orEmpty())
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingContent = { artwork(row.item, Modifier.size(48.dp)) },
                trailingContent = {
                    TextButton(
                        onClick = { pendingDelete = row },
                        modifier = Modifier.testTag("upload-delete-${row.item.ref.id}"),
                    ) {
                        Text(stringResource(Res.string.uploads_delete), color = MaterialTheme.colorScheme.error)
                    }
                },
                modifier = Modifier
                    .testTag("uploaded-song-${row.item.ref.id}")
                    .clickable { actions.play(row, state.rows) },
            )
        }
        if (state.hasMore) {
            item("more") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(actions.loadMore, Modifier.padding(8.dp), enabled = !state.loading) {
                        Text(stringResource(Res.string.uploads_load_more))
                    }
                }
            }
        }
    }
    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(Res.string.uploads_delete_title)) },
            text = { Text(stringResource(Res.string.uploads_delete_message, row.item.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        actions.delete(row)
                    },
                ) {
                    Text(stringResource(Res.string.uploads_delete_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(Res.string.m6_cancel))
                }
            },
        )
    }
}

@Composable
private fun UploadTaskRow(task: UploadTaskUi) {
    ListItem(
        headlineContent = {
            Text(
                task.name.ifBlank { stringResource(Res.string.uploads_title) },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
                Text(
                    taskText(task),
                    color = if (task.stage == UploadTaskStage.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                when (task.stage) {
                    UploadTaskStage.UPLOADING ->
                        LinearProgressIndicator(
                            progress = { (task.percent ?: 0) / 100f },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )

                    UploadTaskStage.QUEUED,
                    UploadTaskStage.PROCESSING,
                    ->
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))

                    else -> Unit
                }
            }
        },
    )
}
