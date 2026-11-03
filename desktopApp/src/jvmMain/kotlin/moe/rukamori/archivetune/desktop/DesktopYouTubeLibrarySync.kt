package moe.rukamori.archivetune.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.shared.data.persistence.RoomLibraryRepository
import moe.rukamori.archivetune.shared.data.provider.YouTubeAccountBinding
import moe.rukamori.archivetune.shared.data.provider.YouTubeContentRepository
import moe.rukamori.archivetune.shared.domain.library.AlbumSortType
import moe.rukamori.archivetune.shared.domain.library.ArtistSortType
import moe.rukamori.archivetune.shared.domain.library.LibrarySortState
import moe.rukamori.archivetune.shared.domain.platform.AccountAdmission
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistCompletion
import moe.rukamori.archivetune.shared.domain.queue.IncompleteFinitePlaylistException
import moe.rukamori.archivetune.shared.domain.repository.ContentAccountChanged
import moe.rukamori.archivetune.shared.domain.repository.ContentAccountRequired
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentQuery
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface
import moe.rukamori.archivetune.shared.domain.repository.LibraryMutation

/**
 * Pulls the signed-in YouTube Music library into the local library, as the baseline `SyncUtils.performFullSync` does
 * for liked songs, liked albums and artist subscriptions. Every remote read acts for the account [account] admits, and
 * the local writes go straight to [library], so a pulled like is never sent back to the provider.
 *
 * A listing is applied only once it was read to its end; a failed or incomplete read changes nothing. Songs liked here
 * but missing remotely are unliked only by an authoritative sync, which follows an account switch, as in the baseline.
 * Stale liked albums and artists are cleared on every sync, as the baseline clears them. The library-songs corpus and
 * saved playlists have no listing on this host yet, so they stay local.
 */
