package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.CancellationException
import moe.rukamori.archivetune.shared.domain.customartwork.localPlaylistCoverUri
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistCompleteList
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistSong
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface
import moe.rukamori.archivetune.shared.domain.settings.SpeedDialPin
import moe.rukamori.archivetune.shared.domain.settings.SpeedDialPinType
import moe.rukamori.archivetune.shared.domain.settings.SpeedDialSongIdsKey
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
 * The host the shared playlist overflow menu runs against on iOS. Speed-dial pins, tags, covers and deletion have real
 * owners here; downloads, YouTube sync, hiding and remote radio do not, so the facts keep those rows off the menu and
 * the ones that still reach a member answer with the unavailable notice.
 */
internal class IosPlaylistMenuHost(
    private val graph: IosSessionGraph,
    private val menu: IosItemMenuState,
    private val tagState: IosPlaylistTagsState,
    private val openRoute: (String) -> Unit,
) : PlaylistMenuHost {
    override suspend fun startPlaylistRadio(playlist: ContentRef) = menu.unavailable()

    override suspend fun setSpeedDialPinned(playlist: ContentRef, pinned: Boolean) {
        require(playlist.provider == ContentProvider.LOCAL.id) { "Only local playlists pin to the speed dial" }
        graph.settings.edit {
            val pins = parseSpeedDialPins(get(SpeedDialSongIdsKey, ""))
            this[SpeedDialSongIdsKey] = serializeSpeedDialPins(
                toggleSpeedDialPin(pins, SpeedDialPin(SpeedDialPinType.PLAYLIST, playlist.id)),
            )
        }
    }

    override suspend fun startMissingDownloads(songs: List<ContentOccurrence>) = menu.unavailable()
    override suspend fun removeDownloads(songs: List<ContentOccurrence>) = menu.unavailable()

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

    override suspend fun syncToYouTube(playlist: ContentRef) = menu.unavailable()
    override suspend fun setHidden(playlist: ContentRef, hidden: Boolean) = menu.unavailable()

    override suspend fun deletePlaylist(playlist: ContentRef) {
        require(playlist.provider == ContentProvider.LOCAL.id) { "Only local playlists are deleted here" }
        graph.library.deletePlaylist(playlist.id)
    }

    override fun openTelegramBots() {
        menu.dismiss()
        openRoute("telegram/bots")
    }

    override suspend fun refreshTelegramPlaylist(playlist: ContentRef) = menu.unavailable()
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
            download = CollectionDownloadState.NOT_DOWNLOADED,
            speedDialPinned = pinned,
            hidden = false,
            browseId = null,
            shareLink = null,
            editable = true,
            automatic = false,
            downloadPlaylist = false,
            downloadsSupported = false,
            youtubeSync = false,
            hiding = false,
            telegramChannel = ref.id.startsWith("LPtg"),
            canChangeCover = true,
            canRemoveCover = artworkOverrides.hasCover(occurrence.item.artworkUri),
        ),
        queue = graph.queue,
        library = graph.library,
        sharing = sharing,
        host = playlistMenus,
        exportLocal = { menu.unavailable() },
        onDismiss = menu::dismiss,
        onFailure = menu::failed,
    )
}
