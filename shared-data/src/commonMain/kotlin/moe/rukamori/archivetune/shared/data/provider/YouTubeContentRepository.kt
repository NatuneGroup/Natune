package moe.rukamori.archivetune.shared.data.provider

import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.shared.domain.platform.AcceptedAccountReader
import moe.rukamori.archivetune.shared.domain.platform.AccountAdmission
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistCompletion
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistIds
import moe.rukamori.archivetune.shared.domain.queue.IncompleteFinitePlaylistException
import moe.rukamori.archivetune.shared.domain.repository.ContentAccountChanged
import moe.rukamori.archivetune.shared.domain.repository.ContentAccountRequired
import moe.rukamori.archivetune.shared.domain.repository.ContentCredit
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentPage
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSearchFilter
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface
import moe.rukamori.archivetune.shared.domain.repository.TypedContentRepository
import moe.rukamori.archivetune.shared.domain.repository.UnsupportedContentOperation

/** A browse request in the provider's own vocabulary. The transport never sees a [ContentRequest]. */
sealed interface YouTubeBrowse {
    /** A finite playlist's songs in order. [playlistId] is normalized and never a radio (RD) id. */
    data class Playlist(val playlistId: String) : YouTubeBrowse

    data class Album(val browseId: String) : YouTubeBrowse

    data class Search(val query: String, val filter: ContentSearchFilter) : YouTubeBrowse

    /**
     * A generic provider browse section: home shelf "see all", charts, a mood or genre page, a browse link
     * carrying a browse id. The provider answers whole sections with no continuation, so a token is refused
     * inside the transport rather than ignored.
     */
    data class Section(val browseId: String, val params: String?) : YouTubeBrowse

    /** An artist page section opened through its "see all" endpoint; it pages through the artist-items endpoint. */
    data class ArtistItems(val browseId: String, val params: String?) : YouTubeBrowse

    /**
     * A watch-next collection (the play, shuffle or radio endpoint on an artist page). [videoId] is the song
     * the endpoint names and [playlistId] the radio id when it gave one; the queue keeps paging it as a
     * RADIO collection because the provider owns its continuation.
     */
    data class Watch(val videoId: String?, val playlistId: String?, val params: String?) : YouTubeBrowse

    /**
     * A watch-next queue page: radio, mixes and song-seeded watch queues, the InnerTube `next` endpoint of
     * `YouTubeQueue`. A song seed follows the automix preview; an RD/mix playlist seed does not.
     */
    data class WatchNext(
        val videoId: String? = null,
        val playlistId: String? = null,
        val playlistSetVideoId: String? = null,
        val params: String? = null,
        val followAutomixPreview: Boolean = false,
    ) : YouTubeBrowse {
        init { require(videoId != null || playlistId != null) { "Watch-next needs a seed" } }
    }
}

/** One provider entry reduced to common fields. [setVideoId] is the playlist entry id when the provider has one. */
data class YouTubeEntry(
    val kind: ContentKind,
    val id: String,
    val title: String,
    val subtitle: String = "",
    val artworkUri: String? = null,
    val durationSeconds: Int? = null,
    val explicit: Boolean = false,
    val setVideoId: String? = null,
    val album: String? = null,
    /** The artist credits and album id the provider gave. They stay empty when the provider omitted them. */
    val artists: List<ContentCredit> = emptyList(),
    val albumId: String? = null,
    /** The endpoint's own music-video marker (OMV/UGC); Android stores it as `ExtraIsMusicVideo`. */
    val isVideo: Boolean = false,
)

/** One provider page. [continuation] is the provider's raw token; the repository wraps it before it leaves. */
data class YouTubeEntryPage(
    val entries: List<YouTubeEntry>,
    val continuation: String? = null,
)

/**
 * The InnerTube boundary. Adapters map provider models to [YouTubeEntry] and throw when a request fails. An empty
 * page is never a substitute for a failed or unsupported request.
 */
interface YouTubeBrowseTransport {
    suspend fun page(browse: YouTubeBrowse, continuation: String?): YouTubeEntryPage
}

/**
 * The signed-in library listings. Each one maps to its verified browse inside the transport, not here. [kind] is the
 * one kind a listing holds. A provider page can mix kinds, so an entry of another kind is left out of the listing
 * rather than shown under it.
 */
enum class YouTubeListing(val kind: ContentKind) {
    LIKED_SONGS(ContentKind.SONG),
    LIKED_ALBUMS(ContentKind.ALBUM),
    SUBSCRIBED_ARTISTS(ContentKind.ARTIST),
    LIBRARY_PLAYLISTS(ContentKind.PLAYLIST),
}

/**
 * The signed-in account boundary. Each call acts for the account that [YouTubeAccountBinding] admitted. A failed
 * request throws, so an empty result or a local change is never reported as the outcome of a remote request.
 */
