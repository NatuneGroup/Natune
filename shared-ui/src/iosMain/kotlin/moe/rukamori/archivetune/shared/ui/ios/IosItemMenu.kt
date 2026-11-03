@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkReader
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkSource
import moe.rukamori.archivetune.shared.domain.platform.FileHandle
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.ui.IosHost
import moe.rukamori.archivetune.shared.ui.screens.customartwork.BoundCustomArtworkEditDialog
import moe.rukamori.archivetune.shared.ui.screens.customartwork.CustomArtworkKind
import platform.Foundation.NSUUID

/**
 * The one row menu of the iOS host. A row's overflow opens it on that row's occurrence, and the shared song menu runs
 * against the owners this host connects (see [IosSongMenu]). A page action whose owner is not connected reports the
 * unavailable notice through [refuse] instead of doing nothing.
 */
internal class IosItemMenuState {
    /** The row the menu is open for, or null while it is closed. */
    var target: ContentOccurrence? by mutableStateOf(null)
        private set

    /** The editable local playlist the row sits in, so its "remove from playlist" row commits against that playlist. */
    var playlistId: String? by mutableStateOf(null)
        private set

    /** The song whose custom artwork editor is open, or null. It replaces the menu rather than stacking on it. */
    var artworkTarget: ContentItem? by mutableStateOf(null)
        private set

    /** The notice key a failed or unconnected action leaves for the user, or null. */
    var message: String? by mutableStateOf(null)
        private set

    private var refusedAsUnavailable = false

    fun open(occurrence: ContentOccurrence, playlistId: String? = null) {
        target = occurrence
        this.playlistId = playlistId
    }

    fun dismiss() {
        target = null
        playlistId = null
        artworkTarget = null
    }

    fun openCustomArtwork() {
        val occurrence = target ?: return
        openCustomArtwork(occurrence.item)
    }

    /** Opens the artwork editor on [item] — a song's artwork URL or a playlist's cover pseudo-URL. */
    fun openCustomArtwork(item: ContentItem) {
        target = null
        playlistId = null
        artworkTarget = item
    }

    fun closeCustomArtwork() {
        artworkTarget = null
    }

    fun dismissMessage() {
        message = null
    }

    /** A failed menu row: the unavailable notice for a row this host does not connect, otherwise the failure. */
    fun failed() {
        message = if (refusedAsUnavailable) NOTICE_UNAVAILABLE else NOTICE_FAILED
        refusedAsUnavailable = false
    }

    /** A page action this host does not connect: the menu closes and the unavailable notice says so. */
    fun refuse() {
        dismiss()
        refusedAsUnavailable = false
        message = NOTICE_UNAVAILABLE
    }

    /** A download of a provider this host cannot store: the menu closes and the notice names the limit. */
    fun refuseDownload() {
        dismiss()
        refusedAsUnavailable = false
        message = NOTICE_DOWNLOAD_REFUSED
    }

    internal fun unavailable(): Nothing {
        refusedAsUnavailable = true
        throw UnsupportedOperationException("not connected on the iOS host")
    }

    companion object {
        const val NOTICE_UNAVAILABLE = "ios_settings_feature_unavailable"
        const val NOTICE_FAILED = "ios_menu_action_failed"
        const val NOTICE_DOWNLOAD_REFUSED = "ios_download_refused"
    }
}

/**
 * A row keeps the occurrence id of its list, and the queue already holds that id once the list plays, so it would
 * refuse the insert. Every queue command therefore gets its own occurrence of the same item.
 */
internal fun ContentOccurrence.queuedEntry(): ContentOccurrence =
    ContentOccurrence("queued:${NSUUID().UUIDString}", item, setVideoId)

/**
 * The row menu over the page it opened on. The navigation host does not key the route body, so the effect is keyed on
 * the route itself: leaving a page closes its menu, and a menu opened on one page is never shown again on another
 * page. The shared menu is a dropdown, so it is anchored at the middle of the screen rather than its top corner.
 */
@Composable
internal fun IosItemMenuDialog(
    menu: IosItemMenuState,
    graph: IosSessionGraph,
    host: IosHost,
    routeKey: String,
    openRoute: (String) -> Unit,
    playlistMenus: IosPlaylistMenuHost,
    sleepSheet: IosSleepTimerSheetState,
    tempoSheet: IosTempoPitchSheetState,
) {
    DisposableEffect(routeKey) { onDispose { menu.dismiss() } }
    menu.artworkTarget?.let { item ->
        BoundCustomArtworkEditDialog(
            artworkUrl = item.artworkUri,
            // Canvas plays on this host, so a stored canvas shows; the song id opens the canvas rows to a SONG item,
            // matching the desktop dialog. Other kinds carry none and keep the unsupported state.
            songId = item.ref.id.takeIf { item.ref.kind == ContentKind.SONG },
            repository = graph.customArtwork,
            pick = { pickIosArtworkFile(host, it) },
            onDismiss = menu::closeCustomArtwork,
            preview = { modifier -> IosRemoteArtwork(item.artworkUri, graph.artworkLoader, modifier) },
        )
    }
    menu.target?.let { occurrence ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box {
                if (occurrence.item.ref.kind == ContentKind.SONG || occurrence.item.ref.kind == ContentKind.EPISODE) {
                    IosSongMenu(menu, graph, host, occurrence, openRoute, sleepSheet, tempoSheet)
                } else if (
                    occurrence.item.ref.kind == ContentKind.PLAYLIST &&
                    occurrence.item.ref.provider == ContentProvider.LOCAL.id
                ) {
                    IosPlaylistRowMenu(menu, graph, host, occurrence, playlistMenus)
                } else {
                    IosCollectionRowMenu(menu, host, occurrence)
                }
            }
        }
    }
    menu.message?.let { IosMenuNotice(it, menu::dismissMessage) }
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
