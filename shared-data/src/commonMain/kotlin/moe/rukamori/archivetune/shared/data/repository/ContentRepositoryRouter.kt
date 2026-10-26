/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.repository

import kotlinx.coroutines.CancellationException
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.playlistimport.PlaylistImportLibrary
import moe.rukamori.archivetune.shared.data.persistence.RoomLibraryRepository
import moe.rukamori.archivetune.shared.data.provider.YouTubeAccountBinding
import moe.rukamori.archivetune.shared.data.provider.YouTubeAccountTransport

private val youtubeProviderId = ContentProvider.YOUTUBE.id

private fun ContentRef.isYouTubeSong(): Boolean = provider == youtubeProviderId && kind == ContentKind.SONG

/** UI passes the one typed request; absent providers fail explicitly instead of empty fake pages. */
class ContentRepositoryRouter(repositories: List<TypedContentRepository>) {
    private val repositories = repositories.associateBy { it.provider }
    init { require(this.repositories.size == repositories.size) }
    suspend fun page(request: ContentRequest, continuation: String? = null): ContentPage =
        repository(request.provider).page(request, continuation)
    suspend fun item(ref: ContentRef): ContentItem = repository(ContentProvider.fromId(ref.provider)).item(ref)
    private fun repository(provider: ContentProvider) = repositories[provider] ?: throw UnsupportedContentOperation()
}

/** Actual bridge for the four retained local surfaces; no schema/DTO or local mutation rewrite. */
class TypedRoomLibraryRepository(private val local: RoomLibraryRepository) :
    LibraryRepository by local, TypedContentRepository {
    override val provider = ContentProvider.LOCAL
    override suspend fun page(query: ContentQuery, continuation: String?): ContentPage =
        page(ContentRequest.fromLegacy(query, provider), continuation)
    override suspend fun page(request: ContentRequest, continuation: String?): ContentPage {
        require(request.provider == provider)
        return local.page(request.legacyQuery(), continuation)
    }
}

/**
 * Settles a YouTube library change with the provider before the local library commits it.
 * A remote like, unlike, album bookmark or artist subscription is accepted first. A failed,
 * cancelled or account-fenced request throws and leaves the local library unchanged. Other
 * changes, and every change while sync is off or no YouTube sign-in is present, stay local.
 */
class YouTubeSettlingLibrary(
    private val local: PlaylistImportLibrary,
    private val account: YouTubeAccountBinding,
) : PlaylistImportLibrary by local {
    override suspend fun mutate(mutation: LibraryMutation) {
        when {
            mutation is LibraryMutation.Like && mutation.ref.isYouTubeSong() -> {
                // An unsaved song is refused here, so no remote like is sent for it.
                val saved = local.findSong(mutation.ref.id, mutation.ref.provider)
                requireNotNull(saved) { "Content has not been saved" }
                settle({ it.likeSong(mutation.ref.id, mutation.liked) }) { local.mutate(mutation) }
            }
            mutation is LibraryMutation.LikeAlbum && mutation.album.ref.provider == youtubeProviderId ->
                settle({ it.likeAlbum(mutation.album.ref.id, mutation.liked) }) { local.mutate(mutation) }
            mutation is LibraryMutation.SubscribeArtist && mutation.artist.ref.provider == youtubeProviderId -> {
                settle(remote = { it.subscribeArtist(mutation.artist.ref.id, mutation.subscribed) }) {
                    local.mutate(mutation)
                }
            }
            else -> local.mutate(mutation)
        }
    }

    /**
     * Likes a batch the way the baseline bulk like does: one provider request per YouTube song, and a song
     * the provider refuses stays unliked. The accepted songs and every other item commit together. A refusal
     * is then thrown with the refused ids, so the caller reports a shortfall instead of a success. Remote
     * likes already accepted are not rolled back, matching the per-song outcome of the baseline. An account
     * move stops the batch before the local commit.
     */
    override suspend fun likeAll(items: List<ContentItem>) {
        val songIds = items.filter { it.ref.isYouTubeSong() }.map { it.ref.id }.distinct()
        if (songIds.isEmpty()) {
            local.likeAll(items)
            return
        }
        val admission = account.remoteAccount()
        if (admission == null) {
            local.likeAll(items)
            return
        }
        val refused = linkedSetOf<String>()
        for (songId in songIds) {
            try {
                account.transport.likeSong(songId, liked = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refusal: Exception) {
                refused += songId
            }
            if (account.remoteAccount() != admission) throw ContentAccountChanged()
        }
        local.likeAll(items.filterNot { it.ref.isYouTubeSong() && it.ref.id in refused })
        if (refused.isNotEmpty()) throw ContentLikePartialFailure(refused)
    }

    /**
     * Sends the remote change for the admitted account, then commits it locally. The local commit is
     * withheld when that account moved during the remote change, since the change belongs to it.
     */
    private suspend fun settle(remote: suspend (YouTubeAccountTransport) -> Unit, commit: suspend () -> Unit) {
        val admission = account.remoteAccount()
        if (admission == null) {
            commit()
            return
        }
        remote(account.transport)
        if (account.remoteAccount() != admission) throw ContentAccountChanged()
        commit()
    }
}
