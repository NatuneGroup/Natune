package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import moe.rukamori.archivetune.shared.data.download.downloadTaskId
import moe.rukamori.archivetune.shared.domain.customartwork.localPlaylistCoverUri
import moe.rukamori.archivetune.shared.domain.playlistexport.PlaylistExportDestination
import moe.rukamori.archivetune.shared.domain.playlistexport.PlaylistExportNaming
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistCompleteList
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistSong
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface
import moe.rukamori.archivetune.shared.domain.repository.QueueCollectionKind
import moe.rukamori.archivetune.shared.domain.repository.QueueLoadRequest
import moe.rukamori.archivetune.shared.domain.repository.radioQueueRequest
import moe.rukamori.archivetune.shared.domain.settings.SpeedDialPin
import moe.rukamori.archivetune.shared.domain.settings.SpeedDialPinType
import moe.rukamori.archivetune.shared.domain.settings.SpeedDialSongIdsKey
import moe.rukamori.archivetune.shared.domain.settings.TelegramLosslessOnlyKey
import moe.rukamori.archivetune.shared.domain.settings.YtmSyncKey
import moe.rukamori.archivetune.shared.domain.settings.parseSpeedDialPins
import moe.rukamori.archivetune.shared.domain.settings.serializeSpeedDialPins
import moe.rukamori.archivetune.shared.domain.settings.toggleSpeedDialPin
import moe.rukamori.archivetune.shared.ui.IosHost
import moe.rukamori.archivetune.shared.ui.menu.CollectionDownloadState
import moe.rukamori.archivetune.shared.ui.menu.MenuShareHandler
import moe.rukamori.archivetune.shared.ui.menu.PlaylistMenuFacts
import moe.rukamori.archivetune.shared.ui.menu.PlaylistMenuHost
import moe.rukamori.archivetune.shared.ui.menu.PlaylistOverflowMenu

/**
 * The host the shared playlist overflow menu runs against on iOS. Speed-dial pins, tags, covers, downloads, hiding,
 * Telegram channel refresh, playlist radio and deletion run against the session graph's real owners.
 */
