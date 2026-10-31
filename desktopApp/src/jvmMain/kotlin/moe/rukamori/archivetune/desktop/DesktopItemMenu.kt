package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.layout.Column
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
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.domain.library.AutoPlaylistSongSortType
import moe.rukamori.archivetune.shared.domain.library.LibrarySortState
import moe.rukamori.archivetune.shared.domain.queue.UserQueuedKind
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.QueueController
import moe.rukamori.archivetune.shared.domain.settings.ExternalDownloaderEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.SpeedDialPinType
import moe.rukamori.archivetune.shared.domain.settings.SpeedDialSongIdsKey
import moe.rukamori.archivetune.shared.domain.settings.parseSpeedDialPins
import moe.rukamori.archivetune.shared.ui.host.queuedEntry
import moe.rukamori.archivetune.shared.ui.menu.CollectionDownloadState
import moe.rukamori.archivetune.shared.ui.menu.MenuShareHandler
import moe.rukamori.archivetune.shared.ui.menu.OverflowAction
import moe.rukamori.archivetune.shared.ui.menu.SharedOverflowMenu
import moe.rukamori.archivetune.shared.ui.menu.SongMenuFacts
import moe.rukamori.archivetune.shared.ui.menu.SongOverflowMenu
import moe.rukamori.archivetune.shared.ui.menu.playlistOccurrenceRemoval
import moe.rukamori.archivetune.shared.ui.menu.songLibraryAndDownloadActions
import moe.rukamori.archivetune.shared.ui.menu.songOverflowActions
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoutes
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.screens.customartwork.BoundCustomArtworkEditDialog
import org.jetbrains.compose.resources.stringResource

