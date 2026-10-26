/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.menu.artistalbum

import moe.rukamori.archivetune.shared.domain.queue.UserQueuedKind
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.LibraryMutation
import moe.rukamori.archivetune.shared.domain.repository.LibraryRepository
import moe.rukamori.archivetune.shared.domain.repository.QueueController
import moe.rukamori.archivetune.shared.ui.menu.CollectionDownloadState
import moe.rukamori.archivetune.shared.ui.menu.MenuShareHandler
import moe.rukamori.archivetune.shared.ui.menu.OverflowAction
import moe.rukamori.archivetune.shared.ui.resources.*

/**
 * Library album rows. Share, download and refetch are omitted for local albums, as the baseline library menu does.
 * Play and shuffle queue the album's own songs; they do not start the provider album radio. Add to playlist and the
 * artist chooser are dialogs owned by [LibraryAlbumOverflowMenu].
 */
fun libraryAlbumActions(
    facts: LibraryAlbumMenuFacts,
    queue: QueueController,
    library: LibraryRepository,
    sharing: MenuShareHandler,
    host: ArtistAlbumMenuHost,
): List<OverflowAction> {
    val album = facts.album.album
    val liked = facts.album.liked
    val songs = facts.album.songs.orEmpty()
    val ready = songs.isNotEmpty()
    val shareId = facts.album.shareId
    return buildList {
        add(OverflowAction("play", Res.string.m6_play, ready) {
            queue.replace(songs, songs.first().occurrenceId, true)
        })
        add(OverflowAction("shuffle", Res.string.m6_shuffle, ready) {
            val shuffled = songs.shuffled()
            queue.replace(shuffled, shuffled.first().occurrenceId, true)
        })
        if (!facts.isLocal) {
            add(OverflowAction("share", Res.string.m6_share, !shareId.isNullOrBlank()) {
                check(sharing.playlist(shareId, null)) { "Share target unavailable" }
            })
        }
        add(OverflowAction("play_next", Res.string.m6_play_next, ready) { queue.insert(songs, UserQueuedKind.PLAY_NEXT) })
        add(OverflowAction("add_queue", Res.string.m6_add_queue, ready) { queue.insert(songs, UserQueuedKind.ADD_TO_QUEUE) })
        add(OverflowAction("like", if (liked) Res.string.m6_unlike else Res.string.m6_like, dismissOnSuccess = false) {
            library.mutate(LibraryMutation.Like(album, !liked))
        })
        add(speedDialRow(album, facts.album.speedDialPinned, host))
        if (!facts.isLocal) {
            // Baseline: download group actions never dismiss the menu, so the state stays visible while work runs.
            when (facts.album.download) {
                CollectionDownloadState.COMPLETED -> add(
                    OverflowAction("remove_download", Res.string.m6_remove_download, ready, dismissOnSuccess = false) {
                        host.removeDownloads(songs)
                    },
                )
                // Baseline: tapping an in-progress album removes its queued and running downloads.
                CollectionDownloadState.IN_PROGRESS -> add(
                    OverflowAction("downloading", Res.string.m6_downloading, ready, dismissOnSuccess = false) {
                        host.removeDownloads(songs)
                    },
                )
                CollectionDownloadState.NOT_DOWNLOADED -> add(
                    OverflowAction("download", Res.string.m6_download, ready, dismissOnSuccess = false) {
                        host.download(songs)
                    },
                )
            }
            add(OverflowAction("refetch", Res.string.m6aa_refetch, dismissOnSuccess = false) {
                host.refetchAlbum(album)
            })
        }
        add(OverflowAction("hide_from_home", Res.string.m6_hide_from_home) { host.hideAlbumFromHome(album) })
    }
}

/**
 * YouTube artist rows. Start radio and shuffle are each omitted when the artist page has no matching endpoint, as the
 * baseline YouTube artist menu does. Share is always listed and disabled unless the channel id is shareable.
 */
fun youTubeArtistActions(
    facts: YouTubeArtistMenuFacts,
    sharing: MenuShareHandler,
    host: ArtistAlbumMenuHost,
): List<OverflowAction> {
    val menu = facts.artist
    val ref = menu.artist
    val channel = shareableChannelId(menu.channelId)
    return buildList {
        if (menu.radioAvailable) {
            add(OverflowAction("start_radio", Res.string.m6_start_radio) { host.playArtistRadio(ref) })
        }
        if (facts.shuffleAvailable) {
            add(OverflowAction("shuffle", Res.string.m6_shuffle) { host.playArtistShuffle(ref) })
        }
        add(OverflowAction("share", Res.string.m6_share, channel != null) {
            check(sharing.artist(channel)) { "Share target unavailable" }
        })
        add(subscribeRow(ref, menu.subscribed, host))
        add(speedDialRow(ref, menu.speedDialPinned, host))
    }
}

/**
 * Library artist rows. Play and shuffle are omitted for an artist with no local songs, and share is omitted unless the
 * artist is YouTube-backed, as the baseline library artist menu does. Hide from home is always listed.
 */
fun libraryArtistActions(
    facts: LibraryArtistMenuFacts,
    sharing: MenuShareHandler,
    host: ArtistAlbumMenuHost,
): List<OverflowAction> {
    val menu = facts.artist
    val ref = menu.artist
    val channel = if (facts.isYouTubeArtist) shareableChannelId(menu.channelId) else null
    return buildList {
        if (facts.songCount > 0) {
            add(OverflowAction("play", Res.string.m6_play) { host.playArtist(ref, shuffled = false) })
            add(OverflowAction("shuffle", Res.string.m6_shuffle) { host.playArtist(ref, shuffled = true) })
        }
        if (facts.isYouTubeArtist) {
            add(OverflowAction("share", Res.string.m6_share, channel != null) {
                check(sharing.artist(channel)) { "Share target unavailable" }
            })
        }
        add(subscribeRow(ref, menu.subscribed, host))
        add(speedDialRow(ref, menu.speedDialPinned, host))
        add(OverflowAction("hide_from_home", Res.string.m6_hide_from_home) { host.hideFromHome(ref) })
    }
}

/** Subscription is a host-owned artist semantic; it never becomes a generic library like on ARTIST refs. */
private fun subscribeRow(artist: ContentRef, subscribed: Boolean, host: ArtistAlbumMenuHost): OverflowAction =
    OverflowAction("subscribe", if (subscribed) Res.string.m6_subscribed else Res.string.m6_subscribe, dismissOnSuccess = false) {
        host.setSubscribed(artist, !subscribed)
    }

private fun speedDialRow(ref: ContentRef, pinned: Boolean, host: ArtistAlbumMenuHost): OverflowAction =
    OverflowAction("speed_dial", if (pinned) Res.string.m6_remove_speed_dial else Res.string.m6_pin_speed_dial) {
        host.setSpeedDialPinned(ref, !pinned)
    }
