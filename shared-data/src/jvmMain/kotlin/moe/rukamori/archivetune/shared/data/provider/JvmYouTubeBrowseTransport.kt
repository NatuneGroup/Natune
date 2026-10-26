package moe.rukamori.archivetune.shared.data.provider

import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.Artist
import moe.rukamori.archivetune.innertube.models.ArtistItem
import moe.rukamori.archivetune.innertube.models.EpisodeItem
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.models.PodcastItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_OMV
import moe.rukamori.archivetune.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_UGC
import moe.rukamori.archivetune.innertube.models.YTItem
import moe.rukamori.archivetune.shared.domain.repository.ContentCredit
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentSearchFilter
import moe.rukamori.archivetune.shared.domain.repository.UnsupportedContentOperation

/**
 * The desktop binding of the typed YouTube browse boundary, over the same retained InnerTube client that the Android
 * transport uses. Failures propagate unchanged, so an account or network error reaches the queue owner instead of
 * turning into an empty page.
 *
 * The mapping mirrors the Android adapter, which lives in the app module and cannot be shared with this JVM source
 * set. Keep the two in step until the mapping moves to a source set that both platforms compile.
 */
class JvmYouTubeBrowseTransport : YouTubeBrowseTransport, YouTubeAccountTransport {
    override suspend fun page(browse: YouTubeBrowse, continuation: String?): YouTubeEntryPage =
        when (browse) {
            is YouTubeBrowse.Playlist -> playlistPage(browse.playlistId, continuation)
            is YouTubeBrowse.Album -> albumPage(browse.browseId, continuation)
            is YouTubeBrowse.Search -> searchPage(browse, continuation)
            is YouTubeBrowse.WatchNext -> watchNextPage(browse, continuation)
        }

    private suspend fun playlistPage(playlistId: String, continuation: String?): YouTubeEntryPage =
        if (continuation == null) {
            // The same first-page rule as the finite loader: the songs token takes precedence over the shelf token.
            val page = YouTube.playlist(playlistId).getOrThrow()
            YouTubeEntryPage(page.songs.map { it.toSongEntry() }, page.songsContinuation ?: page.continuation)
        } else {
            val page = YouTube.playlistContinuation(continuation, playlistId).getOrThrow()
            YouTubeEntryPage(page.songs.map { it.toSongEntry() }, page.continuation)
        }

    private suspend fun albumPage(browseId: String, continuation: String?): YouTubeEntryPage {
        // The album page carries its whole song list, so a token for it is foreign and is refused rather than ignored.
        if (continuation != null) throw UnsupportedContentOperation()
        val page = YouTube.album(browseId).getOrThrow()
        return YouTubeEntryPage(page.songs.map { it.toSongEntry() })
    }

    /**
     * The watch-next queue page of `YouTubeQueue.getInitialStatus`/`nextPage`: the same endpoint and the same
     * `next` request, page after page. The endpoint stays the seed the queue was opened with, as on Android.
     */
    private suspend fun watchNextPage(browse: YouTubeBrowse.WatchNext, continuation: String?): YouTubeEntryPage {
        val page =
            YouTube.next(
                endpoint =
                    WatchEndpoint(
                        videoId = browse.videoId,
                        playlistId = browse.playlistId,
                        playlistSetVideoId = browse.playlistSetVideoId,
                        params = browse.params,
                    ),
                continuation = continuation,
                followAutomixPreview = browse.followAutomixPreview,
            ).getOrThrow()
        return YouTubeEntryPage(page.items.map { it.toSongEntry() }, page.continuation)
    }

    private suspend fun searchPage(browse: YouTubeBrowse.Search, continuation: String?): YouTubeEntryPage {
        val filter = browse.filter.innerTubeFilter() ?: throw UnsupportedContentOperation()
        val result =
            if (continuation == null) {
                YouTube.search(browse.query, filter).getOrThrow()
            } else {
                YouTube.searchContinuation(continuation).getOrThrow()
            }
        return YouTubeEntryPage(result.items.map { it.toEntry() }, result.continuation)
    }

    override fun signedIn(): Boolean = YouTube.hasLoginCookie()

    /**
     * Points the retained core client at the persisted YouTube sign-in, as the Android App does on every preference
     * change, so library listings, likes and subscriptions act for that account. A null cookie leaves the client
     * signed out. PO token fields the client already holds are kept.
     */
    fun applySession(cookie: String?, visitorData: String?, dataSyncId: String?, useLoginForBrowse: Boolean) {
        YouTube.authState = YouTube.currentPlaybackAuthState().copy(
            cookie = cookie?.takeIf(String::isNotBlank),
            visitorData = visitorData?.takeIf(String::isNotBlank),
            dataSyncId = dataSyncId?.takeIf(String::isNotBlank),
        )
        YouTube.useLoginForBrowse = useLoginForBrowse
    }

    override suspend fun listing(listing: YouTubeListing, continuation: String?): YouTubeEntryPage {
        val browseId =
            when (listing) {
                YouTubeListing.LIKED_SONGS -> "FEmusic_liked_videos"
                YouTubeListing.LIKED_ALBUMS -> "FEmusic_liked_albums"
                YouTubeListing.SUBSCRIBED_ARTISTS -> "FEmusic_library_corpus_artists"
                YouTubeListing.LIBRARY_PLAYLISTS -> "FEmusic_liked_playlists"
            }
        return libraryPage(browseId, continuation)
    }

