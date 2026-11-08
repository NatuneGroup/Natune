package moe.rukamori.archivetune.shared.ui.navigation

import kotlin.io.encoding.Base64
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.ui.screens.artistalbum.ArtistShelfLayout

enum class ArchiveDestination {
    HOME, SEARCH, SEARCH_RESULTS, LIBRARY, DISCOVERY, BROWSE, YOUTUBE_BROWSE, MOODS, CHARTS, RELEASES,
    NEWS, ARTICLE, PODCASTS, PODCAST, LOCAL_SONGS, DOWNLOADS, HISTORY, PLAYLISTS, TAGS,
    ALBUM, ARTIST, PLAYLIST, SETTINGS, ACCOUNT, LOGIN, STATISTICS, RECAP, LISTEN_TOGETHER, LISTEN_TOGETHER_CHAT,
    TELEGRAM, TELEGRAM_BOTS, TELEGRAM_BROWSE, TELEGRAM_BOT_CHAT,
    MUSIC_RECOGNITION, MUSIC_RECOGNITION_DETAILS, ARTIST_SONGS, ARTIST_ALBUMS, ARTIST_ITEMS,
    TRACK_INFO, MEDIA_INFO, UPLOADED_SONGS,
}

/** A decoded view of the original route, not a replacement persisted content/queue DTO. */
data class ArchiveRoute(val value: String, val destination: ArchiveDestination, val arguments: Map<String, String>) {
    val searchQuery: String? get() = if (destination == ArchiveDestination.SEARCH_RESULTS)
        ArchiveRoutes.decodeSearch(arguments.getValue("encodedQuery")) else null

    fun contentRequest(): ContentRequest {
        val provider = arguments["provider"]?.let { id ->
            when (id) {
                "YOUTUBE" -> ContentProvider.YOUTUBE
                "SPOTIFY" -> ContentProvider.SPOTIFY_CATALOG
                else -> ContentProvider.fromId(id)
            }
        } ?: ContentProvider.YOUTUBE
        if (destination == ArchiveDestination.SEARCH_RESULTS) return ContentRequest(provider, ContentSurface.SEARCH, searchQuery,
            filter = arguments["filter"]?.let(ContentSearchFilter::valueOf) ?: ContentSearchFilter.ALL)
        require(destination in setOf(ArchiveDestination.BROWSE, ArchiveDestination.YOUTUBE_BROWSE, ArchiveDestination.ARTIST_ITEMS))
        val parent = arguments["parentId"]?.let { ContentRef(provider.id, ContentKind.valueOf(arguments.getValue("parentKind")), it) }
        return ContentRequest(provider, arguments["surface"]?.let(ContentSurface::fromId) ?: ContentSurface.EXPLORE,
            arguments["text"], parent, ContentSection(arguments.getValue("browseId"), arguments["params"]),
            arguments["filter"]?.let(ContentSearchFilter::valueOf) ?: ContentSearchFilter.ALL)
    }

    /** The title and layout of a complete artist section travel with its route, so a restored entry renders the same. */
    val artistItemsTitle: String get() = arguments["title"].orEmpty()
    val artistItemsLayout: ArtistShelfLayout get() = arguments["layout"]?.let(ArtistShelfLayout::valueOf) ?: ArtistShelfLayout.LIST
}

/** Only route arguments are encoded. Provider IDs, saved playlist IDs and existing path meanings stay intact. */
object ArchiveRoutes {
    /**
     * The Last.fm dashboard. Every host mounts it as a settings page beside `settings/lastfm`
     * rather than as its own destination, so the value lives next to the resolver: the settings
     * hosts read it to mount the page and the shared account menu reads it to open the page, so
     * neither can drift from the other.
     */
    const val LASTFM_DASHBOARD_ROUTE = "settings/lastfm/dashboard"

    fun article(id: String): String = "view_news/${encode(id)}"
    fun search(query: String, provider: ContentProvider, filter: ContentSearchFilter = ContentSearchFilter.ALL): String {
        val encoded = if (query.isEmpty()) "~" else Base64.UrlSafe.encode(query.encodeToByteArray()).trimEnd('=')
        val legacyProvider = when (provider) {
            ContentProvider.YOUTUBE -> "YOUTUBE"
            ContentProvider.SPOTIFY_CATALOG -> "SPOTIFY"
            else -> provider.id
        }
        return "search/$encoded?provider=$legacyProvider" + if (filter == ContentSearchFilter.ALL) "" else "&filter=${filter.name}"
    }