internal class IosPlaylistMenuHost(
    private val graph: IosSessionGraph,
    private val host: IosHost,
    private val menu: IosItemMenuState,
    private val tagState: IosPlaylistTagsState,
    private val openRoute: (String) -> Unit,
) : PlaylistMenuHost {
    override suspend fun startPlaylistRadio(playlist: ContentRef) {
        val seedSong = if (playlist.provider == ContentProvider.LOCAL.id) {
            val page = graph.libraryContent.currentList(
                ContentRequest(ContentProvider.LOCAL, ContentSurface.LIBRARY_PLAYLIST_ITEMS, parent = playlist),
            )
            page?.items?.firstOrNull {
                it.item.ref.provider == ContentProvider.YOUTUBE.id && it.item.ref.kind == ContentKind.SONG
            }
        } else {
            null
        }
        val browseId = if (seedSong == null && playlist.provider == ContentProvider.LOCAL.id) {
            runCatching {
                graph.library.observeLocalPlaylists().first()
                    .firstOrNull { it.occurrence.item.ref.id == playlist.id }?.browseId
            }.getOrNull()
        } else {
            playlist.id.takeIf { playlist.provider == ContentProvider.YOUTUBE.id && playlist.kind == ContentKind.PLAYLIST }
        }
        val seedRef = seedSong?.item?.ref
            ?: browseId?.let { ContentRef(ContentProvider.YOUTUBE.id, ContentKind.PLAYLIST, it) }
            ?: return menu.unavailable()
        graph.queue.load(
            QueueLoadRequest(
                collection = radioQueueRequest(seedRef, seedSong?.setVideoId, null),
                kind = QueueCollectionKind.RADIO,
            ),
        )
    }

    override suspend fun setSpeedDialPinned(playlist: ContentRef, pinned: Boolean) {
        require(playlist.provider == ContentProvider.LOCAL.id) { "Only local playlists pin to the speed dial" }
        graph.settings.edit {
            val pins = parseSpeedDialPins(get(SpeedDialSongIdsKey, ""))
            this[SpeedDialSongIdsKey] = serializeSpeedDialPins(
                toggleSpeedDialPin(pins, SpeedDialPin(SpeedDialPinType.PLAYLIST, playlist.id)),
            )
        }
    }

    override suspend fun startMissingDownloads(songs: List<ContentOccurrence>) {
        val transfers = graph.downloads.observe().first()
        for (occurrence in songs) {
            val ref = occurrence.item.ref
            if (iosDownloadRefusal(ref) != null) continue
            if (graph.downloads.downloadStateOf(transfers, ref) == CollectionDownloadState.NOT_DOWNLOADED) {
                graph.downloads.start(occurrence.item)
            }
        }
    }

    override suspend fun removeDownloads(songs: List<ContentOccurrence>) {
        for (occurrence in songs) {
            graph.downloads.remove(downloadTaskId(occurrence.item.ref))
        }
    }

    override fun openPlaylistTags(playlist: ContentRef) {
        menu.dismiss()
        tagState.openTags(playlist)
    }

    /**
     * The cover row opens the shared artwork editor over the playlist's pseudo-URL, so the stored file replaces the
     * mosaic on every surface the same artwork identity resolves.
     */
    override fun changePlaylistCover(playlist: ContentRef) {
        val item = menu.target?.takeIf { it.item.ref == playlist }?.item ?: return menu.unavailable()
        menu.openCustomArtwork(item)
    }

    override suspend fun removePlaylistCover(playlist: ContentRef) {
        require(playlist.provider == ContentProvider.LOCAL.id) { "Only local playlist covers are removed here" }
        graph.customArtwork.removeCover(localPlaylistCoverUri(playlist.id))
    }

    override suspend fun syncToYouTube(playlist: ContentRef) {
        require(playlist.provider == ContentProvider.LOCAL.id) { "Only local playlists sync to YouTube" }
        val page = graph.libraryContent.currentList(
            ContentRequest(ContentProvider.LOCAL, ContentSurface.LIBRARY_PLAYLIST_ITEMS, parent = playlist),
        )
        val videoIds = page?.items.orEmpty()
            .filter { it.item.ref.provider == ContentProvider.YOUTUBE.id && it.item.ref.kind == ContentKind.SONG }
            .map { it.item.ref.id }
            .distinct()
        val title = menu.target?.takeIf { it.item.ref == playlist }?.item?.title ?: playlist.id
        IosYouTubeLibrarySync(graph).syncPlaylist(playlist.id, title, videoIds)
    }

    override suspend fun setHidden(playlist: ContentRef, hidden: Boolean) {
        require(playlist.provider == ContentProvider.LOCAL.id) { "Only a local playlist can be hidden" }
        graph.library.setPlaylistHidden(playlist.id, hidden)
    }

    override suspend fun deletePlaylist(playlist: ContentRef) {
        require(playlist.provider == ContentProvider.LOCAL.id) { "Only local playlists are deleted here" }
        graph.library.deletePlaylist(playlist.id)
    }

    override fun openTelegramBots() {
        menu.dismiss()
        openRoute("telegram/bots")
    }

    override suspend fun refreshTelegramPlaylist(playlist: ContentRef) {
        val chatId = playlist.id.removePrefix("LPtg").toLongOrNull() ?: return menu.unavailable()
        val client = host.telegram.value ?: return menu.unavailable()
        val title = menu.target?.takeIf { it.item.ref == playlist }?.item?.title ?: playlist.id
        val losslessOnly = graph.settings.snapshots.value?.get(TelegramLosslessOnlyKey) ?: false
        iosTelegramChannelLibrary(graph, client).startSync(chatId, title, losslessOnly)
    }
}

/** Exports the playlist as a CSV file through the iOS system share sheet. */
internal suspend fun iosExportPlaylistCsv(
    host: IosHost,
    graph: IosSessionGraph,
    ref: ContentRef,
    name: String,
) {
    var csvBytes = ByteArray(0)
    graph.playlistExport.exportCsv(
        ref,
        object : PlaylistExportDestination {
            override suspend fun write(bytes: ByteArray) {
                csvBytes = bytes
            }
        },
    )
    if (iosExportDataFile(host, PlaylistExportNaming.csvFileName(name), csvBytes) == false) {
        throw IllegalStateException("CSV export write failed")
    }
}

