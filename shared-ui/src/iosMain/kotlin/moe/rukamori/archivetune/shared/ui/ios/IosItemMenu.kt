@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.ui.ios

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkReader
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkSource
import moe.rukamori.archivetune.shared.domain.platform.FileHandle
import moe.rukamori.archivetune.shared.domain.queue.UserQueuedKind
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.ui.IosHost
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.resources.m5pq_add_to_queue
import moe.rukamori.archivetune.shared.ui.resources.m5pq_play_next
import moe.rukamori.archivetune.shared.ui.resources.m4_custom_artwork_title
import moe.rukamori.archivetune.shared.ui.resources.m6_cancel
import moe.rukamori.archivetune.shared.ui.screens.customartwork.BoundCustomArtworkEditDialog
import moe.rukamori.archivetune.shared.ui.screens.customartwork.CustomArtworkKind
import org.jetbrains.compose.resources.stringResource
import platform.Foundation.NSUUID

/**
 * The one row menu of the iOS host. A row's overflow opens it on that row's occurrence. It offers the queue commands
 * the iOS queue owner answers and, for a song, the custom artwork editor over the portable repository. The rest of the
 * baseline song menu needs library, download, share and artist owners that this host does not connect yet, so those
 * rows are left out instead of appearing as no-ops.
 */
internal class IosItemMenuState {
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
     * Queues the open row with [kind] in the one queue. The menu closes before the command runs, so a second click
     * cannot queue the row twice. A refused command is written to the host log, as every other iOS action is.
     */
    fun enqueue(graph: IosSessionGraph, kind: UserQueuedKind) {
        val occurrence = target ?: return
        target = null
        graph.scope.launch {
            try {
                graph.queue.insert(listOf(occurrence.queuedEntry()), kind)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("the row was not queued: ${failure::class.simpleName}")
            }
        }
    }
}

/**
 * A row keeps the occurrence id of its list, and the queue already holds that id once the list plays, so it would
 * refuse the insert. Every queue command therefore gets its own occurrence of the same item.
 */
internal fun ContentOccurrence.queuedEntry(): ContentOccurrence =
    ContentOccurrence("queued:${NSUUID().UUIDString}", item, setVideoId)

/**
 * The row menu as a dialog over the page it opened on. The navigation host does not key the route body, so the effect
 * is keyed on the route itself: leaving a page closes its menu, and a menu opened on one page is never shown again on
 * another page.
 */
@Composable
internal fun IosItemMenuDialog(menu: IosItemMenuState, graph: IosSessionGraph, host: IosHost, routeKey: String) {
    DisposableEffect(routeKey) { onDispose { menu.dismiss() } }
    menu.artworkTarget?.let { item ->
        BoundCustomArtworkEditDialog(
            artworkUrl = item.artworkUri,
            // This host plays no canvas loop, so a stored canvas would never show; without a song id the canvas rows
            // report unsupported instead of saving one.
            songId = null,
            repository = graph.customArtwork,
            pick = { pickIosArtworkFile(host, it) },
            onDismiss = menu::closeCustomArtwork,
            preview = { modifier -> IosRemoteArtwork(item.artworkUri, graph.artworkLoader, modifier) },
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
                ) { Text(stringResource(Res.string.m5pq_play_next)) }
                TextButton(
                    onClick = { menu.enqueue(graph, UserQueuedKind.ADD_TO_QUEUE) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(Res.string.m5pq_add_to_queue)) }
                if (occurrence.item.ref.kind == ContentKind.SONG) {
                    TextButton(onClick = menu::openCustomArtwork, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.m4_custom_artwork_title))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = menu::dismiss) { Text(stringResource(Res.string.m6_cancel)) } },
    )
}

/**
 * The system document picker for one custom artwork pick. It suspends until the user picks a file or cancels. The
 * picked file stays readable through its security-scoped bookmark; the original name is not kept, so the extension
 * hint stays null and the repository's decodability check decides.
 */
private suspend fun pickIosArtworkFile(host: IosHost, kind: CustomArtworkKind): CustomArtworkSource? {
    val handle = try {
        host.files.pickDocument(host.viewController)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        host.report("the file chooser was not available: ${failure::class.simpleName}")
        null
    } ?: return null
    return IosPickedArtworkSource(host, handle)
}

/** The picked document behind [handle]: the bytes are read once through the platform files port, then served chunked. */
private class IosPickedArtworkSource(private val host: IosHost, private val handle: FileHandle) : CustomArtworkSource {
    override val mimeType: String? = null
    override val fileExtension: String? = null

    override suspend fun open(): CustomArtworkReader? {
        val bytes = try {
            host.files.read(handle, MAX_ARTWORK_BYTES)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            host.report("the picked file could not be read: ${failure::class.simpleName}")
            return null
        }
        return IosPickedArtworkReader(bytes)
    }
}

private class IosPickedArtworkReader(private val bytes: ByteArray) : CustomArtworkReader {
    private var offset = 0

    override suspend fun read(buffer: ByteArray): Int {
        val remaining = bytes.size - offset
        if (remaining <= 0) return -1
        val count = minOf(buffer.size, remaining)
        bytes.copyInto(buffer, destinationOffset = 0, startIndex = offset, endIndex = offset + count)
        offset += count
        return count
    }

    override suspend fun close() {}
}

private const val MAX_ARTWORK_BYTES = 64L * 1024 * 1024