    fun browse(request: ContentRequest): String {
        val section = requireNotNull(request.section) { "Browse navigation requires the provider's actual section handle" }
        val args = linkedMapOf("provider" to request.provider.id, "surface" to request.surface.id)
        section.params?.let { args["params"] = it }
        request.text?.let { args["text"] = it }
        request.parent?.let { args["parentId"] = it.id; args["parentKind"] = it.kind.name }
        if (request.filter != ContentSearchFilter.ALL) args["filter"] = request.filter.name
        return "youtube_browse/${encode(section.id)}?" + args.entries.joinToString("&") { "${it.key}=${encode(it.value)}" }
    }

    fun content(ref: ContentRef): String {
        val path = when (ref.kind) {
            ContentKind.ALBUM -> "album"
            ContentKind.ARTIST -> "artist"
            ContentKind.PODCAST -> "podcast"
            ContentKind.PLAYLIST -> when (ref.provider) {
                "local", "telegram" -> "local_playlist"
                "spotify" -> "spotify_playlist"
                else -> "online_playlist"
            }
            ContentKind.SONG, ContentKind.EPISODE -> error("Playable occurrences are sent to the existing queue owner, not a detail route")
        }
        return "$path/${encode(ref.id)}?provider=${encode(ref.provider)}"
    }

    /**
     * A listen-again request travels in the route as a unique token, so each request starts exactly one capture,
     * including when the existing recognition entry is reused rather than stacked again.
     */
    fun musicRecognition(autoStartRequest: Long? = null): String =
        if (autoStartRequest == null) "music_recognition" else "music_recognition?autoStart=$autoStartRequest"

    fun isMusicRecognition(route: String): Boolean = route.substringBefore('?') == "music_recognition"

    /** The stable key is encoded as one segment, so a key containing '/' or '|' cannot split the route. */
    fun musicRecognitionDetails(stableKey: String): String = "music_recognition/details/${encode(stableKey)}"

    /** Artist sub-pages keep the owning provider in the query, as the artist page itself does. */
    fun artistSongs(artist: ContentRef): String = "artist/${encode(artist.id)}/songs?provider=${encode(artist.provider)}"
    fun artistAlbums(artist: ContentRef): String = "artist/${encode(artist.id)}/albums?provider=${encode(artist.provider)}"

    /** A complete provider section opened from an artist page. Its request is encoded like a browse route. */
    fun artistItems(request: ContentRequest, title: String, layout: ArtistShelfLayout): String {
        val section = requireNotNull(request.section) { "Artist section navigation requires the provider's actual section handle" }
        val args = linkedMapOf("provider" to request.provider.id, "surface" to request.surface.id,
            "title" to title, "layout" to layout.name)
        section.params?.let { args["params"] = it }
        request.text?.let { args["text"] = it }
        request.parent?.let { args["parentId"] = it.id; args["parentKind"] = it.kind.name }
        if (request.filter != ContentSearchFilter.ALL) args["filter"] = request.filter.name
        return "artist_items/${encode(section.id)}?" + args.entries.joinToString("&") { "${it.key}=${encode(it.value)}" }
    }

    /** The track-info page for one track. The id is one encoded segment, so a provider id cannot split the route. */
    fun trackInfo(trackId: String): String = "track_info/${encode(trackId)}"
    fun trackInfo(trackId: String, provider: String): String = trackInfo(trackId) + "?provider=${encode(provider)}"

    /** The media-info page for one media id, encoded like [trackInfo]. */
    fun mediaInfo(mediaId: String): String = "media_info/${encode(mediaId)}"
    fun mediaInfo(mediaId: String, provider: String): String = mediaInfo(mediaId) + "?provider=${encode(provider)}"

