package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import moe.rukamori.archivetune.shared.domain.library.AutoPlaylistSongSortType
import moe.rukamori.archivetune.shared.domain.library.LibrarySortState
import moe.rukamori.archivetune.shared.domain.queue.UserQueuedKind
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.HistoryOrigin
import moe.rukamori.archivetune.shared.domain.repository.QueueController
import moe.rukamori.archivetune.shared.ui.host.queuedEntry
import moe.rukamori.archivetune.shared.ui.menu.CollectionDownloadState
import moe.rukamori.archivetune.shared.ui.menu.MenuShareHandler
import moe.rukamori.archivetune.shared.ui.menu.MenuSharing
import moe.rukamori.archivetune.shared.ui.menu.OverflowAction
import moe.rukamori.archivetune.shared.ui.menu.SharedOverflowMenu
import moe.rukamori.archivetune.shared.ui.menu.SongMenuFacts
import moe.rukamori.archivetune.shared.ui.menu.SongMenuHost
import moe.rukamori.archivetune.shared.ui.menu.SongOverflowMenu
import moe.rukamori.archivetune.shared.ui.menu.playlistOccurrenceRemoval
import moe.rukamori.archivetune.shared.ui.menu.songLibraryAndDownloadActions
import moe.rukamori.archivetune.shared.ui.menu.songOverflowActions
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.ios_menu_action_failed
import moe.rukamori.archivetune.shared.ui.resources.ios_ok
import moe.rukamori.archivetune.shared.ui.resources.ios_settings_feature_unavailable
import moe.rukamori.archivetune.shared.ui.resources.m6_share
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The one row menu of the iOS host. It mounts the shared song menu with the owners this host actually has: queue,
 * library like and add-to-playlist, clipboard share, history removal and playlist-row removal. Rows whose owner the
 * iOS host does not connect (album and artist pages, sleep timer, downloads, custom artwork) are filtered off the
 * menu rather than appearing as dead items.
 */
internal class IosSongMenuState {
    var target: ContentOccurrence? by mutableStateOf(null)
        private set

    /** The editable local playlist the row sits in, so its remove row commits against that playlist. */
    var playlistId: String? by mutableStateOf(null)
        private set

    /** The notice a failed or unconnected row leaves for the user, or null. */
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
    }

    fun dismissMessage() {
        message = null
    }

    fun failed() {
        message = if (refusedAsUnavailable) {
            "ios_settings_feature_unavailable"
        } else {
            "ios_menu_action_failed"
        }
        refusedAsUnavailable = false
    }

    internal fun unavailable(): Nothing {
        refusedAsUnavailable = true
        throw UnsupportedOperationException("not connected on the iOS host")
    }
}

/**
 * Queue commands get their own occurrence of the same item, because a row's list id can already sit in the queue and a
 * repeated insert of it would be refused. All other commands delegate straight through.
 */
private class IosMenuInsertQueue(private val queue: QueueController) : QueueController by queue {
    override suspend fun insert(entries: List<ContentOccurrence>, kind: UserQueuedKind) =
        queue.insert(entries.map { it.queuedEntry() }, kind)
}

private class IosSongMenuHost(
    private val state: IosSongMenuState,
    private val graph: IosSessionGraph,
) : SongMenuHost {
    override suspend fun startRadio(song: ContentRef) = state.unavailable()
    override suspend fun setInLibrary(song: ContentRef, inLibrary: Boolean) {
        // The library store commits saved items; removing a library row has no mutation on this host.
        if (!inLibrary) state.unavailable()
        val item = state.target?.item ?: state.unavailable()
        graph.library.saveItems(listOf(item))
    }

    override suspend fun setSpeedDialPinned(song: ContentRef, pinned: Boolean) = state.unavailable()

    override suspend fun removeFromHistory(song: ContentRef) {
        val id = state.target?.occurrenceId?.removePrefix("history:") ?: state.unavailable()
        graph.pageOwners.history.remove(HistoryOrigin.LOCAL, setOf(id))
    }

    override suspend fun removeFromCache(song: ContentRef) = state.unavailable()
    override suspend fun startDownload(song: ContentRef) = state.unavailable()
    override suspend fun removeDownload(song: ContentRef) = state.unavailable()
    override suspend fun exportDownload(song: ContentRef, suggestedName: String) = state.unavailable()
    override suspend fun openWithExternalDownloader(song: ContentRef): Boolean = false
    override suspend fun viewAlbum(albumId: String) = state.unavailable()
    override fun openSleepTimer() = state.unavailable()
    override suspend fun setRecommendationBlocked(song: ContentRef, blocked: Boolean) = state.unavailable()
    override suspend fun saveCoverArtwork(song: ContentRef, artworkUrl: String): Boolean = false
    override suspend fun refetchSong(song: ContentRef) = state.unavailable()
    override fun openTrackInfo(song: ContentRef) = state.unavailable()
}

/**
 * The iOS row menu mounted from the host bindings' overlays. Leaving a page closes its menu, as on the desktop host.
 */
@Composable
internal fun IosSongMenuOverlay(state: IosSongMenuState, graph: IosSessionGraph, routeKey: String) {
    DisposableEffect(routeKey) { onDispose { state.dismiss() } }
    val occurrence = state.target ?: run {
        state.message?.let { IosMenuNotice(it, state::dismissMessage) }
        return
    }
    if (occurrence.item.ref.kind == ContentKind.SONG || occurrence.item.ref.kind == ContentKind.EPISODE) {
        IosSongMenu(state, graph, occurrence)
    } else {
        IosCollectionRowMenu(state, graph, occurrence)
    }
    state.message?.let { IosMenuNotice(it, state::dismissMessage) }
}

