/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.menu

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.queue.UserQueuedKind
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Owner-backed album and artist effects. Radio sources, download and speed-dial persistence, artist
 * subscription and hide-from-home state stay with their owners; the menu names only the record it acted on.
 * Each callback throws on failure so the shared menu reports it instead of a fabricated success.
 */
interface CollectionMenuHost {
    /** Starts the album's provider radio; the host resolves the playlist identity from its own record. */
    suspend fun playAlbumRadio(album: ContentRef)
    /** Starts a YouTube artist's radio endpoint. */
    suspend fun playArtistRadio(artist: ContentRef)
    /** Plays a library artist's songs, shuffled when [shuffled] is true. */
    suspend fun playArtist(artist: ContentRef, shuffled: Boolean)
    /** Pins or unpins the record on speed dial. The host fetches a missing record before pinning it. */
    suspend fun setSpeedDialPinned(ref: ContentRef, pinned: Boolean)
    /** Artist subscription is a host-owned semantic; it is never a generic [LibraryMutation.Like]. */
    suspend fun setSubscribed(artist: ContentRef, subscribed: Boolean)
    /** Adds the artist to the home hidden set so it leaves the keep-listening shelf. */
    suspend fun hideFromHome(artist: ContentRef)
    suspend fun download(songs: List<ContentOccurrence>)
    suspend fun removeDownloads(songs: List<ContentOccurrence>)
}

/** Aggregate download state the host derives from every song of the album. */
enum class CollectionDownloadState { NOT_DOWNLOADED, IN_PROGRESS, COMPLETED }

/**
 * Album facts for the menu. [songs] is null until the album page is loaded; a partial page must never be
 * committed as the whole album, so every song-based command stays disabled until it is non-null.
 */
data class AlbumMenuFacts(
    val album: ContentRef,
    val shareId: String?,
    val songs: List<ContentOccurrence>?,
    val liked: Boolean,
    val download: CollectionDownloadState,
    val speedDialPinned: Boolean,
)

/** Artist facts for both the YouTube artist menu and the library artist menu. */
data class ArtistMenuFacts(
    val artist: ContentRef,
    val channelId: String?,
    val radioAvailable: Boolean,
    val subscribed: Boolean,
    val speedDialPinned: Boolean,
)

/**
 * YouTube album commands from the baseline YouTube album menu. Play and shuffle both start the album radio, as the
 * baseline does. Library albums use `libraryAlbumActions` in `menu.artistalbum`, which queues the album's own songs.
 * Add to playlist and the artist chooser are added by [AlbumOverflowMenu]; the dropdown places them after these
 * shared rows.
 */
fun albumOverflowActions(
    facts: AlbumMenuFacts,
    queue: QueueController,
    library: LibraryRepository,
    sharing: MenuShareHandler,
    host: CollectionMenuHost,
): List<OverflowAction> {
    val ready = !facts.songs.isNullOrEmpty()
    fun selected(): List<ContentOccurrence> = requireNotNull(facts.songs).also { require(it.isNotEmpty()) { "Empty album" } }
    return buildList {
        add(OverflowAction("play", Res.string.m6_play, ready) { host.playAlbumRadio(facts.album) })
        add(OverflowAction("shuffle", Res.string.m6_shuffle, ready) { host.playAlbumRadio(facts.album) })
        add(OverflowAction("share", Res.string.m6_share, !facts.shareId.isNullOrBlank()) {
            check(sharing.playlist(facts.shareId, null)) { "Share target unavailable" }
        })
        add(OverflowAction("play_next", Res.string.m6_play_next, ready) { queue.insert(selected(), UserQueuedKind.PLAY_NEXT) })
        add(OverflowAction("add_queue", Res.string.m6_add_queue, ready) { queue.insert(selected(), UserQueuedKind.ADD_TO_QUEUE) })
        add(OverflowAction("like", if (facts.liked) Res.string.m6_unlike else Res.string.m6_like, dismissOnSuccess = false) {
            library.mutate(LibraryMutation.Like(facts.album, !facts.liked))
        })
        add(speedDialAction(facts.album, facts.speedDialPinned, host))
        // Baseline: download group actions never dismiss the menu, so the state stays visible while work runs.
        when (facts.download) {
            CollectionDownloadState.COMPLETED -> add(
                OverflowAction("remove_download", Res.string.m6_remove_download, ready, dismissOnSuccess = false) {
                    host.removeDownloads(selected())
                },
            )
            // Baseline: tapping an in-progress album removes its queued and running downloads.
            CollectionDownloadState.IN_PROGRESS -> add(
                OverflowAction("downloading", Res.string.m6_downloading, ready, dismissOnSuccess = false) {
                    host.removeDownloads(selected())
                },
            )
            CollectionDownloadState.NOT_DOWNLOADED -> add(
                OverflowAction("download", Res.string.m6_download, ready, dismissOnSuccess = false) {
                    host.download(selected())
                },
            )
        }
    }
}

private fun speedDialAction(ref: ContentRef, pinned: Boolean, host: CollectionMenuHost): OverflowAction =
    OverflowAction("speed_dial", if (pinned) Res.string.m6_remove_speed_dial else Res.string.m6_pin_speed_dial) {
        host.setSpeedDialPinned(ref, !pinned)
    }

/**
 * YouTube album menu with the baseline "View artist" row. Library albums use `LibraryAlbumOverflowMenu` in
 * `menu.artistalbum`. One artist navigates directly; a split multi-artist album opens a chooser, and every choice keeps
 * the album's original artist reference.
 */
@Composable
fun AlbumOverflowMenu(
    identity: String,
    expanded: Boolean,
    facts: AlbumMenuFacts,
    queue: QueueController,
    library: LibraryRepository,
    sharing: MenuShareHandler,
    host: CollectionMenuHost,
    artists: List<ArtistChoice<ContentRef>>,
    onViewArtist: (ContentRef) -> Unit,
    editablePlaylists: List<ContentItem>,
    onDismiss: () -> Unit,
    onFailure: () -> Unit,
) {
    var choosingArtist by remember(identity) { mutableStateOf(false) }
    LaunchedEffect(expanded) { if (!expanded) choosingArtist = false }
    val artistCommands = if (artists.isEmpty()) emptyList<OverflowAction>() else listOf(
        OverflowAction("view_artist", Res.string.m6_view_artist, dismissOnSuccess = artists.size == 1) {
            if (artists.size == 1) onViewArtist(artists.single().original) else choosingArtist = true
        },
    )
    OccurrenceOverflowMenu(
        identity, expanded && !choosingArtist, facts.songs?.takeIf { it.isNotEmpty() },
        albumOverflowActions(facts, queue, library, sharing, host) + artistCommands,
        library, editablePlaylists, onDismiss, onFailure,
    )
    if (expanded && choosingArtist) AlertDialog(
        onDismissRequest = { choosingArtist = false },
        title = { Text(stringResource(Res.string.m6_view_artist)) },
        text = { LazyColumn(Modifier.heightIn(max = 400.dp)) {
            items(artists) { choice ->
                TextButton(onClick = {
                    choosingArtist = false
                    onViewArtist(choice.original)
                    onDismiss()
                }, modifier = Modifier.testTag("artist:${choice.original.id}")) { Text(choice.name) }
            }
        } },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { choosingArtist = false }) { Text(stringResource(Res.string.m6_cancel)) } },
    )
}
