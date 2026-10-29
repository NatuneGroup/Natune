package moe.rukamori.archivetune.shared.domain.repository

import kotlinx.coroutines.flow.Flow

enum class ContentKind { SONG, ALBUM, ARTIST, PLAYLIST, PODCAST, EPISODE }

data class ContentRef(val provider: String, val kind: ContentKind, val id: String) {
    init { require(provider.isNotBlank() && id.isNotBlank()) }
}

/**
 * One artist credit of an item. [id] is the provider's artist id, or null when the provider did not give one. An
 * unidentified credit can still be shown, but it cannot be blocked, navigated or counted as a known artist.
 */
data class ContentCredit(val name: String, val id: String? = null) {
    init {
        require(name.isNotBlank())
        require(id == null || id.isNotBlank())
    }

    /** The artist's reference under [provider], or null when the provider did not identify this credit. */
    fun ref(provider: String): ContentRef? = id?.let { ContentRef(provider, ContentKind.ARTIST, it) }
}

/**
 * [album] is optional and trailing so every positional provider constructor stays source-compatible. [artists] and
 * [albumId] are the provider identities of the credits and of the album. They default to unknown, so no identity is
 * ever inferred from a display name.
 */
data class ContentItem(
    val ref: ContentRef,
    val title: String,
    val subtitle: String = "",
    val artworkUri: String? = null,
    val durationMs: Long? = null,
    val explicit: Boolean = false,
    val album: String? = null,
    val artists: List<ContentCredit> = emptyList(),
    val albumId: String? = null,
    /** Android's `ExtraIsMusicVideo` extra: the provider marked this entry a music video, so Hide Video drops it. */
    val isVideo: Boolean = false,
    /** A display projection retains its provider names so refresh, persistence and audio lookup stay canonical. */
    val canonicalSongMetadata: moe.rukamori.archivetune.shared.domain.songmetadata.CanonicalSongMetadata? = null,
) {
    init {
        require(albumId == null || albumId.isNotBlank())
    }

    /** The album's reference under this item's provider, or null when the album has no provider id. */
    fun albumRef(): ContentRef? = albumId?.let { ContentRef(ref.provider, ContentKind.ALBUM, it) }
}

/** Occurrence keys belong to the collection, never to the content id. */
data class ContentOccurrence(val occurrenceId: String, val item: ContentItem, val setVideoId: String? = null)
data class ContentPage(val items: List<ContentOccurrence>, val continuation: String? = null)
data class ContentQuery(val surface: String, val text: String? = null, val parent: ContentRef? = null)

interface ContentRepository {
    suspend fun page(query: ContentQuery, continuation: String? = null): ContentPage
    suspend fun item(ref: ContentRef): ContentItem
}

sealed interface LibraryMutation {
    data class Like(val ref: ContentRef, val liked: Boolean) : LibraryMutation

    /**
     * Likes or unlikes an album, as its provider gave it. A liked album is listed from that record, so it needs no
     * saved song, as the baseline's liked albums did not.
     */
    data class LikeAlbum(val album: ContentItem, val liked: Boolean) : LibraryMutation {
        init { require(album.ref.kind == ContentKind.ALBUM) { "Only an album can be liked as an album" } }
    }

    /**
     * Subscribes to or unsubscribes from an artist, as its provider gave it. The liked artists list it from that
     * record, without any saved song.
     */
    data class SubscribeArtist(val artist: ContentItem, val subscribed: Boolean) : LibraryMutation {
        init { require(artist.ref.kind == ContentKind.ARTIST) { "Only an artist can be subscribed to" } }
    }

    data class AddToPlaylist(val playlistId: String, val occurrences: List<ContentOccurrence>) : LibraryMutation
    data class RemoveFromPlaylist(val playlistId: String, val occurrenceIds: Set<String>) : LibraryMutation
    data class RenamePlaylist(val playlistId: String, val title: String) : LibraryMutation
    data class SetPlaylistTags(val playlistId: String, val tags: Set<String>) : LibraryMutation

    /**
     * Places one occurrence beside another in the playlist's custom order, below the neighbour when [after] is true.
     * The store refuses it unless the playlist is in custom order, so a sorted list never rewrites the custom order.
     */
    data class MoveInPlaylist(
        val playlistId: String,
        val occurrenceId: String,
        val neighbourOccurrenceId: String,
        val after: Boolean,
    ) : LibraryMutation
}

interface LibraryRepository : ContentRepository {
    fun observe(query: ContentQuery): Flow<ContentPage>
    /** Completion means the mutation was committed, not merely scheduled. Failures throw. */
    suspend fun mutate(mutation: LibraryMutation)
}

/**
 * A remote account request needs a signed-in account with sync on. It is refused rather than answered with an empty
 * list or a local-only change, so a sign-out never reads as an empty account.
 */
class ContentAccountRequired : IllegalStateException("A signed-in account with sync enabled is required")

/** The admitted account moved while the request ran, so its result is never applied to the new account. */
class ContentAccountChanged : IllegalStateException("The admitted account changed during the request")

/**
 * A bulk like where the provider refused some songs. The accepted songs are already committed locally, and
 * [failedSongIds] names the refused ones, so a caller reports the shortfall instead of a success.
 */
class ContentLikePartialFailure(val failedSongIds: Set<String>) :
    IllegalStateException("Some songs could not be liked on YouTube Music")