/**
 * The one row menu of the desktop host. A row's overflow opens it on that row's occurrence. It mounts the shared song
 * menu with the owners this host actually has: queue, library like and add-to-playlist, share, history
 * removal, playlist-row removal, album navigation, the sleep timer sheet, cover-art save, track info, the download
 * owner's start, removal and export, radio, speed dial and the media-info route. A row keeps its real actions; missing
 * platform owners give a resource-backed reason rather than disappearing.
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

    var artistTarget: ContentItem? by mutableStateOf(null)
        private set

    /** The notice a failed or unconnected row leaves for the user, or null. */
    var message: String? by mutableStateOf(null)
        private set

    private var refusalKey: String? = null

    fun open(occurrence: ContentOccurrence, playlistId: String? = null) {
        target = occurrence
        this.playlistId = playlistId
        artworkTarget = null
        artistTarget = null
        refusalKey = null
        message = null
    }

    fun dismiss() {
        target = null
        playlistId = null
        artworkTarget = null
        artistTarget = null
    }

    fun openCustomArtwork(item: ContentItem) {
        target = null
        playlistId = null
        artworkTarget = item
    }

    fun closeCustomArtwork() {
        artworkTarget = null
    }

    fun openArtists(item: ContentItem) {
        if (item.artists.none { !it.id.isNullOrBlank() }) unavailable("content_menu_artist_unavailable")
        target = null
        playlistId = null
        artistTarget = item
    }

    fun closeArtists() { artistTarget = null }

    fun dismissMessage() {
        message = null
    }

    /** A failed menu row: the unavailable notice for a row this host does not connect, otherwise the failure. */
    fun failed() {
        message = DesktopStrings[refusalKey ?: "menu_action_failed"]
        refusalKey = null
    }

    internal fun unavailable(key: String): Nothing {
        refusalKey = key
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
            songId = item.ref.id.takeIf { item.ref.kind == ContentKind.SONG },
            repository = graph.customArtwork,
            pick = ::pickDesktopArtworkFile,
            onDismiss = menu::closeCustomArtwork,
            preview = { modifier -> DesktopRemoteArtwork(item.artworkUri, graph.artworkLoader, modifier) },
        )
    }
    menu.artistTarget?.let { item ->
        DesktopArtistPicker(item, menu::closeArtists) { ref ->
            menu.dismiss()
            openRoute(ArchiveRoutes.content(ref))
        }
    }
    val occurrence = menu.target ?: run {
        menu.message?.let { DesktopMenuNotice(it, menu::dismissMessage) }
        return
    }
    if (occurrence.item.ref.kind == ContentKind.SONG || occurrence.item.ref.kind == ContentKind.EPISODE) {
        DesktopSongMenu(menu, graph, occurrence, sleepSheet, openRoute)
    } else {
        DesktopCollectionRowMenu(menu, graph, occurrence, openRoute)
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
    val sharing = remember(menu) { MenuShareHandler(desktopMenuSharing(menu)) }
    val host = remember(menu, graph, occurrence, sleepSheet, openRoute) {
        DesktopSongMenuHost(menu, graph, occurrence, sleepSheet, openRoute)
    }
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
    val preferences by graph.settings.snapshots.collectAsState()
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
                value = desktopDownloadMenuState(transfers, ref)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            value = CollectionDownloadState.NOT_DOWNLOADED
        }
    }
    val downloadExtension by produceState<String?>(null, graph, ref, downloadState) {
        value = null
        if (downloadState == CollectionDownloadState.COMPLETED) {
            value = try {
                graph.downloads.completedFile(ref)?.let { desktopDownloadExtension(it.path) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
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
        speedDialPinned = ref.supportsDesktopSongPin() &&
            parseSpeedDialPins(preferences?.get(SpeedDialSongIdsKey).orEmpty())
                .any { it.type == SpeedDialPinType.SONG && it.id == ref.id },
        download = downloadState,
        downloadExtension = downloadExtension,
        fromHistory = occurrence.occurrenceId.startsWith("history:"),
        fromCache = occurrence.occurrenceId.startsWith("cache:"),
        albumId = occurrence.item.albumId.takeUnless { ref.kind == ContentKind.EPISODE },
        artworkUrl = occurrence.item.artworkUri,
        recommendationBlocked = false,
        externalDownloaderEnabled = preferences?.get(ExternalDownloaderEnabledKey) == true,
        // Android package configuration cannot launch a desktop app; the row explains this when selected.
        externalDownloaderConfigured = true,
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
                viewArtists = { menu.openArtists(occurrence.item) },
            ).map { action ->
                when (action.id) {
                    "share" -> OverflowAction(
                        action.id, if (isLocal) Res.string.content_reveal_audio else action.label,
                    ) { shareDesktopItem(occurrence.item, graph, menu) }
                    "view_artist" -> OverflowAction(
                        action.id, if (ref.kind == ContentKind.EPISODE) Res.string.content_view_podcast else action.label,
                        dismissOnSuccess = false,
                    ) {
                        if (ref.kind == ContentKind.EPISODE) {
                            val show = occurrence.item.albumId?.takeIf(String::isNotBlank)
                                ?: menu.unavailable("content_menu_artist_unavailable")
                            menu.dismiss()
                            openRoute(ArchiveRoutes.content(ContentRef(ref.provider, ContentKind.PODCAST, show)))
                        } else menu.openArtists(occurrence.item)
                    }
                    else -> action
                }
            },
        )
        addAll(songLibraryAndDownloadActions(facts, host))
        // The shared menu has no media-info row, so the desktop host adds the Details row that opens the mounted route.
        add(OverflowAction("media_info", m5pqDetailsRes) { menu.dismiss(); openRoute(ArchiveRoutes.mediaInfo(ref.id)) })
        menu.playlistId?.let { playlistId -> add(playlistOccurrenceRemoval(playlistId, occurrence, graph.library)) }
        add(OverflowAction("custom_artwork", Res.string.m4_custom_artwork_title, dismissOnSuccess = false) {
            menu.openCustomArtwork(occurrence.item)
        })
        add(OverflowAction("save_canvas", Res.string.m6_save_canvas) {
            menu.unavailable("content_menu_canvas_export_unsupported")
        })
    }
    SongOverflowMenu(
        identity = "desktop-song:${occurrence.occurrenceId}",
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

@Composable
private fun DesktopCollectionRowMenu(
    menu: DesktopItemMenuState,
    graph: DesktopSessionGraph,
    occurrence: ContentOccurrence,
    openRoute: (String) -> Unit,
) {
    val ref = occurrence.item.ref
    val actions = listOf(
        OverflowAction("open_item", m5pqDetailsRes) { openRoute(ArchiveRoutes.content(ref)) },
        OverflowAction("share", Res.string.m6_share) { shareDesktopItem(occurrence.item, graph, menu) },
    )
    SharedOverflowMenu(
        menuIdentity = "desktop-item:${occurrence.occurrenceId}",
        expanded = true,
        onDismiss = menu::dismiss,
        actions = actions,
        onFailure = menu::failed,
    )
}

@Composable
private fun DesktopArtistPicker(item: ContentItem, onDismiss: () -> Unit, open: (ContentRef) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.m6_view_artist)) },
        text = {
            Column {
                item.artists.filter { !it.id.isNullOrBlank() }.distinctBy { it.id }.take(64).forEach { artist ->
                    TextButton(onClick = { open(ContentRef(item.ref.provider, ContentKind.ARTIST, requireNotNull(artist.id))) }) {
                        Text(artist.name)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(DesktopStrings["close"]) } },
    )
}

@Composable
private fun DesktopMenuNotice(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(DesktopStrings["ok"]) } },
    )
}