internal class DesktopYouTubeLibrarySync(
    private val content: YouTubeContentRepository,
    private val account: YouTubeAccountBinding,
    private val library: RoomLibraryRepository,
    private val beforeSync: () -> Unit,
    private val report: (String) -> Unit,
) {
    private val mutex = Mutex()

    /**
     * One full sync. An ordinary sync is skipped while another runs; an authoritative one waits for it, so the sync
     * after an account switch always runs against the new account.
     */
    suspend fun performFullSync(authoritative: Boolean = false) {
        if (authoritative) mutex.lock() else if (!mutex.tryLock()) return
        try {
            beforeSync()
            val admission = admitted() ?: return
            step("liked songs") { syncLikedSongs(admission, authoritative) }
            step("liked albums") { syncLikedAlbums(admission, authoritative) }
            step("artist subscriptions") { syncArtists(admission, authoritative) }
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun admitted(): AccountAdmission? =
        try {
            account.remoteAccount()
        } catch (_: ContentAccountRequired) {
            null
        }

    /**
     * The gate the playlist menu checks before it offers the sync push, as the baseline read its sync and login flags:
     * no sign-in reports the login notice, a sign-in without an admission reports the disabled one.
     */
    suspend fun playlistSyncGate(): DesktopPlaylistSyncGate =
        when {
            !account.transport.signedIn() -> DesktopPlaylistSyncGate.NOT_SIGNED_IN
            admitted() == null -> DesktopPlaylistSyncGate.SYNC_DISABLED
            else -> DesktopPlaylistSyncGate.READY
        }

    /**
     * The baseline playlist push: an unlinked playlist is created remotely and filled once, and the remote record is
     * stored. A linked one pulls the remote order into the local rows, replacing what a removal dropped. [videoIds]
     * are the local rows' YouTube ids in stored order, as the baseline's song ids were.
     */
    suspend fun syncPlaylist(playlistId: String, title: String, videoIds: List<String>) {
        mutex.lock()
        try {
            beforeSync()
            val admission = admitted() ?: throw ContentAccountRequired()
            val linked = library.observeLocalPlaylists().first()
                .firstOrNull { it.occurrence.item.ref.kind == ContentKind.PLAYLIST &&
                    it.occurrence.item.ref.id == playlistId }
                ?.browseId
            val browseId = linked ?: YouTube.createPlaylist(title).getOrThrow()
            fence(admission)
            if (linked == null) {
                if (videoIds.isNotEmpty()) {
                    YouTube.addSongsToPlaylist(playlistId = browseId, videoIds = videoIds) { _, _ -> }.getOrThrow()
                }
                fence(admission)
                library.setPlaylistRemote(playlistId, browseId, videoIds.size)
            } else {
                val remote = readRemotePlaylist(browseId)
                fence(admission)
                // An empty remote page keeps the local rows, as the baseline refused to clear on an empty pull.
                if (remote.isNotEmpty()) {
                    library.saveItems(remote.map { it.item })
                    library.mutate(LibraryMutation.ReplacePlaylistEntries(playlistId, remote))
                }
                library.setPlaylistRemote(playlistId, browseId, remote.size)
            }
        } finally {
            mutex.unlock()
        }
    }

    /**
     * The linked remote playlist's complete song page, read under the strict whole-list rule: a repeated or blank
     * continuation, a mid-list page that answers empty or a request budget overrun all refuse the read, so a
     * truncated remote can never replace the local rows.
     */
    private suspend fun readRemotePlaylist(browseId: String): List<ContentOccurrence> {
        val request = ContentRequest(
            ContentProvider.YOUTUBE,
            ContentSurface.PLAYLIST,
            parent = ContentRef(ContentProvider.YOUTUBE.id, ContentKind.PLAYLIST, browseId),
        )
        val items = mutableListOf<ContentOccurrence>()
        // The wrapped cursor carries the request key and a running offset, so a provider token repeated at a later
        // offset never equals itself. Repeat detection runs on the raw token after the second separator, which is
        // the identity the provider itself returns.
        val consumed = mutableSetOf<String>()
        var continuation: String? = null
        var rawNext: String? = null
        var requests = 0
        do {
            if (++requests > FinitePlaylistCompletion.MAX_REQUESTS) throw IncompleteFinitePlaylistException()
            val token = continuation
            val raw = rawNext
            if (raw != null && !consumed.add(raw)) throw IncompleteFinitePlaylistException()
            val page = content.page(request, token)
            rawNext = page.continuation?.let(::rawContinuationToken)
            FinitePlaylistCompletion.validateNext(rawNext, consumed, page.items.size, initial = requests == 1)
            items += page.items
            continuation = page.continuation
        } while (continuation != null)
        return items
    }

    private suspend fun step(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            report("YouTube $name were not synced: ${failure.javaClass.simpleName}")
        }
    }

    private suspend fun syncLikedSongs(admission: AccountAdmission, authoritative: Boolean) {
        val remote = readRemote(ContentSurface.LIBRARY_LIKED, admission).filter { it.ref.kind == ContentKind.SONG }
        if (remote.isEmpty() && !authoritative) return
        val local = readLocalLiked()
        fence(admission)
        val remoteIds = remote.mapTo(hashSetOf()) { it.ref.id }
        if (authoritative) {
            local.filter { it.ref.isYouTube() && it.ref.kind == ContentKind.SONG && it.ref.id !in remoteIds }
                .forEach { library.mutate(LibraryMutation.Like(it.ref, liked = false)) }
        }
        val likedHere = local.mapTo(hashSetOf()) { it.ref }
        // The provider lists the newest like first; liking the oldest first keeps that order in the local like order.
        library.likeAll(remote.filterNot { it.ref in likedHere }.reversed())
    }

    private suspend fun syncLikedAlbums(admission: AccountAdmission, authoritative: Boolean) {
        val remote = readRemote(ContentSurface.LIBRARY_ALBUMS, admission).filter { it.ref.kind == ContentKind.ALBUM }
        if (remote.isEmpty() && !authoritative) return
        val local = library.observeLikedAlbums(LibrarySortState(AlbumSortType.NAME, false), naturalOrder(), flowOf(emptySet()))
            .first().orEmpty().map { it.album }
        fence(admission)
        val remoteIds = remote.mapTo(hashSetOf()) { it.ref.id }
        local.filter { it.ref.isYouTube() && it.ref.id !in remoteIds }
            .forEach { library.mutate(LibraryMutation.LikeAlbum(it, liked = false)) }
        val likedHere = local.mapTo(hashSetOf()) { it.ref }
        remote.filterNot { it.ref in likedHere }.reversed()
            .forEach { library.mutate(LibraryMutation.LikeAlbum(it, liked = true)) }
    }

    private suspend fun syncArtists(admission: AccountAdmission, authoritative: Boolean) {
        val remote = readRemote(ContentSurface.LIBRARY_ARTISTS, admission).filter { it.ref.kind == ContentKind.ARTIST }
        if (remote.isEmpty() && !authoritative) return
        val local = library.observeLikedArtists(LibrarySortState(ArtistSortType.NAME, false), naturalOrder(), flowOf(emptySet()))
            .first().orEmpty().map { it.artist }
        fence(admission)
        val remoteIds = remote.mapTo(hashSetOf()) { it.ref.id }
        local.filter { it.ref.isYouTube() && it.ref.id !in remoteIds }
            .forEach { library.mutate(LibraryMutation.SubscribeArtist(it, subscribed = false)) }
        val subscribedHere = local.mapTo(hashSetOf()) { it.ref }
        remote.filterNot { it.ref in subscribedHere }
            .forEach { library.mutate(LibraryMutation.SubscribeArtist(it, subscribed = true)) }
    }

    /** The whole listing, or a failure. The repository bounds each page read and refuses repeated tokens. */
    private suspend fun readRemote(surface: ContentSurface, admission: AccountAdmission): List<ContentItem> {
        val request = ContentRequest(ContentProvider.YOUTUBE, surface)
        val items = mutableListOf<ContentItem>()
        var continuation: String? = null
        var pages = 0
        do {
            if (++pages > MAX_LISTING_PAGES) throw IllegalStateException("Listing exceeded its page bound")
            val page = content.page(request, continuation)
            items += page.items.map { it.item }
            continuation = page.continuation
        } while (continuation != null)
        fence(admission)
        return items.distinctBy { it.ref }
    }

    private suspend fun readLocalLiked(): List<ContentItem> {
        val query = ContentQuery(RoomLibraryRepository.LIKED)
        val items = mutableListOf<ContentItem>()
        var continuation: String? = null
        do {
            val page = library.page(query, continuation)
            items += page.items.map { it.item }
            continuation = page.continuation
        } while (continuation != null)
        return items
    }

    /** A listing read for one account is never applied after the account moved or sync was turned off. */
    private suspend fun fence(admission: AccountAdmission) {
        if (admitted() != admission) throw ContentAccountChanged()
    }

    private fun ContentRef.isYouTube(): Boolean =
        provider == ContentProvider.YOUTUBE.id

    private companion object {
        const val MAX_LISTING_PAGES = 400
    }
}


/** The pre-check a playlist menu reads before its sync row runs. */
enum class DesktopPlaylistSyncGate { READY, SYNC_DISABLED, NOT_SIGNED_IN }

/** The raw provider token of a wrapped continuation cursor; anything without the two separators is taken whole. */
private fun rawContinuationToken(wrapped: String): String =
    wrapped.split('|', limit = 3).let { if (it.size == 3) it[2] else wrapped }
