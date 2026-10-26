package moe.rukamori.archivetune.shared.ui.menu

import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistCompleteList
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.QueueController
import moe.rukamori.archivetune.shared.ui.resources.*

/**
 * Owner-backed playlist rows from the baseline playlist menu. The host owns radio, downloads, speed-dial pins,
 * cover files, tags, the YouTube sync, Telegram channels, persistence and navigation. A failed callback throws, so
 * the shared menu reports the failure instead of a fabricated success.
 */
interface PlaylistMenuHost {
    /** Starts the radio of a linked YouTube playlist. The host reads the radio endpoint from its own record. */
    suspend fun startPlaylistRadio(playlist: ContentRef)
    /** Pins or unpins the playlist on the speed dial. The host owns the persisted pin list. */
    suspend fun setSpeedDialPinned(playlist: ContentRef, pinned: Boolean)
    /** Queues downloads for the songs that are neither downloaded nor downloading. */
    suspend fun startMissingDownloads(songs: List<ContentOccurrence>)
    /** Removes the downloads of these songs from the Downloaded Songs storage. The menu asks for confirmation first. */
    suspend fun removeDownloads(songs: List<ContentOccurrence>)
    /** Opens the tag assignment sheet for the playlist. */
    fun openPlaylistTags(playlist: ContentRef)
    /** Opens the host's cover picker. The picker and the copy of the chosen image belong to the host. */
    fun changePlaylistCover(playlist: ContentRef)
    /** Removes the custom cover and releases its stored copy. */
    suspend fun removePlaylistCover(playlist: ContentRef)
    /**
     * Pushes the local playlist to YouTube, creating the remote playlist first when it has none. The host reports
     * the sync-disabled and not-signed-in gates itself, and throws on any other failure.
     */
    suspend fun syncToYouTube(playlist: ContentRef)
    /** Hides or unhides the playlist in the library. */
    suspend fun setHidden(playlist: ContentRef, hidden: Boolean)
    /** Deletes the local playlist, releases its cover and deletes the linked YouTube playlist. */
    suspend fun deletePlaylist(playlist: ContentRef)
    /** Opens the Telegram bots page, where the user chooses the bot that fetches songs for a channel playlist. */
    fun openTelegramBots()
    /** Re-syncs a Telegram-channel playlist from its channel. May return once the refresh is accepted. */
    suspend fun refreshTelegramPlaylist(playlist: ContentRef)
}

/**
 * Facts the caller holds for one playlist. [complete] is the whole playlist, confirmed complete; every song-based
 * row stays disabled until it is non-null. The shared menu reads no download, cover or sync storage itself.
 */
data class PlaylistMenuFacts(
    val playlist: ContentRef,
    /** Shown in the confirmations, which name the playlist as the baseline does. */
    val name: String,
    val complete: FinitePlaylistCompleteList<ContentOccurrence>?,
    /** Aggregate over the playlist songs, derived the same way as for selections. */
    val download: CollectionDownloadState,
    val speedDialPinned: Boolean,
    val hidden: Boolean,
    /** Linked YouTube playlist id. Start radio is offered only for a linked playlist, as the baseline does. */
    val browseId: String?,
    /** Share link of the playlist record, used when there is no browse id. */
    val shareLink: String?,
    val editable: Boolean,
    /** Automatic playlists keep only the playback and speed-dial rows. */
    val automatic: Boolean,
    /** The Downloaded Songs playlist has no tag row and no download group. */
    val downloadPlaylist: Boolean,
    /** Telegram-channel playlists get the add-songs and refresh rows. */
    val telegramChannel: Boolean,
    /** The host can change this playlist's cover. */
    val canChangeCover: Boolean,
    /** The host has a custom cover to remove. */
    val canRemoveCover: Boolean,
)

/**
 * The playlist rows in the baseline order: play and shuffle, start radio, play next and add to queue, speed dial,
 * rename, cover, tags, downloads, Telegram, sync, hide and delete, then share or export. [rename] is the row the
 * caller built for the rename dialog it owns, or null when renaming is not allowed.
 */