interface YouTubeAccountTransport {
    /** Whether the client holds a YouTube sign-in. A signed-out client sends only anonymous requests. */
    fun signedIn(): Boolean

    /** One page of a listing. A null [continuation] asks for the first page. */
    suspend fun listing(listing: YouTubeListing, continuation: String?): YouTubeEntryPage

    /**
     * One page of the account history. A null [continuation] asks for the first response. The core client's history
     * browse returns one response with no continuation, so this is the recent history, not a complete archive.
     */
    suspend fun history(continuation: String?): YouTubeEntryPage

    suspend fun likeSong(videoId: String, liked: Boolean)

    /** The like is keyed on the album's own playlist id, which the album page gives. */
    suspend fun likeAlbum(browseId: String, liked: Boolean)

    suspend fun subscribeArtist(browseId: String, subscribed: Boolean)
}

/**
 * Names the account that remote YouTube work acts for. Sync off, or no YouTube sign-in, is local-only and yields
 * null. Sync on and signed in with no accepted account to name is refused, because a remote change must never land
 * on an account the store cannot identify.
 *
 * [personalScope] is the store's opaque scope for the personal YouTube sign-in. [syncEnabled] is the user's sync
 * choice, which the root reads with the baseline default of true.
 */
class YouTubeAccountBinding(
    val transport: YouTubeAccountTransport,
    private val accounts: AcceptedAccountReader,
    private val personalScope: suspend () -> String?,
    private val syncEnabled: suspend () -> Boolean,
) {
    suspend fun remoteAccount(): AccountAdmission? {
        if (!syncEnabled() || !transport.signedIn()) return null
        val scope = personalScope() ?: throw ContentAccountRequired()
        return accounts.admit(ContentProvider.YOUTUBE.id, scope) ?: throw ContentAccountRequired()
    }
}

/**
 * Typed YouTube content for the portable host.
 *
 * Playlist occurrences use the Android finite-queue keys (`<playlist>:set:<setVideoId>`, or
 * `<playlist>:ordinal:<n>` when the entry has no set id), so a saved queue and a remote removal name the same entry.
 * A continuation is wrapped with the request that issued it and with the number of entries already delivered, so a
 * token from another request is refused and ordinal keys stay unique across pages without stored state.
 *
 * The signed-in library listings and the account history read through [YouTubeAccountBinding]. They need a sign-in
 * with sync on. A continuation is bound to the admitted account generation, so an earlier account's token is refused.
 * A listing page follows the provider's continuation past a page with no entry of the listing's kind, within the
 * finite completion bounds. A token that repeats, or a page that ends with neither entries nor a continuation, is an
 * incomplete listing and never a complete one.
 *
 * Surfaces outside finite playlists, albums, search, radio and the account (home, artist sections, item lookup) fail
 * with [UnsupportedContentOperation] until their transports are published.
 */
