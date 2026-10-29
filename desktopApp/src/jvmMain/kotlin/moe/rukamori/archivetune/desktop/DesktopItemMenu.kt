package moe.rukamori.archivetune.desktop

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
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.domain.library.AutoPlaylistSongSortType
import moe.rukamori.archivetune.shared.domain.library.LibrarySortState
import moe.rukamori.archivetune.shared.domain.queue.UserQueuedKind
import moe.rukamori.archivetune.shared.domain.repository.ContentDownload
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.DownloadPhase
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
import moe.rukamori.archivetune.shared.ui.menu.collectionShareAction
import moe.rukamori.archivetune.shared.ui.menu.customArtworkEntryAction
import moe.rukamori.archivetune.shared.ui.menu.playlistOccurrenceRemoval
import moe.rukamori.archivetune.shared.ui.menu.songExportFileName
import moe.rukamori.archivetune.shared.ui.menu.songLibraryAndDownloadActions
import moe.rukamori.archivetune.shared.ui.menu.songOverflowActions
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoutes
import moe.rukamori.archivetune.shared.ui.resources.m5pqDetailsRes
import moe.rukamori.archivetune.shared.ui.screens.customartwork.BoundCustomArtworkEditDialog
import org.jetbrains.compose.resources.stringResource

/**
 * The one row menu of the desktop host. A row's overflow opens it on that row's occurrence. It mounts the shared song
 * menu with the owners this host actually has: queue, library like and add-to-playlist, clipboard share, history
 * removal, playlist-row removal, album navigation, the sleep timer sheet, cover-art save, track info, the download
 * owner's start and removal, and the media-info route. Rows whose owner the desktop host does not connect (radio,
 * speed dial, cache removal, export, artist pages, refetch) are filtered off the menu rather than appearing as dead
 * items.
 */
internal class DesktopItemMenuState {
    /** The row the menu is open for, or null while it is closed. */
    var target: ContentOccurrence? by mutableStateOf(null)
        private set

    /** The editable local playlist the row sits in, so its "remove from playlist" row commits against that playlist. */
    var playlistId: String? by mutableStateOf(null)
        private set

    /** The song whose custom artwork editor is open, or null. It replaces the menu rather than stacking on it. */
    var artworkTarget: ContentItem? by mutableStateOf(null)
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

    fun dismissMessage() {
        message = null
    }

    /** A failed menu row: the unavailable notice for a row this host does not connect, otherwise the failure. */
    fun failed() {
        message = if (refusedAsUnavailable) {
            DesktopStrings["settings_feature_unavailable"]
        } else {
            DesktopStrings["menu_action_failed"]
        }
        refusedAsUnavailable = false
    }

    internal fun unavailable(): Nothing {
        refusedAsUnavailable = true
        throw UnsupportedOperationException("not connected on the desktop host")
    }
}

/**
 * Queue commands get their own occurrence of the same item, because a row's list id can already sit in the queue and a
 * repeated insert of it would be refused. All other commands delegate straight through.
 */
private class MenuInsertQueue(private val queue: QueueController) : QueueController by queue {
    override suspend fun insert(entries: List<ContentOccurrence>, kind: UserQueuedKind) =
        queue.insert(entries.map { it.queuedEntry() }, kind)
}

/** The host the shared song menu runs against on the desktop. Rows without a real owner are never offered. */
private class DesktopSongMenuHost(
    private val state: DesktopItemMenuState,
    private val graph: DesktopSessionGraph,
    private val sleepSheet: DesktopSleepTimerSheetState,
    private val openRoute: (String) -> Unit,
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
    /** Queues the song on the one downloads owner, the same store the downloads page and media info read. */
    override suspend fun startDownload(song: ContentRef) = graph.downloads.start(song, "")
    override suspend fun removeDownload(song: ContentRef) = graph.downloads.remove(song.songDownloadId())
    override suspend fun exportDownload(song: ContentRef, suggestedName: String) = state.unavailable()
    override suspend fun openWithExternalDownloader(song: ContentRef): Boolean = false

    override suspend fun viewAlbum(albumId: String) {
        val item = state.target?.item ?: state.unavailable()
        openRoute(ArchiveRoutes.content(ContentRef(item.ref.provider, ContentKind.ALBUM, albumId)))
    }

    override fun openSleepTimer() {
        sleepSheet.open = true
    }

    override suspend fun setRecommendationBlocked(song: ContentRef, blocked: Boolean) = state.unavailable()

    override suspend fun saveCoverArtwork(song: ContentRef, artworkUrl: String): Boolean {
        val item = state.target?.item ?: return false
        val file = chooseDesktopSaveFile(DesktopStrings["menu_cover_pick"], artworkFileName(item)) ?: return true
        val bytes = try {
            graph.http.get(artworkUrl).bodyAsBytes()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        }
        return withContext(Dispatchers.IO) {
            try { file.writeBytes(bytes); true } catch (_: Exception) { false }
        }
    }

    override suspend fun refetchSong(song: ContentRef) = state.unavailable()
    override fun openTrackInfo(song: ContentRef) {
        state.dismiss()
        openRoute(ArchiveRoutes.trackInfo(song.id))
    }
}