    override suspend fun history(continuation: String?): YouTubeEntryPage {
        // The history browse is one response with no further page, so a token for it is foreign and is refused.
        if (continuation != null) throw UnsupportedContentOperation()
        val page = YouTube.musicHistory().getOrThrow()
        return YouTubeEntryPage(page.sections.orEmpty().flatMap { section -> section.songs.map { it.toSongEntry() } })
    }

    override suspend fun likeSong(videoId: String, liked: Boolean) {
        YouTube.likeVideo(videoId, liked).getOrThrow()
    }

    override suspend fun likeAlbum(browseId: String, liked: Boolean) {
        // Only the album page carries the playlist id that the provider keys an album like on.
        val playlistId = YouTube.album(browseId, withSongs = false).getOrThrow().album.playlistId
        YouTube.likePlaylist(playlistId, liked).getOrThrow()
    }

    override suspend fun subscribeArtist(browseId: String, subscribed: Boolean) {
        // The artist page is read directly, because getChannelId() turns a failed lookup into an empty id.
        val channelId =
            YouTube.artist(browseId).getOrThrow().artist.channelId?.takeIf(String::isNotBlank)
                ?: throw IllegalStateException("The artist has no channel to subscribe to")
        YouTube.subscribeChannel(channelId, subscribed).getOrThrow()
    }

    private suspend fun libraryPage(browseId: String, continuation: String?): YouTubeEntryPage =
        if (continuation == null) {
            val page = YouTube.library(browseId).getOrThrow()
            YouTubeEntryPage(page.items.map { it.toEntry() }, page.continuation)
        } else {
            val page = YouTube.libraryContinuation(continuation).getOrThrow()
            YouTubeEntryPage(page.items.map { it.toEntry() }, page.continuation)
        }
}

/**
 * Only filters with a verified InnerTube parameter cross this boundary. ALL, PLAYLISTS, PODCASTS and EPISODES have
 * no verified parameter here, so they fail explicitly rather than searching a different surface.
 */
private fun ContentSearchFilter.innerTubeFilter(): YouTube.SearchFilter? =
    when (this) {
        ContentSearchFilter.SONGS -> YouTube.SearchFilter.FILTER_SONG
        ContentSearchFilter.VIDEOS -> YouTube.SearchFilter.FILTER_VIDEO
        ContentSearchFilter.ALBUMS -> YouTube.SearchFilter.FILTER_ALBUM
        ContentSearchFilter.ARTISTS -> YouTube.SearchFilter.FILTER_ARTIST
        ContentSearchFilter.FEATURED_PLAYLISTS -> YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST
        ContentSearchFilter.COMMUNITY_PLAYLISTS -> YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST
        ContentSearchFilter.ALL,
        ContentSearchFilter.PLAYLISTS,
        ContentSearchFilter.PODCASTS,
        ContentSearchFilter.EPISODES,
        -> null
    }

private fun SongItem.toSongEntry(): YouTubeEntry =
    YouTubeEntry(
        kind = if (isPodcast) ContentKind.EPISODE else ContentKind.SONG,
        id = id,
        title = title,
        subtitle = artists.joinToString(", ") { it.name },
        artworkUri = thumbnail,
        durationSeconds = duration,
        explicit = explicit,
        setVideoId = setVideoId,
        album = album?.name,
        isVideo =
            endpoint
                ?.watchEndpointMusicSupportedConfigs
                ?.watchEndpointMusicConfig
                ?.musicVideoType in setOf(MUSIC_VIDEO_TYPE_OMV, MUSIC_VIDEO_TYPE_UGC),
        artists = artists.toCredits(),
        albumId = album?.id?.takeIf(String::isNotBlank),
    )

/** Credits keep the browse id the provider gave. A credit without a name is dropped, never shown blank. */
private fun List<Artist>.toCredits(): List<ContentCredit> =
    mapNotNull { artist ->
        artist.name.takeIf(String::isNotBlank)?.let { ContentCredit(it, artist.id?.takeIf(String::isNotBlank)) }
    }

private fun YTItem.toEntry(): YouTubeEntry =
    when (this) {
        is SongItem -> toSongEntry()
        is AlbumItem ->
            YouTubeEntry(
                kind = ContentKind.ALBUM,
                id = browseId,
                title = title,
                subtitle = artists.orEmpty().joinToString(", ") { it.name },
                artworkUri = thumbnail,
                explicit = explicit,
                artists = artists.orEmpty().toCredits(),
            )
        is PlaylistItem ->
            YouTubeEntry(
                kind = ContentKind.PLAYLIST,
                id = id,
                title = title,
                subtitle = author?.name ?: songCountText.orEmpty(),
                artworkUri = thumbnail,
            )
        is PodcastItem ->
            YouTubeEntry(
                kind = ContentKind.PODCAST,
                id = browseId,
                title = title,
                subtitle = author?.name.orEmpty(),
                artworkUri = thumbnail,
            )
        is EpisodeItem ->
            YouTubeEntry(
                kind = ContentKind.EPISODE,
                id = id,
                title = title,
                subtitle = podcast?.name.orEmpty(),
                artworkUri = thumbnail,
                durationSeconds = duration,
            )
        is ArtistItem ->
            YouTubeEntry(
                kind = ContentKind.ARTIST,
                id = id,
                title = title,
                subtitle = subscriberCountText ?: monthlyListenerCountText.orEmpty(),
                artworkUri = thumbnail,
            )
    }
