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
import moe.rukamori.archivetune.shared.data.provider.YouTubeBrowse
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
import moe.rukamori.archivetune.shared.domain.settings.SpeedDialPinType
import moe.rukamori.archivetune.shared.domain.settings.SpeedDialSongIdsKey
import moe.rukamori.archivetune.shared.domain.settings.parseSpeedDialPins
import moe.rukamori.archivetune.shared.domain.settings.serializeSpeedDialPins
import moe.rukamori.archivetune.shared.domain.telegram.TelegramTrackRef
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
import moe.rukamori.archivetune.shared.ui.menu.songExportFileName
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
    private val host: IosHost,
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
        if (inLibrary) {
            val item = state.target?.item ?: state.unavailable()
            graph.library.saveItems(listOf(item))
        } else {
            // The repository cascade the Android store performs: membership and its like go, while
            // playlist entries, downloads, listens and metadata edits keep their own tables.
            graph.library.removeItems(listOf(song))
        }
    }

    override suspend fun setSpeedDialPinned(song: ContentRef, pinned: Boolean) {
        if (!song.iosYouTubeSong()) state.unavailable()
        graph.settings.edit {
            val pins = parseSpeedDialPins(get(SpeedDialSongIdsKey).orEmpty())
            val next = iosSongPins(pins, song.id, pinned) ?: state.unavailable()
            set(SpeedDialSongIdsKey, serializeSpeedDialPins(next))
        }
    }

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

    /**
     * The stored file is staged under the menu's suggested name and the document picker exports a copy
     * to a destination the user picks — the Save-to-Files counterpart of the desktop save dialog. A
     * cancelled picker ends the row quietly, exactly like a cancelled save dialog does.
     */
    override suspend fun exportDownload(song: ContentRef, suggestedName: String) {
        val path = graph.downloads.committedFile(song) ?: state.unavailable()
        iosExportStagedFile(host, suggestedName) { staged -> iosCopyCommitted(path, staged) }
    }

    override suspend fun openWithExternalDownloader(song: ContentRef): Boolean = false

    override suspend fun viewAlbum(albumId: String) {
        val item = state.target?.item ?: state.unavailable()
        state.dismiss()
        openRoute(ArchiveRoutes.content(ContentRef(item.ref.provider, ContentKind.ALBUM, albumId)))
    }

    override fun openSleepTimer() = state.unavailable()
    override suspend fun setRecommendationBlocked(song: ContentRef, blocked: Boolean) = state.unavailable()

    /**
     * The decoded cover is written as PNG and exported through the same document picker as a download,
     * named after the song like the desktop save dialog names it. A cancelled picker is no failure —
     * the desktop row reports success when its save dialog is dismissed — only a missing or
     * unencodable image fails.
     */
    override suspend fun saveCoverArtwork(song: ContentRef, artworkUrl: String): Boolean {
        val image = graph.artworkLoader.load(artworkUrl) ?: return false
        val png = iosArtworkPng(image) ?: return false
        val name = songExportFileName(state.target?.item?.title.orEmpty(), "png")
        return iosExportStagedFile(host, name) { staged -> iosWriteBytes(staged, png) } ?: true
    }

    /**
     * The desktop refetch's counterpart: the watch-next refresh re-reads the seed song's own panel
     * row and the fresh record replaces the stored metadata. Anything that is not a provider song
     * has no such source and is refused rather than silently saving a stale copy.
     */
    override suspend fun refetchSong(song: ContentRef) {
        if (!song.iosYouTubeSong()) state.unavailable()
        val page = graph.youtubeTransport.page(YouTubeBrowse.WatchNext(videoId = song.id), null)
        val fresh = page.entries.firstOrNull { it.kind == ContentKind.SONG && it.id == song.id }
            ?: error("The provider returned no matching song")
        graph.library.saveItems(listOf(iosRefreshedItem(fresh)))
    }

    override fun openTrackInfo(song: ContentRef) {
        state.dismiss()
        openRoute(ArchiveRoutes.trackInfo(song.id))
    }
}