/**
 * The row menu as an anchored dropdown over the page it opened on. The navigation host does not key the route body,
 * so the effect is keyed on the route itself: leaving a page closes its menu, and a menu opened on one page is never
 * shown again on another page.
 */
@Composable
internal fun DesktopItemMenuDialog(
    menu: DesktopItemMenuState,
    graph: DesktopSessionGraph,
    routeKey: String,
    sleepSheet: DesktopSleepTimerSheetState,
    openRoute: (String) -> Unit,
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
    val occurrence = menu.target ?: run {
        menu.message?.let { DesktopMenuNotice(it, menu::dismissMessage) }
        return
    }
    if (occurrence.item.ref.kind == ContentKind.SONG || occurrence.item.ref.kind == ContentKind.EPISODE) {
        DesktopSongMenu(menu, graph, occurrence, sleepSheet, openRoute)
    } else {
        DesktopCollectionRowMenu(menu, graph, occurrence)
    }
    menu.message?.let { DesktopMenuNotice(it, menu::dismissMessage) }
}

@Composable
private fun DesktopSongMenu(
    menu: DesktopItemMenuState,
    graph: DesktopSessionGraph,
    occurrence: ContentOccurrence,
    sleepSheet: DesktopSleepTimerSheetState,
    openRoute: (String) -> Unit,
) {
    val sharing = remember { MenuShareHandler(desktopMenuSharing(menu)) }
    val host = remember { DesktopSongMenuHost(menu, graph, sleepSheet, openRoute) }
    val editablePlaylists by remember {
        graph.libraryContent.playlistRows().map { rows -> rows.filter { it.editable }.map { it.item } }
    }.collectAsState(emptyList())
    val ref = occurrence.item.ref
    val liked by produceState(false, ref) {
        try {
            graph.library.observeLikedSongs(
                LibrarySortState(AutoPlaylistSongSortType.CREATE_DATE, false),
                graph.textOrder,
                graph.artistBlocks.observeBlockedArtists(),
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
    // The download row follows the owner's live transfers and stored rows, so it updates as a download starts and ends.
    val downloadState by produceState(CollectionDownloadState.NOT_DOWNLOADED, ref) {
        try {
            graph.downloads.observe().collect { transfers ->
                value = downloadStateOf(transfers, ref.songDownloadId())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            value = CollectionDownloadState.NOT_DOWNLOADED
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
        download = downloadState,
        // Export stays off the menu: exportDownload is not connected on this host, so no extension is offered.
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
                queue = MenuInsertQueue(graph.queue),
                library = graph.library,
                sharing = sharing,
                liked = liked == true,
                isLocal = isLocal,
                mimeType = null,
                viewArtists = menu::unavailable,
            ),
        )
        addAll(songLibraryAndDownloadActions(facts, host))
        // The shared menu has no media-info row, so the desktop host adds the Details row that opens the mounted route.
        add(OverflowAction("media_info", m5pqDetailsRes) { menu.dismiss(); openRoute(ArchiveRoutes.mediaInfo(ref.id)) })
        menu.playlistId?.let { playlistId -> add(playlistOccurrenceRemoval(playlistId, occurrence, graph.library)) }
        add(customArtworkEntryAction { menu.openCustomArtwork() })
    }.filter { it.id in desktopBoundSongRows(occurrence, facts, inLibrary) }
    SongOverflowMenu(
        identity = "desktop-song:${occurrence.occurrenceId}",
        expanded = true,
        selected = occurrence,
        actions = actions,
        library = graph.library,
        editablePlaylists = editablePlaylists,
        onDismiss = menu::dismiss,
        onFailure = menu::failed,
    )
}

/** Album, artist and playlist rows get only the share link this host can actually build. */
@Composable
private fun DesktopCollectionRowMenu(
    menu: DesktopItemMenuState,
    graph: DesktopSessionGraph,
    occurrence: ContentOccurrence,
) {
    val sharing = remember { MenuShareHandler(desktopMenuSharing(menu)) }
    val ref = occurrence.item.ref
    val actions = buildList<OverflowAction> {
        when (ref.kind) {
            ContentKind.ARTIST -> if (ref.id.length > 2 && ref.id.startsWith("UC") && ref.id.none(Char::isWhitespace)) {
                add(collectionShareAction {
                    check(sharing.artist(ref.id)) { "Share target unavailable" }
                })
            }
            ContentKind.PLAYLIST -> if (ref.provider == ContentProvider.YOUTUBE.id) {
                add(collectionShareAction {
                    check(sharing.playlist(ref.id, null)) { "Share target unavailable" }
                })
            }
            else -> { }
        }
    }
    if (actions.isEmpty()) {
        menu.dismiss()
        menu.failed()
        return
    }
    SharedOverflowMenu(
        menuIdentity = "desktop-item:${occurrence.occurrenceId}",
        expanded = true,
        onDismiss = menu::dismiss,
        actions = actions,
        onFailure = menu::failed,
    )
}

/** The rows the desktop host connects end to end, per the opened occurrence. */
private fun desktopBoundSongRows(occurrence: ContentOccurrence, facts: SongMenuFacts, inLibrary: Boolean): Set<String> =
    buildSet {
        addAll(listOf("play_next", "add_queue", "like", "add_playlist", "custom_artwork", "sleep_timer", "download_cover"))
        // Clipboard share covers catalog songs; Telegram ids and local files have no link or file-share owner here.
        if (!facts.isLocal && !facts.isTelegram) add("share")
        // Library add commits through saveItems; removal has no mutation on this host.
        if (!inLibrary && !facts.isLocal) add("library")
        if (facts.fromHistory) add("remove_history")
        if (facts.albumId != null) add("view_album")
        // The track-info route is mounted by the shared navigation host on this surface.
        add("track_info")
        // Start, cancel and removal commit through the downloads owner; Details opens the mounted media-info route.
        addAll(listOf("download", "downloading", "remove_download", "media_info"))
        add("remove_playlist")
    }

/** The id the downloads owner keys a song by, `provider:mediaId`, the form it lists, starts and removes it under. */
private fun ContentRef.songDownloadId(): String = "$provider:$id"

/** The row state the shared menu shows for the download with [id], read from the owner's transfers and stored rows. */
private fun downloadStateOf(transfers: List<ContentDownload>, id: String): CollectionDownloadState =
    when (transfers.firstOrNull { it.id == id }?.phase) {
        DownloadPhase.COMPLETED -> CollectionDownloadState.COMPLETED
        // A removal in progress keeps the row busy, so a new download cannot start over the removal.
        DownloadPhase.QUEUED, DownloadPhase.DOWNLOADING, DownloadPhase.REMOVING -> CollectionDownloadState.IN_PROGRESS
        DownloadPhase.STOPPED, DownloadPhase.FAILED, null -> CollectionDownloadState.NOT_DOWNLOADED
    }

private fun desktopMenuSharing(state: DesktopItemMenuState): MenuSharing = object : MenuSharing {
    override suspend fun shareLink(url: String) = copyToDesktopClipboard(url)
    override suspend fun shareLocalAudio(mediaId: String, mimeType: String?) = state.unavailable()
    // Link resolution needs the signed-in Telegram client's message-link query, which this host does not expose.
    override suspend fun telegramMessageLink(mediaId: String): String? = null
}

private fun artworkFileName(item: ContentItem): String {
    val extension = item.artworkUri
        ?.substringBefore('?')
        ?.substringAfterLast('.', "")
        ?.takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) }
        ?: "jpg"
    return songExportFileName(item.title, extension)
}

@Composable
private fun DesktopMenuNotice(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(DesktopStrings["ok"]) } },
    )
}