class YouTubeContentRepository(
    private val transport: YouTubeBrowseTransport,
    private val account: YouTubeAccountBinding? = null,
) : TypedContentRepository {
    override val provider: ContentProvider = ContentProvider.YOUTUBE

    override suspend fun page(request: ContentRequest, continuation: String?): ContentPage {
        require(request.provider == provider) { "Foreign content request" }
        if (request.surface == ContentSurface.HISTORY || request.surface.toListing() != null) {
            return accountPage(request, continuation)
        }
        val browse = request.toBrowse()
        val key = browse.requestKey()
        val wrapped =
            if (continuation == null) {
                null
            } else {
                YouTubeContinuation.decode(continuation, key) ?: throw IllegalArgumentException("Foreign continuation")
            }
        val offset = wrapped?.offset ?: 0
        val raw = transport.page(browse, wrapped?.token)
        val collection = browse.collectionKey()
        val items = raw.entries.mapIndexed { index, entry -> entry.occurrence(collection, offset + index) }
        val next = raw.continuation?.let { YouTubeContinuation(key, offset + raw.entries.size, it).encode() }
        return ContentPage(items, next)
    }

    /** Single-item lookup needs a transport that is not published yet; the explicit refusal keeps callers honest. */
    override suspend fun item(ref: ContentRef): ContentItem = throw UnsupportedContentOperation()

    /**
     * Account reads act for the admitted account only. The admission is taken before the read and compared again after
     * every provider page, so a page read for an earlier account is never returned. The continuation key carries the
     * account generation, so a token from one account is refused once the account has moved on.
     */
    private suspend fun accountPage(request: ContentRequest, continuation: String?): ContentPage {
        val binding = account ?: throw UnsupportedContentOperation()
        if (request.section != null || request.parent != null || request.text != null) {
            throw UnsupportedContentOperation()
        }
        val listing = request.surface.toListing()
        val admission = binding.remoteAccount() ?: throw ContentAccountRequired()
        val key = "account:${admission.accepted.generation}:${request.surface.id}"
        val cursor =
            continuation?.let { wrapped ->
                YouTubeContinuation.decode(wrapped, key) ?: throw foreignCursor(wrapped, key, request.surface)
            }
        // The history response is one page with no continuation, so no history token can have been issued.
        if (listing == null && cursor != null) throw IllegalArgumentException("Foreign continuation")
        return withTimeoutOrNull(FinitePlaylistCompletion.TIMEOUT_MS) {
            readAccountPage(binding, admission, key, listing, cursor)
        } ?: throw IncompleteFinitePlaylistException("Account listing timed out")
    }

    /**
     * Reads one account page. A provider page with no entry of the listing's kind is skipped while the provider still
     * names a continuation, so a filtered page never ends the listing early. The skip is bounded by the finite request
     * bound. A token that repeats one already consumed in this read is refused, and so is a page after the first that
     * carries neither entries nor a continuation. Both are incomplete listings, never complete ones.
     */
    private suspend fun readAccountPage(
        binding: YouTubeAccountBinding,
        admission: AccountAdmission,
        key: String,
        listing: YouTubeListing?,
        cursor: YouTubeContinuation?,
    ): ContentPage {
        val offset = cursor?.offset ?: 0
        val consumed = mutableSetOf<String>()
        var token = cursor?.token
        var requests = 0
        while (true) {
            requests += 1
            if (requests > FinitePlaylistCompletion.MAX_REQUESTS) {
                throw IncompleteFinitePlaylistException("Account listing needed more pages than its bound")
            }
            token?.let { current ->
                if (!consumed.add(current)) throw IncompleteFinitePlaylistException("Account listing repeated a page")
            }
            val raw =
                if (listing != null) binding.transport.listing(listing, token) else binding.transport.history(token)
            if (binding.remoteAccount() != admission) throw ContentAccountChanged()
            FinitePlaylistCompletion.validateNext(raw.continuation, consumed, raw.entries.size, initial = token == null)
            val kept = if (listing == null) raw.entries else raw.entries.filter { entry -> entry.kind == listing.kind }
            if (kept.isNotEmpty() || raw.continuation == null) {
                val items = kept.mapIndexed { index, entry -> entry.occurrence(key, offset + index) }
                val next = raw.continuation?.let { YouTubeContinuation(key, offset + kept.size, it).encode() }
                return ContentPage(items, next)
            }
            token = raw.continuation
        }
    }

    /**
     * A token for this surface that an earlier account generation issued names an account that has moved on, and is
     * reported as [ContentAccountChanged]. Any other token that does not decode is a foreign continuation.
     */
    private fun foreignCursor(wrapped: String, admittedKey: String, surface: ContentSurface): RuntimeException {
        val tokenKey = wrapped.substringBefore('|')
        val parts = tokenKey.split(':')
        val sameSurface = parts.size == 3 && parts[0] == "account" && parts[2] == surface.id
        val movedAccount = sameSurface && tokenKey != admittedKey
        return if (movedAccount) ContentAccountChanged() else IllegalArgumentException("Foreign continuation")
    }
}

/** A raw provider token bound to its request and to the entries already delivered before it. */
internal data class YouTubeContinuation(val requestKey: String, val offset: Int, val token: String) {
    init {
        require(offset >= 0 && token.isNotBlank()) { "Invalid continuation" }
    }

    /** `<escaped request key>|<offset>|<token>`; the token may contain '|' because only the first two split. */
    fun encode(): String = "${requestKey.escapeField()}|$offset|$token"

    companion object {
        fun decode(wrapped: String, requestKey: String): YouTubeContinuation? {
            val parts = wrapped.split('|', limit = 3)
            if (parts.size != 3 || parts[0] != requestKey.escapeField()) return null
            val offset = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val token = parts[2].takeIf { it.isNotBlank() } ?: return null
            return YouTubeContinuation(requestKey, offset, token)
        }
    }
}

private fun String.escapeField(): String = replace("%", "%25").replace("|", "%7C")