/**
 * The shared song menu over the owners this host connects: queue, like and add-to-playlist, the system share sheet,
 * library add and remove, speed-dial pins, downloads and their export, cover save, the watch-next metadata refetch,
 * history and playlist-row removal, album and artist pages, YouTube radio, track and media info and the custom
 * artwork editor. Rows whose owner is not connected here (cache, sleep timer, external downloader, recommendation
 * blocks) are filtered off rather than appearing as dead items.
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
    val songHost = remember { IosSongMenuHost(menu, graph, host, openRoute) }
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
    val download = graph.downloads.downloadStateOf(transfers, ref)
    // The committed file's container is sniffed like the desktop export names it, because the stored
    // name carries no format.
    val downloadExtension by produceState<String?>(null, ref, download) {
        value = null
        if (download == CollectionDownloadState.COMPLETED) {
            value = try {
                graph.downloads.committedFile(ref)?.let { path -> withContext(Dispatchers.Default) { iosCommittedExtension(path) } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }
    val preferences by graph.settings.snapshots.collectAsState()
    val facts = SongMenuFacts(
        song = ref,
        title = occurrence.item.title,
        isLocal = isLocal,
        isTelegram = isTelegram,
        inLibrary = inLibrary,
        speedDialPinned = ref.iosYouTubeSong() &&
            parseSpeedDialPins(preferences?.get(SpeedDialSongIdsKey).orEmpty())
                .any { it.type == SpeedDialPinType.SONG && it.id == ref.id },
        download = download,
        downloadExtension = downloadExtension,
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
    }.filter { it.id in iosBoundSongRows(ref, facts, artistRef != null) }
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
internal fun iosBoundSongRows(ref: ContentRef, facts: SongMenuFacts, hasArtist: Boolean): Set<String> =
    buildSet {
        addAll(listOf("play_next", "add_queue", "like", "add_playlist", "custom_artwork", "track_info", "media_info"))
        // Link share covers catalog songs and Telegram tracks alike: the client's message-link query
        // resolves a Telegram id, and only local files keep having no share owner here.
        if (!facts.isLocal) add("share")
        // Library add and remove both commit through the repository now.
        if (!facts.isLocal) add("library")
        // A speed-dial pin holds a YouTube song id; other providers keep the row's unavailable answer.
        if (!facts.isLocal) add("speed_dial")
        if (facts.fromHistory) add("remove_history")
        if (facts.albumId != null) add("view_album")
        if (hasArtist) add("view_artist")
        // A provider that can only stream encrypted audio has no stored file here, so it offers no download row.
        if (iosDownloadRefusal(ref) == null) {
            when (facts.download) {
                CollectionDownloadState.NOT_DOWNLOADED -> add("download")
                CollectionDownloadState.COMPLETED -> {
                    add("remove_download")
                    if (facts.downloadExtension != null) add("export_download")
                }
                CollectionDownloadState.IN_PROGRESS -> add("downloading")
            }
        }
        // The cover and the metadata refresh need a remote artwork or a provider song; local files have neither.
        if (!facts.isLocal) {
            add("download_cover")
            add("refetch")
        }
        // Watch-next radio is seeded only by a YouTube song.
        if (ref.provider == ContentProvider.YOUTUBE.id && ref.kind == ContentKind.SONG && !facts.isTelegram) {
            add("start_radio")
        }
        add("remove_playlist")
    }

internal fun iosMenuSharing(state: IosItemMenuState, host: IosHost): MenuSharing = object : MenuSharing {
    override suspend fun shareLink(url: String) = withContext(Dispatchers.Main) { iosShareText(host, url) }
    override suspend fun copyLink(url: String) = withContext(Dispatchers.Main) { copyToIosClipboard(url) }
    override suspend fun shareLocalAudio(mediaId: String, mimeType: String?) = state.unavailable()

    /**
     * The t.me link of the channel message a Telegram track id names, resolved by the signed-in client the
     * same way Android resolves it through TDLib's message-link query. No running client, no link.
     */
    override suspend fun telegramMessageLink(mediaId: String): String? {
        val track = TelegramTrackRef.fromMediaId(mediaId) ?: return null
        return host.telegram.value?.messageLink(track.chatId, track.messageId)
    }
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
