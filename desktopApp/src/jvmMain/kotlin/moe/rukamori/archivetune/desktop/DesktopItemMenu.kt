package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.domain.queue.UserQueuedKind
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoutes
import moe.rukamori.archivetune.shared.ui.screens.customartwork.BoundCustomArtworkEditDialog
import org.jetbrains.compose.resources.stringResource
import moe.rukamori.archivetune.shared.ui.resources.m5pqActionDownloadRes
import moe.rukamori.archivetune.shared.ui.resources.m5pqDetailsRes
import moe.rukamori.archivetune.shared.ui.resources.m5pqRemoveDownloadRes
import moe.rukamori.archivetune.shared.ui.resources.m6TrackInfoRes

/**
 * The one row menu of the desktop host. A row's overflow opens it on that row's occurrence. It offers the queue
 * commands the desktop queue owner answers and, for a song, the custom artwork editor. The rest of the baseline song
 * menu needs library, download, share and artist owners that this host does not connect yet, so those rows are left out
 * instead of appearing as no-ops.
 */
internal class DesktopItemMenuState {
    /** The row the menu is open for, or null while it is closed. The dialog reads it, so a click shows the menu at once. */
    var target: ContentOccurrence? by mutableStateOf(null)
        private set

    /** The song whose custom artwork editor is open, or null. It replaces the menu rather than stacking on it. */
    var artworkTarget: ContentItem? by mutableStateOf(null)
        private set

    fun open(occurrence: ContentOccurrence) {
        target = occurrence
    }

    fun dismiss() {
        target = null
        artworkTarget = null
    }

    fun openCustomArtwork() {
        val occurrence = target ?: return
        target = null
        artworkTarget = occurrence.item
    }

    fun closeCustomArtwork() {
        artworkTarget = null
    }

    /**
     * Queues the open row with [kind] in the one queue. The menu closes before the command runs, so a second click cannot
     * queue the row twice. A refused command is written to the local log, as every other desktop action is.
     */
    fun enqueue(graph: DesktopSessionGraph, kind: UserQueuedKind) {
        val occurrence = target ?: return
        target = null
        graph.scope.launch {
            try {
                graph.queue.insert(listOf(occurrence.queuedEntry()), kind)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("the row was not queued: ${failure.javaClass.simpleName}")
            }
        }
    }
}

/**
 * A row keeps the occurrence id of its list, and the queue already holds that id once the list plays, so it would refuse
 * the insert. Every queue command therefore gets its own occurrence of the same item.
 */
internal fun ContentOccurrence.queuedEntry(): ContentOccurrence =
    ContentOccurrence("queued:${UUID.randomUUID()}", item, setVideoId)

/**
 * The row menu as a dialog over the page it opened on. The navigation host does not key the route body, so the effect
 * is keyed on the route itself: leaving a page closes its menu, and a menu opened on one page is never shown again on
 * another page.
 */
@Composable
internal fun DesktopItemMenuDialog(
    menu: DesktopItemMenuState,
    graph: DesktopSessionGraph,
    routeKey: String,
    openRoute: (String) -> Unit = {},
) {
    DisposableEffect(routeKey) { onDispose { menu.dismiss() } }
    menu.artworkTarget?.let { item ->
        BoundCustomArtworkEditDialog(
            artworkUrl = item.artworkUri,
            // This host plays no canvas loop, so a stored canvas would never show; without a song id the canvas rows
            // report unsupported instead of saving one.
            songId = null,
            repository = graph.customArtwork,
            pick = ::pickDesktopArtworkFile,
            onDismiss = menu::closeCustomArtwork,
            preview = { modifier -> DesktopRemoteArtwork(item.artworkUri, graph.artworkLoader, modifier) },
        )
    }
    val occurrence = menu.target ?: return
    AlertDialog(
        onDismissRequest = menu::dismiss,
        title = { Text(occurrence.item.title) },
        text = {
            Column {
                TextButton(
                    onClick = { menu.enqueue(graph, UserQueuedKind.PLAY_NEXT) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(DesktopStrings["play_next"]) }
                TextButton(
                    onClick = { menu.enqueue(graph, UserQueuedKind.ADD_TO_QUEUE) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(DesktopStrings["add_to_queue"]) }
                if (occurrence.item.ref.kind == ContentKind.SONG) {
                    TextButton(onClick = menu::openCustomArtwork, modifier = Modifier.fillMaxWidth()) {
                        Text(DesktopStrings["custom_artwork"])
                    }
                    // Download/remove reflect the stored-song rows, not a guessed flag.
                    val mediaId = occurrence.item.ref.id
                    val provider = occurrence.item.ref.provider
                    val stored by androidx.compose.runtime.produceState<Boolean?>(null, mediaId, provider) {
                        value = try {
                            graph.downloads.listedRows().any { it.provider == provider && it.mediaId == mediaId }
                        } catch (error: Exception) {
                            null
                        }
                    }
                    val downloaded = stored
                    if (downloaded != null && provider != "local") {
                        TextButton(
                            onClick = {
                                menu.dismiss()
                                graph.scope.launch {
                                    try {
                                        if (downloaded) {
                                            graph.downloads.remove("$provider:$mediaId")
                                        } else {
                                            graph.downloads.start(occurrence.item.ref, "")
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        graph.report("the download action failed: ${failure.javaClass.simpleName}")
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(if (downloaded) m5pqRemoveDownloadRes
                                else m5pqActionDownloadRes))
                        }
                    }
                    TextButton(
                        onClick = { menu.dismiss(); openRoute(ArchiveRoutes.trackInfo(mediaId)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(m6TrackInfoRes)) }
                    TextButton(
                        onClick = { menu.dismiss(); openRoute(ArchiveRoutes.mediaInfo(mediaId)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(m5pqDetailsRes)) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = menu::dismiss) { Text(DesktopStrings["cancel"]) } },
    )
}