@Composable
private fun IosSongMenu(state: IosSongMenuState, graph: IosSessionGraph, occurrence: ContentOccurrence) {
    val sharing = remember { MenuShareHandler(iosMenuSharing(state)) }
    val host = remember { IosSongMenuHost(state, graph) }
    val editablePlaylists by remember {
        graph.libraryContent.playlistRows().map { rows -> rows.filter { it.editable }.map { it.item } }
    }.collectAsState(emptyList())
    val ref = occurrence.item.ref
    val liked by produceState(false, ref) {
        try {
            graph.library.observeLikedSongs(
                LibrarySortState(AutoPlaylistSongSortType.CREATE_DATE, false),
                iosTextOrder,
                graph.pageOwners.blocked,
            ).collect { page -> value = page?.items.orEmpty().any { it.item.ref == ref } }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            value = false
        }
    }
    val inLibrary by produceState(false, ref) {
        value = try {
            graph.library.findSong(ref.id, ref.provider) != null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }
    val isTelegram = ref.id.startsWith("telegram://")
    val isLocal = ref.provider == ContentProvider.LOCAL.id
    val facts = SongMenuFacts(
        song = ref,
        title = occurrence.item.title,
        isLocal = isLocal,
        isTelegram = isTelegram,
        inLibrary = inLibrary,
        speedDialPinned = false,
        download = CollectionDownloadState.NOT_DOWNLOADED,
        downloadExtension = null,
        fromHistory = occurrence.occurrenceId.startsWith("history:"),
        fromCache = false,
        albumId = occurrence.item.albumId,
        artworkUrl = occurrence.item.artworkUri,
        recommendationBlocked = false,
        externalDownloaderEnabled = false,
        externalDownloaderConfigured = false,
    )
    val actions = buildList {
        addAll(
            songOverflowActions(
                selected = occurrence,
                queue = IosMenuInsertQueue(graph.queue),
                library = graph.library,
                sharing = sharing,
                liked = liked,
                isLocal = isLocal,
                mimeType = null,
                viewArtists = state::unavailable,
            ),
        )
        addAll(songLibraryAndDownloadActions(facts, host))
        state.playlistId?.let { playlistId -> add(playlistOccurrenceRemoval(playlistId, occurrence, graph.library)) }
    }.filter { it.id in iosBoundSongRows(occurrence, facts, inLibrary) }
    SongOverflowMenu(
        identity = "ios-song:${occurrence.occurrenceId}",
        expanded = true,
        selected = occurrence,
        actions = actions,
        library = graph.library,
        editablePlaylists = editablePlaylists,
        onDismiss = state::dismiss,
        onFailure = state::failed,
    )
}

/** Album, artist and playlist rows get only the share link this host can actually build. */
@Composable
private fun IosCollectionRowMenu(state: IosSongMenuState, graph: IosSessionGraph, occurrence: ContentOccurrence) {
    val sharing = remember { MenuShareHandler(iosMenuSharing(state)) }
    val ref = occurrence.item.ref
    val actions = buildList<OverflowAction> {
        when (ref.kind) {
            ContentKind.ARTIST -> if (ref.id.length > 2 && ref.id.startsWith("UC") && ref.id.none(Char::isWhitespace)) {
                add(OverflowAction("share", Res.string.m6_share) {
                    check(sharing.artist(ref.id)) { "Share target unavailable" }
                })
            }
            ContentKind.PLAYLIST -> if (ref.provider == ContentProvider.YOUTUBE.id) {
                add(OverflowAction("share", Res.string.m6_share) {
                    check(sharing.playlist(ref.id, null)) { "Share target unavailable" }
                })
            }
            else -> { }
        }
    }
    if (actions.isEmpty()) {
        state.dismiss()
        state.failed()
        return
    }
    SharedOverflowMenu(
        menuIdentity = "ios-item:${occurrence.occurrenceId}",
        expanded = true,
        onDismiss = state::dismiss,
        actions = actions,
        onFailure = state::failed,
    )
}

/** The rows the iOS host connects end to end, per the opened occurrence. */
private fun iosBoundSongRows(occurrence: ContentOccurrence, facts: SongMenuFacts, inLibrary: Boolean): Set<String> =
    buildSet {
        addAll(listOf("play_next", "add_queue", "like", "add_playlist"))
        if (!facts.isLocal && !facts.isTelegram) add("share")
        if (!inLibrary && !facts.isLocal) add("library")
        if (facts.fromHistory) add("remove_history")
        add("remove_playlist")
    }

private fun iosMenuSharing(state: IosSongMenuState): MenuSharing = object : MenuSharing {
    override suspend fun shareLink(url: String) = copyToIosClipboard(url)
    override suspend fun shareLocalAudio(mediaId: String, mimeType: String?) = state.unavailable()
    // Link resolution needs the signed-in Telegram client's message-link query, which this host does not expose.
    override suspend fun telegramMessageLink(mediaId: String): String? = null
}

@Composable
private fun IosMenuNotice(key: String, onDismiss: () -> Unit) {
    val resource = when (key) {
        "ios_settings_feature_unavailable" -> Res.string.ios_settings_feature_unavailable
        else -> Res.string.ios_menu_action_failed
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(stringResource(resource)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.ios_ok)) } },
    )
}