fun playlistMenuActions(
    facts: PlaylistMenuFacts,
    host: PlaylistMenuHost,
    queue: QueueController,
    sharing: MenuShareHandler,
    exportLocal: suspend () -> Unit,
    rename: OverflowAction?,
): List<OverflowAction> {
    val (play, shuffle, playNext, addQueue) = collectionQueueActions(facts.complete, queue)
    val ref = facts.playlist
    val name = facts.name
    val songs = facts.complete?.songs?.map { it.item }
    val songsReady = !songs.isNullOrEmpty()
    fun selected(): List<ContentOccurrence> = requireNotNull(songs).also { require(it.isNotEmpty()) { "Empty playlist" } }
    return buildList {
        add(play)
        add(shuffle)
        if (!facts.browseId.isNullOrBlank()) {
            add(OverflowAction("start_radio", Res.string.m6_start_radio) { host.startPlaylistRadio(ref) })
        }
        add(playNext)
        add(addQueue)
        add(OverflowAction(
            "speed_dial",
            if (facts.speedDialPinned) Res.string.m6_remove_speed_dial else Res.string.m6_pin_speed_dial,
        ) { host.setSpeedDialPinned(ref, !facts.speedDialPinned) })
        if (rename != null) add(rename)
        if (facts.editable && !facts.automatic) {
            if (facts.canChangeCover) {
                add(OverflowAction("change_cover", Res.string.m6_change_playlist_cover) { host.changePlaylistCover(ref) })
            }
            if (facts.canRemoveCover) {
                add(OverflowAction("remove_cover", Res.string.m6_remove_playlist_cover) { host.removePlaylistCover(ref) })
            }
        }
        if (!facts.automatic && !facts.downloadPlaylist) {
            add(OverflowAction("manage_tags", Res.string.m6_manage_tags) { host.openPlaylistTags(ref) })
        }
        if (!facts.downloadPlaylist) {
            when (facts.download) {
                CollectionDownloadState.COMPLETED -> add(OverflowAction(
                    "remove_downloads", Res.string.m6_remove_download, songsReady, destructive = true,
                    confirm = Res.string.m6_remove_playlist_downloads_confirm, confirmArgs = listOf(name),
                ) { host.removeDownloads(selected()) })
                CollectionDownloadState.IN_PROGRESS -> add(OverflowAction(
                    "downloading", Res.string.m6_downloading, songsReady,
                    confirm = Res.string.m6_remove_playlist_downloads_confirm, confirmArgs = listOf(name),
                ) { host.removeDownloads(selected()) })
                CollectionDownloadState.NOT_DOWNLOADED -> add(OverflowAction(
                    "download_missing", Res.string.m6_download, songsReady,
                ) { host.startMissingDownloads(selected()) })
            }
        }
        if (!facts.automatic) {
            if (facts.telegramChannel) {
                add(OverflowAction("telegram_add_songs", Res.string.m6_telegram_add_songs) { host.openTelegramBots() })
                add(OverflowAction("telegram_refresh", Res.string.m6_refresh_telegram_playlist) { host.refreshTelegramPlaylist(ref) })
            }
            add(OverflowAction("sync_playlist", Res.string.m6_sync_playlist) { host.syncToYouTube(ref) })
            add(OverflowAction(
                "hide_playlist",
                if (facts.hidden) Res.string.m6_unhide_playlist else Res.string.m6_hide_playlist,
                confirm = if (facts.hidden) null else Res.string.m6_hide_playlist_confirm,
                confirmArgs = listOf(name),
            ) { host.setHidden(ref, !facts.hidden) })
            add(OverflowAction(
                "delete_playlist", Res.string.m6_delete, destructive = true,
                confirm = Res.string.m6_delete_playlist_confirm, confirmArgs = listOf(name),
            ) { host.deletePlaylist(ref) })
        }
        addAll(playlistOverflowActions(facts.browseId, facts.shareLink, sharing, exportLocal))
    }
}
