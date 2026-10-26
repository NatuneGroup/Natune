/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.menu.youtubeplaylist

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistCompleteList
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistIds
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef

/** The account's Liked Music shelf. It is never bookmarked as a remote playlist copy. */
private const val LIKED_MUSIC_BROWSE_ID = "LM"

/**
 * Remote facts about one YouTube playlist as the browse surface reported them. Local state
 * (bookmark, auto sync, speed dial) is observed from the host instead, so the menu cannot keep
 * rendering a copy the host has since changed.
 */
data class YouTubePlaylistMenuFacts(
    val item: ContentItem,
    val shareLink: String?,
    val songCountText: String?,
    val remoteEditable: Boolean,
    val canPlay: Boolean,
    val canShuffle: Boolean,
    val canRadio: Boolean,
) {
    init {
        require(item.ref.kind == ContentKind.PLAYLIST)
    }

    /** Radio mixes are endless, so only finite playlists take the whole-list paths. */
    val isFinite: Boolean get() = FinitePlaylistIds.isFinite(item.ref.id)

    val bookmarkable: Boolean get() = item.ref.id != LIKED_MUSIC_BROWSE_ID && !remoteEditable

    /** The first run of digits in the count text, which the baseline persisted as the remote count. */
    val remoteSongCount: Int? get() = songCountText?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
}

/** What the host has persisted for one remote playlist. These fields track the local database. */
data class YouTubePlaylistLocalState(
    val recordExists: Boolean,
    val bookmarked: Boolean,
    val autoSync: Boolean,
    val speedDialPinned: Boolean,
) {
    companion object {
        /**
         * Shown before the host's first emission. A command issued from it stays safe, because every host
         * write re-checks the record inside its own transaction and so cannot create a second record.
         */
        val Initial = YouTubePlaylistLocalState(
            recordExists = false,
            bookmarked = false,
            autoSync = false,
            speedDialPinned = false,
        )
    }
}

/** The shared finite-playlist loader as the dialogs see it. The host owns every transition. */
data class YouTubePlaylistLoadState(
    val showDialog: Boolean,
    val loading: Boolean,
    val incomplete: Boolean,
    /** A whole-list action never offers a partial "reload from beginning". */
    val wholeAction: Boolean,
)

/** A song's download as the host reports it. Any state not listed here, including failed, is [STOPPED]. */
enum class YouTubeDownloadStatus { QUEUED, DOWNLOADING, COMPLETED, STOPPED }

enum class YouTubePlaylistDownloadState { NOT_DOWNLOADED, IN_PROGRESS, COMPLETED }

/** The remote endpoint a playback command starts. Finite whole-list shuffle is client-side and never uses [SHUFFLE]. */
enum class YouTubePlaylistEndpoint { PLAY, SHUFFLE, RADIO }

/**
 * Aggregates per-song statuses for the download row. One song without a completed or in-flight
 * status keeps the whole playlist in the download state, as the baseline did.
 */
fun youTubePlaylistDownloadState(
    songIds: List<String>,
    statuses: Map<String, YouTubeDownloadStatus>,
): YouTubePlaylistDownloadState {
    if (songIds.isEmpty()) return YouTubePlaylistDownloadState.NOT_DOWNLOADED
    if (songIds.all { statuses[it] == YouTubeDownloadStatus.COMPLETED }) return YouTubePlaylistDownloadState.COMPLETED
    val inFlight = songIds.all { id ->
        when (statuses[id]) {
            YouTubeDownloadStatus.QUEUED, YouTubeDownloadStatus.DOWNLOADING, YouTubeDownloadStatus.COMPLETED -> true
            else -> false
        }
    }
    return if (inFlight) YouTubePlaylistDownloadState.IN_PROGRESS else YouTubePlaylistDownloadState.NOT_DOWNLOADED
}

