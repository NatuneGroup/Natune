package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.shared.domain.customartwork.localPlaylistCoverUri
import moe.rukamori.archivetune.shared.domain.repository.QueueCollectionKind
import moe.rukamori.archivetune.shared.domain.repository.QueueLoadRequest
import moe.rukamori.archivetune.shared.domain.repository.radioQueueRequest
import moe.rukamori.archivetune.shared.domain.playlistexport.PlaylistExportDestination
import moe.rukamori.archivetune.shared.domain.playlistexport.PlaylistExportNaming
import moe.rukamori.archivetune.shared.domain.playlisttags.PlaylistTag
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistCompleteList
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistCompletion
import moe.rukamori.archivetune.shared.domain.queue.IncompleteFinitePlaylistException
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistSong
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentQuery
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface
import moe.rukamori.archivetune.shared.domain.repository.LibraryMutation
import moe.rukamori.archivetune.shared.domain.settings.SpeedDialPin
import moe.rukamori.archivetune.shared.domain.settings.SpeedDialPinType
import moe.rukamori.archivetune.shared.domain.settings.SpeedDialSongIdsKey
import moe.rukamori.archivetune.shared.domain.settings.TelegramLosslessOnlyKey
import moe.rukamori.archivetune.shared.domain.settings.parseSpeedDialPins
import moe.rukamori.archivetune.shared.domain.settings.serializeSpeedDialPins
import moe.rukamori.archivetune.shared.domain.settings.toggleSpeedDialPin
import moe.rukamori.archivetune.shared.domain.telegram.TelegramTrackRef
import moe.rukamori.archivetune.shared.ui.host.queuedEntry
import moe.rukamori.archivetune.shared.ui.menu.CollectionDownloadState
import moe.rukamori.archivetune.shared.ui.menu.MenuShareHandler
import moe.rukamori.archivetune.shared.ui.menu.MenuSharing
import moe.rukamori.archivetune.shared.ui.menu.PlaylistMenuFacts
import moe.rukamori.archivetune.shared.ui.menu.PlaylistMenuHost
import moe.rukamori.archivetune.shared.ui.menu.PlaylistOverflowMenu
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.screens.PlaylistArtwork
import moe.rukamori.archivetune.shared.ui.screens.customartwork.BoundCustomArtworkEditDialog
import moe.rukamori.archivetune.shared.ui.screens.AssignPlaylistTagsActions
import moe.rukamori.archivetune.shared.ui.screens.AssignPlaylistTagsDialog
import moe.rukamori.archivetune.shared.ui.screens.AssignPlaylistTagsUi
import moe.rukamori.archivetune.shared.ui.screens.PlaylistChoiceUi
import moe.rukamori.archivetune.shared.ui.screens.PlaylistCollectionRow
import moe.rukamori.archivetune.shared.ui.screens.PlaylistTagEditorUi
import moe.rukamori.archivetune.shared.ui.screens.PlaylistTagUi
import moe.rukamori.archivetune.shared.ui.screens.PlaylistTagsActions
import moe.rukamori.archivetune.shared.ui.screens.PlaylistTagsUi

/** The tag route of the shared graph. */
internal const val DesktopTagsRoute = "library/tags"

/**
 * The playlist menus of the desktop host: the shared playlist overflow menu over the local playlist, the tag assignment
 * dialog over the portable tag store, and the CSV export to a file the user chooses. Speed-dial pins are owned by the
 * ordinary settings store, and playlist radio seeds a watch-next collection off the provider page's own radio
 * endpoint. Rows without a desktop owner (the download group, per-playlist YouTube sync and hiding) are hidden by
 * the shared menu's capability facts; the remaining unconnected host members answer with the unavailable notice
 * instead of a silent no-op.
 */
