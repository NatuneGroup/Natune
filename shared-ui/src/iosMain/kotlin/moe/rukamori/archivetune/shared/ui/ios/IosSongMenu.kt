package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.data.download.downloadTaskId
import moe.rukamori.archivetune.shared.domain.library.AutoPlaylistSongSortType
import moe.rukamori.archivetune.shared.domain.library.LibrarySortState
import moe.rukamori.archivetune.shared.domain.queue.UserQueuedKind
import moe.rukamori.archivetune.shared.domain.repository.ContentDownload
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.HistoryOrigin
import moe.rukamori.archivetune.shared.domain.repository.QueueController
import moe.rukamori.archivetune.shared.ui.IosHost
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
import moe.rukamori.archivetune.shared.ui.menu.songLibraryAndDownloadActions
import moe.rukamori.archivetune.shared.ui.menu.songOverflowActions
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoutes
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.ios_download_refused
import moe.rukamori.archivetune.shared.ui.resources.ios_menu_action_failed
import moe.rukamori.archivetune.shared.ui.resources.ios_ok
import moe.rukamori.archivetune.shared.ui.resources.ios_settings_feature_unavailable
import moe.rukamori.archivetune.shared.ui.resources.m5pqDetailsRes
import org.jetbrains.compose.resources.stringResource

/**
 * Queue commands get their own occurrence of the same item, because a row's list id can already sit in the queue and a
 * repeated insert of it would be refused. All other commands delegate straight through.
 */
private class IosMenuInsertQueue(private val queue: QueueController) : QueueController by queue {
    override suspend fun insert(entries: List<ContentOccurrence>, kind: UserQueuedKind) =
        queue.insert(entries.map { it.queuedEntry() }, kind)
}

/** The host the shared song menu runs against on iOS. Rows without a real owner are filtered off the menu. */
private class IosSongMenuHost(
    private val state: IosItemMenuState,
    private val graph: IosSessionGraph,
    private val openRoute: (String) -> Unit,
) : SongMenuHost {
    /**
     * The song loads alone and the watch-next tail is appended behind it, the pair of calls `PlayerConnection.startRadio`
     * makes on Android. A queue that could not seed the tail is a failure, not a silent solo play.
     */
    override suspend fun startRadio(song: ContentRef) {
        if (song.provider != ContentProvider.YOUTUBE.id || song.kind != ContentKind.SONG) state.unavailable()
        val entry = state.target?.queuedEntry() ?: state.unavailable()
        graph.queue.replace(listOf(entry), entry.occurrenceId, playWhenReady = true)
        check(graph.queue.startRadioSeamlessly()) { "The radio tail could not be seeded" }
    }

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

    /**
     * A download resolves under the user's download source order and is stored in the app container. A provider this
     * host cannot store is refused before any task exists, so the notice names the limit instead of a bare failure.
     */
    override suspend fun startDownload(song: ContentRef) {
        val item = state.target?.item
        try {
            if (item != null && item.ref == song) graph.downloads.start(item) else graph.downloads.start(song, "")
        } catch (refused: DownloadRefused) {
            state.refuseDownload()
        }
    }

    override suspend fun removeDownload(song: ContentRef) {
        graph.downloads.remove(downloadTaskId(song))
    }

    override suspend fun exportDownload(song: ContentRef, suggestedName: String) = state.unavailable()
    override suspend fun openWithExternalDownloader(song: ContentRef): Boolean = false

    override suspend fun viewAlbum(albumId: String) {
        val item = state.target?.item ?: state.unavailable()
        state.dismiss()
        openRoute(ArchiveRoutes.content(ContentRef(item.ref.provider, ContentKind.ALBUM, albumId)))
    }

    override fun openSleepTimer() = state.unavailable()
    override suspend fun setRecommendationBlocked(song: ContentRef, blocked: Boolean) = state.unavailable()
    override suspend fun saveCoverArtwork(song: ContentRef, artworkUrl: String): Boolean = false
    override suspend fun refetchSong(song: ContentRef) = state.unavailable()

    override fun openTrackInfo(song: ContentRef) {
        state.dismiss()
        openRoute(ArchiveRoutes.trackInfo(song.id))
    }
}

/**
 * The shared song menu over the owners this host connects: queue, like and add-to-playlist, the system share sheet,
 * library add, downloads, history and playlist-row removal, album and artist pages, YouTube radio, track and media
 * info and the custom artwork editor. Rows whose owner is not connected here (cache, speed dial, sleep timer,
 * refetch, recommendation blocks, cover save, exporting a download to another app) are filtered off rather than
 * appearing as dead items.
 */