/**
 * Everything the menu cannot do by itself: local persistence, playback endpoints, downloads and the
 * loader's transitions. Suspend callbacks throw on failure, and the menu reports the failure while the
 * sheet stays open. Suspend callbacks must be main-safe, so the host moves its own I/O off the caller's
 * dispatcher. Every member is required, never defaulted, so a missing owner cannot silently remove an
 * action.
 */
interface YouTubePlaylistMenuHost {
    /** The loader state that drives [YouTubePlaylistLoadDialog] and [YouTubePlaylistRecoveryActions]. */
    val loadState: StateFlow<YouTubePlaylistLoadState>

    /** Per-song download statuses keyed by song id. */
    val downloads: StateFlow<Map<String, YouTubeDownloadStatus>>

    /** Local persistence for one remote playlist, re-emitted after every committed change. */
    fun observeLocal(playlist: ContentRef): Flow<YouTubePlaylistLocalState>

    fun retryLoading()
    fun reloadFromBeginning()
    fun cancelLoading()
    fun dismissLoadError()

    /**
     * Loads the full song list. Every whole-list command calls this, endless lists included, and the host
     * decides what completeness means for each kind. It throws when completeness is not proven, and the
     * caller then commits nothing. The host publishes [loadState] for the whole load.
     */
    suspend fun loadComplete(playlist: ContentRef): FinitePlaylistCompleteList<ContentOccurrence>

    /**
     * Starts the playback queue for [endpoint], using the endpoint parameters the host has for the playlist.
     * The menu never sends [YouTubePlaylistEndpoint.SHUFFLE] for a finite playlist, whose shuffle is client-side.
     */
    suspend fun startPlayback(playlist: ContentRef, endpoint: YouTubePlaylistEndpoint)

    /**
     * Creates the local record when none exists, or refreshes its metadata, replaces its songs with
     * [songs], and marks it bookmarked.
     */
    suspend fun bookmark(playlist: YouTubePlaylistMenuFacts, songs: FinitePlaylistCompleteList<ContentOccurrence>)

    /** Refreshes an existing record's metadata from [playlist], then sets its bookmark flag. */
    suspend fun setBookmarked(playlist: YouTubePlaylistMenuFacts, bookmarked: Boolean)

    /** Pins or unpins the playlist on the speed dial. Pinning creates the local record first when it is missing. */
    suspend fun setSpeedDialPinned(playlist: YouTubePlaylistMenuFacts, pinned: Boolean)

    /**
     * Sets auto sync. [songs] is non-null only when no record exists yet, and the host then creates the
     * record holding them. Enabling sync on an existing record triggers the host's auto-sync worker.
     */
    suspend fun setAutoSync(
        playlist: YouTubePlaylistMenuFacts,
        enabled: Boolean,
        songs: FinitePlaylistCompleteList<ContentOccurrence>?,
    )

    /** Id of the local record bound to this browse id, or null. Read-only. */
    suspend fun findLocalPlaylistId(playlist: ContentRef): String?

    /** Sets the bookmark on an unbookmarked duplicate as soon as the duplicate is detected, as the baseline did. */
    suspend fun ensureBookmarked(localPlaylistId: String)

    /** Appends the songs that the local playlist does not already contain, keyed by song id. */
    suspend fun addMissingSongs(localPlaylistId: String, songs: FinitePlaylistCompleteList<ContentOccurrence>)

    /** Creates a bookmarked, non-editable local record linked to [playlist]'s browse id, holding [songs]. */
    suspend fun importLinked(
        playlist: YouTubePlaylistMenuFacts,
        name: String,
        songs: FinitePlaylistCompleteList<ContentOccurrence>,
    )

    /** Creates a bookmarked, editable local record with no browse id, holding [songs]. */
    suspend fun importLocalCopy(name: String, songs: FinitePlaylistCompleteList<ContentOccurrence>)

    /** Queues downloads for the songs that are not already downloaded. */
    suspend fun startMissingDownloads(playlist: ContentRef, songs: List<ContentItem>)

    /** Removes the downloads of the given song ids. */
    suspend fun removeDownloads(songIds: List<String>)
}
