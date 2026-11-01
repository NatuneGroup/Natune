package moe.rukamori.archivetune.shared.ui.menu.youtubeplaylist

import moe.rukamori.archivetune.shared.domain.queue.UserQueuedKind
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.QueueController
import moe.rukamori.archivetune.shared.ui.menu.MenuShareHandler
import moe.rukamori.archivetune.shared.ui.menu.OverflowAction
import moe.rukamori.archivetune.shared.ui.resources.*

/**
 * The command rows of the YouTube playlist menu, in baseline order. The sheet renders this list and an
 * overflow menu can render it too, so each command has one implementation.
 *
 * Whole-list commands on a finite playlist keep the menu open, as the baseline did, so the loader dialog
 * stays visible above it. Endless lists dismiss after their start command. Sync is a switch rather than a
 * command, so the sheet renders it separately. Play, shuffle and radio appear only when the playlist has
 * the matching endpoint. Every other command is always present.
 */
fun youTubePlaylistOverflowActions(
    facts: YouTubePlaylistMenuFacts,
    local: YouTubePlaylistLocalState,
    songs: List<ContentItem>,
    downloadState: YouTubePlaylistDownloadState,
    host: YouTubePlaylistMenuHost,
    queue: QueueController,
    sharing: MenuShareHandler,
    canSelect: Boolean,
    onAddToPlaylist: () -> Unit,
    onImport: () -> Unit,
    onConfirmRemoveDownloads: () -> Unit,
    onSelect: () -> Unit,
): List<OverflowAction> {
    val ref = facts.item.ref
    val finite = facts.isFinite
    return buildList<OverflowAction> {
        if (facts.canPlay) {
            add(OverflowAction(id = "play", label = Res.string.m6_play, dismissOnSuccess = !finite) {
                host.startPlayback(ref, YouTubePlaylistEndpoint.PLAY)
            })
        }
        if (facts.canShuffle) {
            add(OverflowAction(id = "shuffle", label = Res.string.m6_shuffle, dismissOnSuccess = !finite) {
                if (finite) {
                    val all = host.occurrences(ref)
                    if (all.isNotEmpty()) {
                        val shuffled = all.shuffled()
                        queue.replace(shuffled, shuffled.first().occurrenceId, true)
                    }
                } else {
                    host.startPlayback(ref, YouTubePlaylistEndpoint.SHUFFLE)
                }
            })
        }
        if (facts.canRadio) {
            add(OverflowAction(id = "start_radio", label = Res.string.m6yp_start_radio) {
                host.startPlayback(ref, YouTubePlaylistEndpoint.RADIO)
            })
        }
        add(OverflowAction(id = "play_next", label = Res.string.m6_play_next, dismissOnSuccess = !finite) {
            val all = host.occurrences(ref)
            if (all.isNotEmpty()) queue.insert(all, UserQueuedKind.PLAY_NEXT)
        })
        add(OverflowAction(id = "add_queue", label = Res.string.m6_add_queue, dismissOnSuccess = !finite) {
            val all = host.occurrences(ref)
            if (all.isNotEmpty()) queue.insert(all, UserQueuedKind.ADD_TO_QUEUE)
        })
        add(OverflowAction(id = "add_playlist", label = Res.string.m6_add_playlist, dismissOnSuccess = false) {
            onAddToPlaylist()
        })
        add(OverflowAction(
            id = "speed_dial",
            label = if (local.speedDialPinned) Res.string.m6yp_remove_from_speed_dial else Res.string.m6yp_pin_to_speed_dial,
        ) {
            host.setSpeedDialPinned(facts, !local.speedDialPinned)
        })
        add(OverflowAction(id = "import", label = Res.string.m6yp_import_playlist, dismissOnSuccess = false) {
            onImport()
        })
        when (downloadState) {
            YouTubePlaylistDownloadState.COMPLETED -> add(
                OverflowAction(id = "remove_download", label = Res.string.m6yp_remove_download, dismissOnSuccess = false) {
                    onConfirmRemoveDownloads()
                },
            )
            YouTubePlaylistDownloadState.IN_PROGRESS -> add(
                OverflowAction(id = "downloading", label = Res.string.m6yp_downloading, dismissOnSuccess = false) {
                    onConfirmRemoveDownloads()
                },
            )
            YouTubePlaylistDownloadState.NOT_DOWNLOADED -> add(
                OverflowAction(id = "download", label = Res.string.m6yp_action_download, dismissOnSuccess = false) {
                    val known = if (songs.isNotEmpty()) songs else host.occurrences(ref).map { it.item }
                    host.startMissingDownloads(ref, known)
                },
            )
        }
        add(OverflowAction(id = "share", label = Res.string.m6_share) {
            check(sharing.playlist(ref.id, facts.shareLink)) { "Share target unavailable" }
        })
        add(OverflowAction(id = "copy_link", label = Res.string.m5pq_copy_link) {
            check(sharing.copyPlaylistLink(ref.id, facts.shareLink)) { "Link unavailable" }
        })
        if (canSelect) {
            add(OverflowAction(id = "select", label = Res.string.m6yp_select) { onSelect() })
        }
    }
}

/** The complete song list as occurrences. A whole-list command never works from a partial page. */
private suspend fun YouTubePlaylistMenuHost.occurrences(playlist: ContentRef): List<ContentOccurrence> =
    loadComplete(playlist).songs.map { it.item }