@Composable
internal fun IosSongMenu(
    menu: IosItemMenuState,
    graph: IosSessionGraph,
    host: IosHost,
    occurrence: ContentOccurrence,
    openRoute: (String) -> Unit,
) {
    val sharing = remember { MenuShareHandler(iosMenuSharing(menu, host)) }
    val songHost = remember { IosSongMenuHost(menu, graph, openRoute) }
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
    val artistRef = occurrence.item.artists.firstNotNullOfOrNull { it.ref(ref.provider) }
    val transfers by produceState(emptyList<ContentDownload>(), ref) {
        try {
            graph.downloads.observe().collect { value = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            value = emptyList()
        }
    }
    val facts = SongMenuFacts(
        song = ref,
        title = occurrence.item.title,
        isLocal = isLocal,
        isTelegram = isTelegram,
        inLibrary = inLibrary,
        speedDialPinned = false,
        download = graph.downloads.downloadStateOf(transfers, ref),
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
                viewArtists = {
                    val artist = artistRef ?: menu.unavailable()
                    menu.dismiss()
                    openRoute(ArchiveRoutes.content(artist))
                },
            ),
        )
        addAll(songLibraryAndDownloadActions(facts, songHost))
        // The shared menu has no media-info row, so this host adds the Details row that opens the mounted route.
        add(OverflowAction("media_info", m5pqDetailsRes) { menu.dismiss(); openRoute(ArchiveRoutes.mediaInfo(ref.id)) })
        menu.playlistId?.let { playlistId -> add(playlistOccurrenceRemoval(playlistId, occurrence, graph.library)) }
        add(customArtworkEntryAction { menu.openCustomArtwork() })
    }.filter { it.id in iosBoundSongRows(ref, facts, inLibrary, artistRef != null) }
    SongOverflowMenu(
        identity = "ios-song:${occurrence.occurrenceId}",
        expanded = true,
        selected = occurrence,
        actions = actions,
        library = graph.library,
        editablePlaylists = editablePlaylists,
        onDismiss = menu::dismiss,
        onFailure = menu::failed,
        songMetadata = graph.library.songMetadata,
    )
}

/** Album, artist and playlist rows get only the share link this host can actually build. */
@Composable
internal fun IosCollectionRowMenu(menu: IosItemMenuState, host: IosHost, occurrence: ContentOccurrence) {
    val sharing = remember { MenuShareHandler(iosMenuSharing(menu, host)) }
    val ref = occurrence.item.ref
    val actions = buildList<OverflowAction> {
        when (ref.kind) {
            ContentKind.ARTIST -> if (ref.id.length > 2 && ref.id.startsWith("UC") && ref.id.none(Char::isWhitespace)) {
                add(collectionShareAction { check(sharing.artist(ref.id)) { "Share target unavailable" } })
            }
            ContentKind.PLAYLIST -> if (ref.provider == ContentProvider.YOUTUBE.id) {
                add(collectionShareAction { check(sharing.playlist(ref.id, null)) { "Share target unavailable" } })
            }
            else -> { }
        }
    }
    if (actions.isEmpty()) {
        LaunchedEffect(occurrence) { menu.refuse() }
        return
    }
    SharedOverflowMenu(
        menuIdentity = "ios-item:${occurrence.occurrenceId}",
        expanded = true,
        onDismiss = menu::dismiss,
        actions = actions,
        onFailure = menu::failed,
    )
}

/** The rows this host connects end to end, per the opened occurrence. */
internal fun iosBoundSongRows(ref: ContentRef, facts: SongMenuFacts, inLibrary: Boolean, hasArtist: Boolean): Set<String> =
    buildSet {
        addAll(listOf("play_next", "add_queue", "like", "add_playlist", "custom_artwork", "track_info", "media_info"))
        // Link share covers catalog songs; Telegram ids and local files have no link or file-share owner here.
        if (!facts.isLocal && !facts.isTelegram) add("share")
        // Library add commits through saveItems; removal has no mutation on this host.
        if (!inLibrary && !facts.isLocal) add("library")
        if (facts.fromHistory) add("remove_history")
        if (facts.albumId != null) add("view_album")
        if (hasArtist) add("view_artist")
        // A provider that can only stream encrypted audio has no stored file here, so it offers no download row.
        if (iosDownloadRefusal(ref) == null) {
            when (facts.download) {
                CollectionDownloadState.NOT_DOWNLOADED -> add("download")
                CollectionDownloadState.COMPLETED -> add("remove_download")
                CollectionDownloadState.IN_PROGRESS -> add("downloading")
            }
        }
        // Watch-next radio is seeded only by a YouTube song.
        if (ref.provider == ContentProvider.YOUTUBE.id && ref.kind == ContentKind.SONG && !facts.isTelegram) {
            add("start_radio")
        }
        add("remove_playlist")
    }

private fun iosMenuSharing(state: IosItemMenuState, host: IosHost): MenuSharing = object : MenuSharing {
    override suspend fun shareLink(url: String) = withContext(Dispatchers.Main) { iosShareText(host, url) }
    override suspend fun shareLocalAudio(mediaId: String, mimeType: String?) = state.unavailable()
    // Link resolution needs the signed-in Telegram client's message-link query, which this host does not expose.
    override suspend fun telegramMessageLink(mediaId: String): String? = null
}

@Composable
internal fun IosMenuNotice(key: String, onDismiss: () -> Unit) {
    val resource = when (key) {
        IosItemMenuState.NOTICE_UNAVAILABLE -> Res.string.ios_settings_feature_unavailable
        IosItemMenuState.NOTICE_DOWNLOAD_REFUSED -> Res.string.ios_download_refused
        else -> Res.string.ios_menu_action_failed
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(stringResource(resource)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.ios_ok)) } },
    )
}