private fun ContentRequest.toBrowse(): YouTubeBrowse {
    val section = section
    // A song-seeded watch names its video id in the request text; blank text names none.
    val textSeed = text?.trim()?.takeIf(String::isNotEmpty)
    if (section != null && surface != ContentSurface.RADIO) {
        val playlistId = section.id.takeIf(String::isNotBlank)
        // Radio and mix ids are endless watch queues. The video id is the request text when the endpoint named a
        // song, else the one encoded in the RDAMVM playlist id.
        if (section.id.startsWith("RDAMVM")) {
            val videoId = section.id.removePrefix("RDAMVM").takeIf(String::isNotBlank)
            return YouTubeBrowse.Watch(videoId, playlistId, section.params)
        }
        if (surface == ContentSurface.ARTIST) {
            return if (textSeed != null || playlistId?.startsWith("RD") == true) {
                YouTubeBrowse.Watch(textSeed, playlistId, section.params)
            } else {
                YouTubeBrowse.ArtistItems(section.id, section.params)
            }
        }
        if (playlistId?.startsWith("RD") == true) return YouTubeBrowse.Watch(null, playlistId, section.params)
        return YouTubeBrowse.Section(section.id, section.params)
    }
    if (surface == ContentSurface.ARTIST && textSeed != null) {
        // A video-id-only watch endpoint, e.g. playing one provider song without a radio id.
        return YouTubeBrowse.Watch(textSeed, null, null)
    }
    return when (surface) {
        ContentSurface.PLAYLIST -> {
            val playlist = parent?.takeIf { it.kind == ContentKind.PLAYLIST } ?: throw UnsupportedContentOperation()
            val normalized = FinitePlaylistIds.normalize(playlist.id)
            // Radio and mix ids are endless and need the watch-next transport, not a finite playlist browse.
            if (!FinitePlaylistIds.isFinite(normalized)) throw UnsupportedContentOperation()
            YouTubeBrowse.Playlist(normalized)
        }
        ContentSurface.ALBUM -> {
            val album = parent?.takeIf { it.kind == ContentKind.ALBUM } ?: throw UnsupportedContentOperation()
            YouTubeBrowse.Album(album.id)
        }
        ContentSurface.SEARCH -> {
            val query = text?.trim()?.takeIf(String::isNotEmpty) ?: throw IllegalArgumentException("Blank search")
            YouTubeBrowse.Search(query, filter)
        }
        ContentSurface.RADIO -> {
            val seed = parent ?: throw UnsupportedContentOperation()
            YouTubeBrowse.WatchNext(
                videoId = seed.id.takeIf { seed.kind == ContentKind.SONG },
                playlistId = seed.id.takeIf { seed.kind == ContentKind.PLAYLIST },
                playlistSetVideoId = section?.id?.takeIf { it != seed.id },
                params = section?.params,
                // A song seed follows the automix preview; a playlist/mix endpoint does not, as on Android.
                followAutomixPreview = seed.kind == ContentKind.SONG,
            )
        }
        else -> throw UnsupportedContentOperation()
    }
}

/** The signed-in library listings the account exposes. Every other surface is not an account listing. */
private fun ContentSurface.toListing(): YouTubeListing? =
    when (this) {
        ContentSurface.LIBRARY_LIKED -> YouTubeListing.LIKED_SONGS
        ContentSurface.LIBRARY_ALBUMS -> YouTubeListing.LIKED_ALBUMS
        ContentSurface.LIBRARY_ARTISTS -> YouTubeListing.SUBSCRIBED_ARTISTS
        ContentSurface.LIBRARY_PLAYLISTS -> YouTubeListing.LIBRARY_PLAYLISTS
        else -> null
    }

private fun YouTubeBrowse.requestKey(): String =
    when (this) {
        is YouTubeBrowse.Playlist -> "playlist:$playlistId"
        is YouTubeBrowse.Album -> "album:$browseId"
        is YouTubeBrowse.Search -> "search:${filter.name}:$query"
        is YouTubeBrowse.Section -> "section:$browseId:$params"
        is YouTubeBrowse.ArtistItems -> "artist-items:$browseId:$params"
        is YouTubeBrowse.Watch -> "watch:$videoId:$playlistId:$params"
        is YouTubeBrowse.WatchNext ->
            "radio:${videoId.orEmpty()}:${playlistId.orEmpty()}:${playlistSetVideoId.orEmpty()}:${params.orEmpty()}"
        is YouTubeBrowse.Section -> "section:$browseId:$params"
        is YouTubeBrowse.ArtistItems -> "artist-items:$browseId:$params"
        is YouTubeBrowse.Watch -> "watch:$videoId:$playlistId:$params"
    }

private fun YouTubeBrowse.collectionKey(): String =
    when (this) {
        is YouTubeBrowse.Playlist -> playlistId
        else -> requestKey()
    }

private fun YouTubeEntry.occurrence(collection: String, ordinal: Int): ContentOccurrence {
    val setId = setVideoId?.takeIf(String::isNotBlank)
    return ContentOccurrence(
        occurrenceId = if (setId != null) "$collection:set:$setId" else "$collection:ordinal:$ordinal",
        item =
            ContentItem(
                ref = ContentRef(ContentProvider.YOUTUBE.id, kind, id),
                title = title,
                subtitle = subtitle,
                artworkUri = artworkUri,
                durationMs = durationSeconds?.let { it * 1000L },
                explicit = explicit,
                album = album,
                artists = artists,
                albumId = albumId?.takeIf(String::isNotBlank),
                isVideo = isVideo,
            ),
        setVideoId = setId,
    )
}