/**
 * The local playlist's overflow menu: the shared playlist menu over the playlist's complete song list. A Telegram
 * channel playlist gets the channel rows; every other unsupported row is refused through the facts rather than a
 * failure at run time.
 */
@Composable
internal fun IosPlaylistRowMenu(
    menu: IosItemMenuState,
    graph: IosSessionGraph,
    host: IosHost,
    occurrence: ContentOccurrence,
    playlistMenus: IosPlaylistMenuHost,
) {
    val ref = occurrence.item.ref
    val sharing = remember { MenuShareHandler(iosMenuSharing(menu, host)) }
    // The complete song list enables the play and queue rows once it is read, as the desktop menu does.
    val complete by produceState<FinitePlaylistCompleteList<ContentOccurrence>?>(null, ref) {
        value = try {
            graph.libraryContent.currentList(
                ContentRequest(ContentProvider.LOCAL, ContentSurface.LIBRARY_PLAYLIST_ITEMS, parent = ref),
            )?.takeIf { it.continuation == null }?.let { page ->
                FinitePlaylistCompleteList(
                    title = occurrence.item.title,
                    songs = page.items.mapIndexed { index, entry ->
                        FinitePlaylistSong(entry.queuedEntry(), entry.item.ref.id, entry.setVideoId, index)
                    },
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
    val downloads by remember { graph.downloads.observe() }.collectAsState(emptyList())
    val hiddenPlaylists by remember { graph.library.observeHiddenPlaylists() }.collectAsState(emptyList())
    val localPlaylists by remember { graph.library.observeLocalPlaylists() }.collectAsState(emptyList())
    val downloadState = remember(complete, downloads) {
        val songs = complete?.songs?.map { it.occurrence.item.ref }?.filter { iosDownloadRefusal(it) == null }
        when {
            songs.isNullOrEmpty() -> CollectionDownloadState.NOT_DOWNLOADED
            songs.all { graph.downloads.downloadStateOf(downloads, it) == CollectionDownloadState.COMPLETED } ->
                CollectionDownloadState.COMPLETED
            songs.any { graph.downloads.downloadStateOf(downloads, it) == CollectionDownloadState.IN_PROGRESS } ->
                CollectionDownloadState.IN_PROGRESS
            else -> CollectionDownloadState.NOT_DOWNLOADED
        }
    }
    val isHidden = hiddenPlaylists.any { it.occurrence.item.ref.id == ref.id }
    val browseId = localPlaylists.firstOrNull { it.occurrence.item.ref.id == ref.id }?.browseId
    val artworkOverrides by graph.customArtwork.overrides.collectAsState()
    val snapshot by graph.settings.snapshots.collectAsState()
    val pinned = snapshot?.let { current ->
        parseSpeedDialPins(current[SpeedDialSongIdsKey, ""])
            .any { it.type == SpeedDialPinType.PLAYLIST && it.id == ref.id }
    } ?: false
    PlaylistOverflowMenu(
        identity = "ios-playlist:${occurrence.occurrenceId}",
        expanded = true,
        facts = PlaylistMenuFacts(
            playlist = ref,
            name = occurrence.item.title,
            complete = complete,
            download = downloadState,
            speedDialPinned = pinned,
            hidden = isHidden,
            browseId = browseId,
            shareLink = browseId?.let { "https://music.youtube.com/playlist?list=$it" },
            editable = true,
            automatic = false,
            downloadPlaylist = false,
            downloadsSupported = true,
            youtubeSync = snapshot?.youTubeSignedIn() == true &&
                (snapshot?.get(YtmSyncKey) ?: true) &&
                ref.provider == ContentProvider.LOCAL.id &&
                !ref.id.startsWith("LPtg"),
            hiding = ref.provider == ContentProvider.LOCAL.id,
            telegramChannel = ref.id.startsWith("LPtg"),
            canChangeCover = true,
            canRemoveCover = artworkOverrides.hasCover(occurrence.item.artworkUri),
        ),
        queue = graph.queue,
        library = graph.library,
        sharing = sharing,
        host = playlistMenus,
        exportLocal = { iosExportPlaylistCsv(host, graph, ref, occurrence.item.title) },
        onDismiss = menu::dismiss,
        onFailure = menu::failed,
    )
}