    fun resolve(value: String): ArchiveRoute? {
        val path = value.substringBefore('?').split('/').map(::decode)
        val args = value.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
            decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
        }.toMutableMap()
        fun id(destination: ArchiveDestination, key: String): ArchiveDestination? =
            if (path.size == 2 && path[1].isNotBlank()) destination.also { args[key] = path[1] } else null
        val destination = when {
            path == listOf("home") -> ArchiveDestination.HOME
            path == listOf("search") -> ArchiveDestination.SEARCH
            path.firstOrNull() == "search" -> id(ArchiveDestination.SEARCH_RESULTS, "encodedQuery")
            path == listOf("library") -> ArchiveDestination.LIBRARY
            path == listOf("discovery") -> ArchiveDestination.DISCOVERY
            path == listOf("mood_and_genres") -> ArchiveDestination.MOODS
            path == listOf("charts_screen") -> ArchiveDestination.CHARTS
            path == listOf("new_release") -> ArchiveDestination.RELEASES
            path == listOf("news") -> ArchiveDestination.NEWS
            path.firstOrNull() == "view_news" -> id(ArchiveDestination.ARTICLE, "newsId")
            path.firstOrNull() == "browse" -> id(ArchiveDestination.BROWSE, "browseId")
            path.firstOrNull() == "youtube_browse" -> id(ArchiveDestination.YOUTUBE_BROWSE, "browseId")
            path == listOf("podcasts") -> ArchiveDestination.PODCASTS
            path.firstOrNull() == "podcast" -> id(ArchiveDestination.PODCAST, "browseId")
            path == listOf("local_songs") -> ArchiveDestination.LOCAL_SONGS
            path == listOf("uploaded_songs") -> ArchiveDestination.UPLOADED_SONGS
            path == listOf("auto_playlist", "downloaded") -> ArchiveDestination.DOWNLOADS
            path == listOf("history") -> ArchiveDestination.HISTORY
            path == listOf("library", "playlists") -> ArchiveDestination.PLAYLISTS
            path == listOf("library", "tags") -> ArchiveDestination.TAGS
            path.firstOrNull() == "album" -> id(ArchiveDestination.ALBUM, "albumId")
            path.firstOrNull() == "artist" && path.size == 2 -> {
                args["artistId"] = path[1]
                ArchiveDestination.ARTIST
            }
            path.size == 3 && path[0] == "artist" && path[2] == "songs" -> {
                args["artistId"] = path[1]
                ArchiveDestination.ARTIST_SONGS
            }
            path.size == 3 && path[0] == "artist" && path[2] == "albums" -> {
                args["artistId"] = path[1]
                ArchiveDestination.ARTIST_ALBUMS
            }
            path.firstOrNull() == "artist_items" -> id(ArchiveDestination.ARTIST_ITEMS, "browseId")
            path.firstOrNull() in listOf("local_playlist", "online_playlist", "spotify_playlist", "auto_playlist", "cache_playlist", "top_playlist") ->
                id(ArchiveDestination.PLAYLIST, "playlistId")?.also { args["playlistType"] = path[0] }
            path.firstOrNull() == "settings" -> ArchiveDestination.SETTINGS
            path == listOf("account") -> ArchiveDestination.ACCOUNT
            path == listOf("login") -> ArchiveDestination.LOGIN
            path == listOf("stats") -> ArchiveDestination.STATISTICS
            path == listOf("year_in_music") -> ArchiveDestination.RECAP
            path == listOf("listen_together", "chat") -> ArchiveDestination.LISTEN_TOGETHER_CHAT
            path.firstOrNull() in listOf("listen_together", "listen_together_from_topbar") -> ArchiveDestination.LISTEN_TOGETHER
            path == listOf("telegram", "bots") -> ArchiveDestination.TELEGRAM_BOTS
            path == listOf("telegram", "browse") -> ArchiveDestination.TELEGRAM_BROWSE
            path.size == 3 && path[0] == "telegram" && path[1] == "bot" && path[2].isNotBlank() -> {
                args["botId"] = path[2]
                ArchiveDestination.TELEGRAM_BOT_CHAT
            }
            path == listOf("music_recognition") -> ArchiveDestination.MUSIC_RECOGNITION
            path.size == 3 && path[0] == "music_recognition" && path[1] == "details" && path[2].isNotBlank() -> {
                args["recognitionKey"] = path[2]
                ArchiveDestination.MUSIC_RECOGNITION_DETAILS
            }
            path.firstOrNull() == "track_info" -> id(ArchiveDestination.TRACK_INFO, "trackId")
            path.firstOrNull() == "media_info" -> id(ArchiveDestination.MEDIA_INFO, "mediaId")
            path.firstOrNull() in listOf("telegram", "telegram_browse") -> ArchiveDestination.TELEGRAM
            else -> null
        } ?: return null
        return ArchiveRoute(value, destination, args)
    }

    internal fun decodeSearch(value: String): String = if (value == "~") "" else runCatching {
        Base64.UrlSafe.decode(value.padEnd((value.length + 3) / 4 * 4, '=')).decodeToString()
    }.getOrElse { value }

    private fun encode(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val n = byte.toInt() and 255
            if (n in 65..90 || n in 97..122 || n in 48..57 || n.toChar() in "-._~") append(n.toChar())
            else { append('%'); append(HEX[n shr 4]); append(HEX[n and 15]) }
        }
    }

    private fun decode(value: String): String {
        val bytes = ArrayList<Byte>()
        var i = 0
        while (i < value.length) {
            if (value[i] == '%' && i + 2 < value.length) {
                val byte = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (byte != null) { bytes += byte.toByte(); i += 3; continue }
            }
            val end = value.indexOf('%', i + 1).takeIf { it >= 0 } ?: value.length
            value.substring(i, end).encodeToByteArray().forEach { bytes += it }
            i = end
        }
        return bytes.toByteArray().decodeToString()
    }

    private const val HEX = "0123456789ABCDEF"
}