internal class DesktopPlaylistMenuState(
    private val graph: DesktopSessionGraph,
    private val openRoute: (String) -> Unit,
) {
    /** The playlist the overflow menu is open for, with its complete song list once it is read. */
    data class MenuTarget(
        val item: ContentItem,
        val complete: FinitePlaylistCompleteList<ContentOccurrence>?,
        val previewItems: List<ContentItem> = emptyList(),
        val hidden: Boolean = false,
        val browseId: String? = null,
    )

    /** The tag dialog: one playlist, or the bulk picker when [playlist] is null. */
    data class TagTarget(val playlist: ContentRef?)

    /** The playlist whose custom cover editor is open, with its mosaic items for the preview. */
    data class CoverTarget(val item: ContentItem, val previewItems: List<ContentItem>)

    var menu: MenuTarget? by mutableStateOf(null)
        private set
    var tags: TagTarget? by mutableStateOf(null)
        private set
    var cover: CoverTarget? by mutableStateOf(null)
        private set
    var message: String? by mutableStateOf(null)
        private set

    private var refusedAsUnavailable = false
    private val telegramChannels = TdlightChannelLibrary(graph, graph.telegram)

    val host: PlaylistMenuHost = object : PlaylistMenuHost {
        /**
         * Android's playlist-menu radio row: the provider page's own `radioEndpoint` seeds a watch-next
         * collection (`playQueue(YouTubeQueue(endpoint))`). A local playlist has no endpoint and reports
         * unavailable; a provider page without one is reported, not silently ignored.
         */
        override suspend fun startPlaylistRadio(playlist: ContentRef) {
            // A synced local playlist radios its linked YouTube record; an unlinked one has no remote seed.
            val resolved = if (playlist.provider == ContentProvider.LOCAL.id) {
                val browseId = try {
                    graph.library.observeLocalPlaylists().first()
                        .firstOrNull { it.occurrence.item.ref.id == playlist.id }?.browseId
                } catch (_: Exception) {
                    null
                }
                browseId?.let { ContentRef(ContentProvider.YOUTUBE.id, ContentKind.PLAYLIST, it) }
                    ?: return unavailable()
            } else playlist
            if (resolved.provider != ContentProvider.YOUTUBE.id || resolved.kind != ContentKind.PLAYLIST) {
                unavailable()
                return
            }
            val endpoint = withContext(Dispatchers.IO) {
                YouTube.playlist(resolved.id).getOrThrow().playlist.radioEndpoint
            }
            val radioPlaylistId = endpoint?.playlistId
            val radioVideoId = endpoint?.videoId
            val seed = when {
                radioPlaylistId != null ->
                    ContentRef(ContentProvider.YOUTUBE.id, ContentKind.PLAYLIST, radioPlaylistId)
                radioVideoId != null ->
                    ContentRef(ContentProvider.YOUTUBE.id, ContentKind.SONG, radioVideoId)
                else -> {
                    graph.report("playlist radio: the provider page has no radio endpoint")
                    return
                }
            }
            graph.queue.load(
                QueueLoadRequest(
                    collection = radioQueueRequest(seed, endpoint.playlistSetVideoId, endpoint.params),
                    kind = QueueCollectionKind.RADIO,
                ),
            )
        }
        override suspend fun setSpeedDialPinned(playlist: ContentRef, pinned: Boolean) {
            require(playlist.provider == ContentProvider.LOCAL.id) { "Only local playlists pin to the speed dial" }
            graph.settings.edit {
                val pins = parseSpeedDialPins(get(SpeedDialSongIdsKey, ""))
                this[SpeedDialSongIdsKey] =
                    serializeSpeedDialPins(toggleSpeedDialPin(pins, SpeedDialPin(SpeedDialPinType.PLAYLIST, playlist.id)))
            }
        }
        override suspend fun startMissingDownloads(songs: List<ContentOccurrence>) {
            val transfers = graph.downloads.observe().first()
            for (occurrence in songs) {
                val ref = occurrence.item.ref
                if (!desktopDownloadSupported(ref)) continue
                if (desktopDownloadMenuState(transfers, ref) == CollectionDownloadState.NOT_DOWNLOADED) {
                    graph.downloads.start(occurrence.item)
                }
            }
        }
        override suspend fun removeDownloads(songs: List<ContentOccurrence>) {
            for (occurrence in songs) {
                val ref = occurrence.item.ref
                if (desktopDownloadSupported(ref) ||
                    desktopDownloadMenuState(graph.downloads.observe().first(), ref) != CollectionDownloadState.NOT_DOWNLOADED
                ) {
                    graph.downloads.remove(ref.desktopDownloadId())
                }
            }
        }
        override fun openPlaylistTags(playlist: ContentRef) {
            menu = null
            tags = TagTarget(playlist)
        }
        /**
         * The cover row opens the shared artwork editor over the playlist's pseudo-URL, so the stored file replaces
         * the mosaic on every surface the same artwork identity resolves.
         */
        override fun changePlaylistCover(playlist: ContentRef) {
            val target = menu?.takeIf { it.item.ref == playlist } ?: return unavailable()
            menu = null
            cover = CoverTarget(target.item, target.previewItems)
        }
        override suspend fun removePlaylistCover(playlist: ContentRef) {
            require(playlist.provider == ContentProvider.LOCAL.id) { "Only local playlist covers are removed here" }
            graph.customArtwork.removeCover(localPlaylistCoverUri(playlist.id))
        }
        override suspend fun syncToYouTube(playlist: ContentRef) {
            require(playlist.provider == ContentProvider.LOCAL.id && playlist.kind == ContentKind.PLAYLIST) {
                "Only a local playlist syncs to YouTube"
            }
            when (graph.youtubeLibrarySync.playlistSyncGate()) {
                DesktopPlaylistSyncGate.NOT_SIGNED_IN -> {
                    message = DesktopStrings["playlist_sync_not_logged_in"]
                    return
                }
                DesktopPlaylistSyncGate.SYNC_DISABLED -> {
                    message = DesktopStrings["playlist_sync_disabled"]
                    return
                }
                DesktopPlaylistSyncGate.READY -> Unit
            }
            val title = menu?.takeIf { it.item.ref == playlist }?.item?.title ?: playlist.id
            // The push reads the stored rows, not the menu's page: a filtered or partial snapshot pushed to the
            // remote and then pulled back would drop the missing songs locally.
            val videoIds = playlistVideoIds(playlist.id) ?: run {
                message = DesktopStrings["playlist_action_failed"]
                return
            }
            graph.youtubeLibrarySync.syncPlaylist(playlist.id, title, videoIds)
            message = DesktopStrings["playlist_synced"]
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
            menu = null
            openRoute("telegram/bots")
        }
        override suspend fun refreshTelegramPlaylist(playlist: ContentRef) {
            val chatId = playlist.id.removePrefix("LPtg").toLongOrNull() ?: return
            val title = menu?.takeIf { it.item.ref == playlist }?.item?.title ?: graph.library.item(playlist).title
            val losslessOnly = graph.settings.snapshots.value?.get(TelegramLosslessOnlyKey) ?: false
            telegramChannels.startSync(chatId, title, losslessOnly)
        }
    }

    /** A local playlist has no share link, so the export row stands in for share; a link is copied when one exists. */
    val sharing = MenuShareHandler(object : MenuSharing {
        override suspend fun shareLink(url: String) = copyToDesktopClipboard(url)
        override suspend fun copyLink(url: String) = copyToDesktopClipboard(url)
        override suspend fun shareLocalAudio(mediaId: String, mimeType: String?) { unavailable() }
        override suspend fun telegramMessageLink(mediaId: String): String? {
            val track = TelegramTrackRef.fromMediaId(mediaId) ?: return null
            val link = graph.telegram.admittedRequest(
                "telegramMessageLink",
                TdApi.GetMessageLink(track.chatId, track.messageId, 0, 0, null, false, false),
            ) as? TdApi.MessageLink
            return link?.link?.takeIf(String::isNotBlank)
        }
    })

    /** Opens the menu at once, then fills in the complete song list, so the song rows enable once the list is read. */
    fun open(ref: ContentRef) {
        graph.scope.launch {
            try {
                val row = graph.libraryContent.playlistRows().first().firstOrNull { it.item.ref == ref } ?: return@launch
                menu = MenuTarget(row.item, null, row.previewItems, hidden = row.hidden, browseId = row.browseId)
                val page = graph.libraryContent.currentList(
                    ContentRequest(ContentProvider.LOCAL, ContentSurface.LIBRARY_PLAYLIST_ITEMS, parent = ref),
                )
                val complete = page?.takeIf { it.continuation == null }?.let { list ->
                    FinitePlaylistCompleteList(
                        title = row.item.title,
                        songs = list.items.mapIndexed { index, occurrence ->
                            FinitePlaylistSong(occurrence.queuedEntry(), occurrence.item.ref.id, occurrence.setVideoId, index)
                        },
                    )
                }
                if (menu?.item?.ref == ref) menu = MenuTarget(row.item, complete, row.previewItems, hidden = row.hidden, browseId = row.browseId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("playlist menu not opened: ${failure.javaClass.simpleName}")
            }
        }
    }

    fun dismissMenu() {
        menu = null
    }

    fun dismissCover() {
        cover = null
    }

    fun openTags(ref: ContentRef) {
        tags = TagTarget(ref)
    }

    fun openBulkTags() {
        tags = TagTarget(null)
    }

    fun dismissTags() {
        tags = null
    }

    fun manageTags() {
        tags = null
        openRoute(DesktopTagsRoute)
    }

    fun dismissMessage() {
        message = null
    }

    /** The playlist-list drag in custom order commits through the library mutation, as the baseline did. */
    fun movePlaylist(ref: ContentRef, neighbour: ContentRef, after: Boolean) {
        if (ref.provider != ContentProvider.LOCAL.id || neighbour.provider != ContentProvider.LOCAL.id) {
            refuseMove()
            return
        }
        graph.scope.launch {
            try {
                graph.library.mutate(LibraryMutation.MovePlaylist(ref.id, neighbour.id, after))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("playlist move was not saved: ${failure.javaClass.simpleName}")
                message = DesktopStrings["playlist_action_failed"]
            }
        }
    }

    /**
     * The stored YouTube video ids of the local playlist, read unfiltered and in stored order under the strict
     * whole-list rule. Null refuses the push when the complete list cannot be read.
     */
    private suspend fun playlistVideoIds(playlistId: String): List<String>? {
        val query = ContentQuery(
            RoomLibraryRepository.PLAYLIST_ITEMS,
            parent = ContentRef(ContentProvider.LOCAL.id, ContentKind.PLAYLIST, playlistId),
        )
        val ids = mutableListOf<String>()
        val consumed = mutableSetOf<String>()
        var continuation: String? = null
        var requests = 0
        try {
            do {
                if (++requests > FinitePlaylistCompletion.MAX_REQUESTS) return null
                val token = continuation
                if (token != null && !consumed.add(token)) return null
                val page = graph.library.page(query, token)
                FinitePlaylistCompletion.validateNext(
                    page.continuation, consumed, page.items.size, initial = requests == 1)
                page.items.mapNotNullTo(ids) { occurrence ->
                    occurrence.item.ref.takeIf {
                        it.provider == ContentProvider.YOUTUBE.id && it.kind == ContentKind.SONG
                    }?.id
                }
                continuation = page.continuation
            } while (continuation != null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        return ids
    }

    /** A row whose drag target is not a local playlist is refused with the notice. */
    fun refuseMove() {
        message = DesktopStrings["settings_feature_unavailable"]
    }

    /** A failed menu row: the unavailable notice for a row this host does not connect, otherwise the failure. */
    fun failed() {
        message = if (refusedAsUnavailable) DesktopStrings["settings_feature_unavailable"] else DesktopStrings["playlist_action_failed"]
        refusedAsUnavailable = false
    }

    /** Asks for a file, then writes the baseline CSV there. A cancelled chooser writes nothing and reports nothing. */
    suspend fun exportCsv(ref: ContentRef, name: String) {
        val file = chooseSaveFile(PlaylistExportNaming.csvFileName(name)) ?: return
        graph.playlistExport.exportCsv(ref, object : PlaylistExportDestination {
            override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) { file.writeBytes(bytes) }
        })
        message = DesktopStrings["playlist_exported"]
    }

    /** The collection row's export button, which has no menu around it to report a failure. */
    fun exportFromRow(ref: ContentRef, name: String) {
        graph.scope.launch {
            try {
                exportCsv(ref, name)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("playlist export failed: ${failure.javaClass.simpleName}")
                message = DesktopStrings["playlist_action_failed"]
            }
        }
    }

    private fun unavailable(): Nothing {
        refusedAsUnavailable = true
        throw UnsupportedOperationException("not connected on the desktop host")
    }
}

/** The local playlists with their tag ids, so the Playlists page can filter by tag. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun DesktopSessionGraph.taggedPlaylistRows(): Flow<List<PlaylistCollectionRow>> =
    libraryContent.playlistRows().flatMapLatest { rows ->
        if (rows.isEmpty()) {
            flowOf(rows)
        } else {
            combine(
                rows.map { row ->
                    playlistTags.observePlaylistTags(row.item.ref.id).map { assigned ->
                        row.copy(tagIds = assigned.mapTo(HashSet()) { it.id }, exportable = true)
                    }
                },
            ) { it.toList() }
        }
    }

/** The menus over the page they opened on. Leaving the page closes them, as the row menu does. */
@Composable
internal fun DesktopPlaylistMenuOverlay(state: DesktopPlaylistMenuState, graph: DesktopSessionGraph, routeKey: String) {
    DisposableEffect(routeKey) {
        onDispose {
            state.dismissMenu()
            state.dismissTags()
        }
    }
    val settingsSnapshot by graph.settings.snapshots.collectAsState()
    val artworkOverrides by graph.customArtwork.overrides.collectAsState()
    state.cover?.let { target ->
        BoundCustomArtworkEditDialog(
            artworkUrl = target.item.artworkUri,
            songId = null,
            repository = graph.customArtwork,
            pick = ::pickDesktopArtworkFile,
            onDismiss = state::dismissCover,
            preview = { modifier ->
                PlaylistArtwork(
                    target.item,
                    target.previewItems,
                    { cell, cellModifier -> DesktopRemoteArtwork(cell.artworkUri, graph.artworkLoader, cellModifier) },
                    modifier,
                )
            },
        )
    }
    state.menu?.let { target ->
        val ref = target.item.ref
        val pinned = settingsSnapshot?.let { snapshot ->
            parseSpeedDialPins(snapshot[SpeedDialSongIdsKey, ""])
                .any { it.type == SpeedDialPinType.PLAYLIST && it.id == ref.id }
        } ?: false
        // The shared menu is a dropdown, so it is anchored at the middle of the window.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box {
                val download by produceState(CollectionDownloadState.NOT_DOWNLOADED, ref, target.complete) {
                    try {
                        graph.downloads.observe().collect { transfers ->
                            value = target.complete?.songs
                                ?.map { desktopDownloadMenuState(transfers, it.item.item.ref) }
                                ?.let { states -> desktopPlaylistDownloadState(states) }
                                ?: CollectionDownloadState.NOT_DOWNLOADED
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        value = CollectionDownloadState.NOT_DOWNLOADED
                    }
                }
                PlaylistOverflowMenu(
                    identity = "desktop-playlist:${ref.provider}:${ref.id}",
                    expanded = true,
                    facts = PlaylistMenuFacts(
                        playlist = ref,
                        name = target.item.title,
                        complete = target.complete,
                        download = download,
                        downloadsSupported = true,
                        youtubeSync = ref.provider == ContentProvider.LOCAL.id && ref.kind == ContentKind.PLAYLIST,
                        hiding = ref.provider == ContentProvider.LOCAL.id && ref.kind == ContentKind.PLAYLIST,
                        speedDialPinned = pinned,
                        hidden = target.hidden,
                        browseId = target.browseId,
                        shareLink = null,
                        editable = true,
                        automatic = false,
                        downloadPlaylist = false,
                        telegramChannel = ref.provider == ContentProvider.LOCAL.id && ref.id.startsWith("LPtg"),
                        canChangeCover = ref.provider == ContentProvider.LOCAL.id && ref.kind == ContentKind.PLAYLIST,
                        canRemoveCover = artworkOverrides.hasCover(target.item.artworkUri),
                    ),
                    queue = graph.queue,
                    library = graph.library,
                    sharing = state.sharing,
                    host = state.host,
                    exportLocal = { state.exportCsv(ref, target.item.title) },
                    onDismiss = state::dismissMenu,
                    onFailure = state::failed,
                )
            }
        }
    }
    state.tags?.let { target -> DesktopAssignTagsDialog(state, graph, target) }
    state.message?.let { text ->
        AlertDialog(
            onDismissRequest = state::dismissMessage,
            text = { Text(text) },
            confirmButton = { TextButton(onClick = state::dismissMessage) { Text(DesktopStrings["ok"]) } },
        )
    }
}

@Composable
private fun DesktopAssignTagsDialog(
    state: DesktopPlaylistMenuState,
    graph: DesktopSessionGraph,
    target: DesktopPlaylistMenuState.TagTarget,
) {
    val catalog by remember { graph.playlistTags.observeTags() }.collectAsState(null)
    val playlists by remember(target) {
        if (target.playlist == null) graph.playlistTags.observeEditablePlaylists() else flowOf(emptyList())
    }.collectAsState(emptyList())
    var selectedTags by remember(target) { mutableStateOf<Set<String>>(emptySet()) }
    var selectedPlaylists by remember(target) { mutableStateOf<Set<ContentRef>>(emptySet()) }
    var loaded by remember(target) { mutableStateOf(target.playlist == null) }
    var busy by remember(target) { mutableStateOf(false) }
    var failed by remember(target) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(target) {
        val playlist = target.playlist ?: return@LaunchedEffect
        try {
            selectedTags = graph.playlistTags.observePlaylistTags(playlist.id).first().mapTo(HashSet()) { it.id }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            graph.report("playlist tags not read: ${failure.javaClass.simpleName}")
            failed = true
        }
        loaded = true
    }
    AssignPlaylistTagsDialog(
        state = AssignPlaylistTagsUi(
            tags = catalog.orEmpty().map { it.toUi() },
            selectedTagIds = selectedTags,
            playlists = if (target.playlist == null) {
                playlists.map { PlaylistChoiceUi(localPlaylistRef(it.id), it.name, it.songCount) }
            } else {
                null
            },
            selectedPlaylists = selectedPlaylists,
            loading = catalog == null || !loaded,
            busy = busy,
            failed = failed,
        ),
        actions = AssignPlaylistTagsActions(
            tag = { id -> selectedTags = if (id in selectedTags) selectedTags - id else selectedTags + id },
            playlist = { ref -> selectedPlaylists = if (ref in selectedPlaylists) selectedPlaylists - ref else selectedPlaylists + ref },
            save = { tagIds, refs ->
                busy = true
                failed = false
                scope.launch {
                    try {
                        val playlist = target.playlist
                        if (playlist != null) {
                            graph.playlistTags.replacePlaylistTags(playlist.id, tagIds.toList())
                        } else {
                            graph.playlistTags.addTagsToPlaylists(refs.map { it.id }, tagIds.toList())
                        }
                        state.dismissTags()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        graph.report("playlist tags not saved: ${failure.javaClass.simpleName}")
                        failed = true
                    } finally {
                        busy = false
                    }
                }
            },
            manage = state::manageTags,
            dismiss = state::dismissTags,
        ),
    )
}

/** The Manage tags page: the tag catalog of the portable file, with the baseline tag palette. */
@Composable
internal fun desktopTagsPage(graph: DesktopSessionGraph, menus: DesktopPlaylistMenuState): ArchiveGraphPage {
    var attempt by remember { mutableStateOf(0) }
    val tags by remember(attempt) { graph.playlistTags.observeTags() }.collectAsState(null)
    var editor by remember { mutableStateOf<PlaylistTagEditorUi?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun write(change: suspend () -> Unit, done: () -> Unit = {}) {
        if (busy) return
        busy = true
        failed = false
        scope.launch {
            try {
                change()
                done()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("tag change not saved: ${failure.javaClass.simpleName}")
                failed = true
            } finally {
                busy = false
            }
        }
    }
    val state = PlaylistTagsUi(
        tags = tags.orEmpty().map { it.toUi() },
        editor = editor,
        loading = tags == null,
        busy = busy,
        failed = failed,
    )
    val actions = PlaylistTagsActions(
        back = {},
        retry = { attempt += 1 },
        create = { editor = PlaylistTagEditorUi(null, "", TagPalette.first(), TagPalette, canSave = true) },
        edit = { id ->
            tags?.firstOrNull { it.id == id }?.let { tag ->
                editor = PlaylistTagEditorUi(tag.id, tag.name, tagColor(tag.color), TagPalette, canSave = true)
            }
        },
        name = { value -> editor = editor?.copy(name = value) },
        color = { value -> editor = editor?.copy(color = value) },
        save = {
            editor?.let { current ->
                val name = current.name.trim()
                val color = tagHex(current.color)
                write({
                    val id = current.id
                    if (id == null) graph.playlistTags.createTag(name, color) else graph.playlistTags.updateTag(id, name, color)
                }) { editor = null }
            }
        },
        cancelEdit = { editor = null },
        delete = { id -> write({ graph.playlistTags.deleteTag(id) }) },
        bulkAssign = menus::openBulkTags,
    )
    return ArchiveGraphPage.Tags(state, actions)
}

private fun localPlaylistRef(id: String): ContentRef =
    ContentRef(ContentProvider.LOCAL.id, ContentKind.PLAYLIST, id)

private fun PlaylistTag.toUi(): PlaylistTagUi = PlaylistTagUi(id, name, tagColor(color))

internal fun desktopTagColor(hex: String): Color = tagColor(hex)

/** A stored `#RRGGBB` colour. An unreadable value shows the default tag colour rather than failing the list. */
private fun tagColor(hex: String): Color {
    val digits = hex.removePrefix("#")
    val rgb = digits.takeIf { it.length == 6 }?.toLongOrNull(16) ?: DEFAULT_TAG_RGB
    return Color(0xFF000000L or rgb)
}

private fun tagHex(color: Color): String = "#%06X".format(color.toArgb() and 0xFFFFFF)

private const val DEFAULT_TAG_RGB = 0xFF6B6BL

/** The baseline tag palette, in the order the Android tag editor offers it. */
private val TagPalette: List<Color> = listOf(
    "#FF6B6B", "#4ECDC4", "#45B7D1", "#FFA07A", "#98D8C8", "#F7DC6F", "#BB8FCE", "#85C1E2",
    "#F8B739", "#52B788", "#E63946", "#457B9D", "#F4A261", "#2A9D8F", "#E76F51", "#264653",
    "#E9C46A", "#F4A6C1", "#8E44AD", "#16A085", "#C0392B", "#D35400", "#7D3C98", "#1ABC9C", "#3498DB",
).map(::tagColor)

/** A save chooser on the event queue, as the artwork picker opens its chooser. Null when the user cancels. */
private suspend fun chooseSaveFile(suggestedName: String): File? = suspendCancellableCoroutine { continuation ->
    EventQueue.invokeLater {
        val chosen = try {
            val dialog = FileDialog(null as Frame?, DesktopStrings["playlist_export_pick"], FileDialog.SAVE)
            dialog.file = suggestedName
            // A modal file dialog blocks this event-queue task until it closes, while AWT keeps pumping events.
            dialog.isVisible = true
            val name = dialog.file
            val folder = dialog.directory
            dialog.dispose()
            if (name == null || folder == null) null else File(folder, name)
        } catch (failure: Exception) {
            desktopDiagnostic("file chooser unavailable: ${failure.javaClass.simpleName}")
            null
        }
        if (continuation.isActive) continuation.resume(chosen)
    }
}


/** The playlist download row: every song done reads COMPLETED, any live transfer reads IN_PROGRESS. */
internal fun desktopPlaylistDownloadState(states: List<CollectionDownloadState>): CollectionDownloadState =
    when {
        states.isEmpty() -> CollectionDownloadState.NOT_DOWNLOADED
        states.all { it == CollectionDownloadState.COMPLETED } -> CollectionDownloadState.COMPLETED
        states.any { it == CollectionDownloadState.IN_PROGRESS } -> CollectionDownloadState.IN_PROGRESS
        else -> CollectionDownloadState.NOT_DOWNLOADED
    }
