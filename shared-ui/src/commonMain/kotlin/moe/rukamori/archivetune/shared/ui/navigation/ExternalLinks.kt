package moe.rukamori.archivetune.shared.ui.navigation

import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherServers

/**
 * What one incoming link asks the host to do, classified the way the Android `MainActivity` intent handler reads the
 * same links. Parsing and classification only: a link is never fetched or contacted as a request.
 */
sealed interface ExternalLink {
    /**
     * A page link whose [route] [ArchiveRoutes] resolves. `archivetune://open/...` carries it directly; a share or
     * https link maps onto the same destination vocabulary.
     */
    data class Route(val route: String) : ExternalLink

    /**
     * `archivetune://login?url=` — the sign-in return the Android host turns into its login route. No host opens it
     * from a link: the same refusal that keeps a link from starting a sign-in or the microphone.
     */
    data class Login(val url: String?) : ExternalLink

    /** A Listen Together invite carrying the room code. */
    data class ListenTogetherInvite(val code: String) : ExternalLink

    /**
     * A YouTube watch link. The Android host queues and plays [videoId] instead of navigating; [playlistId] is the
     * link's playlist context and [shuffle] its playlist shuffle request.
     */
    data class YouTubeWatch(val videoId: String, val playlistId: String?, val shuffle: Boolean) : ExternalLink
}

/**
 * The shared URL-to-destination table behind `archivetune://` links and the share/https links a host accepts, so the
 * iOS and desktop intakes map a link the way the Android intent handler does instead of keeping three tables.
 *
 * YouTube and YouTube Music links map onto the same routes the Android handler navigates (album, artist, online
 * playlist) or, for watch links, the play request it performs. Spotify and Telegram links reach the Android handler
 * only as shared text and open nothing there; here a Spotify playlist, album or artist maps onto its Android route
 * and a Telegram link opens the Telegram browse page, while a kind with no destination (a Spotify track, show or
 * episode) stays unmapped exactly as Android leaves it.
 *
 * A Listen Together invite is recognized exactly as Android recognizes it — a `listen` host, a `listen` first path
 * segment, or one of the invite hosts in [ListenTogetherServers], which accepts the retired onrender host only as an
 * invite-link host and never contacts it — and the code is `?code`, then `?room`, then the second path segment.
 * `OLAK5uy_` album-playlist links open the playlist page, not the album the Android host resolves online, because
 * this table makes no request.
 */
object ExternalLinks {
    /** The YouTube hosts the Android manifest registers for the deep-link table. */
    private val YOUTUBE_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")

    /** The Spotify catalog web host whose links name playlist, album and artist destinations. */
    private const val SPOTIFY_HOST = "open.spotify.com"

    /** The Telegram web hosts a channel or bot link may name; they open the Telegram browse page. */
    private val TELEGRAM_HOSTS = setOf("t.me", "telegram.me", "telegram.dog")

    private val SCHEME = Regex("[A-Za-z][A-Za-z0-9+.-]*")

    /** Upper bound for one intake link; nothing legitimate is this long. */
    private const val MAX_LINK_LENGTH = 8_192

    /**
     * Classifies [raw] or returns null when the link is not for this app. `archivetune://login` and
     * `archivetune://open/...` match first, as they do in the Android handler; a page link under `open` stays a page
     * link even when its path spells `listen`, so a crafted `open` link cannot join a room.
     */
    fun classify(raw: String): ExternalLink? {
        val link = parse(raw) ?: return null
        if (link.scheme == "archivetune") {
            when (link.host) {
                "login" -> return ExternalLink.Login(link.query["url"])
                "open" -> {
                    val route = link.rawPath.removePrefix("/") + link.rawQuery?.let { "?$it" }.orEmpty()
                    if (route.isNotEmpty()) return ExternalLink.Route(route)
                }
            }
        }
        // The Android invite test runs before its page table, so it applies to every scheme and host.
        val isListenLink = link.segments.firstOrNull() == "listen" ||
            link.host == "listen" ||
            ListenTogetherServers.isInviteHost(link.host)
        if (isListenLink) {
            val code = link.query["code"] ?: link.query["room"] ?: link.segments.getOrNull(1)
            return code?.takeUnless { it.isBlank() }?.let(ExternalLink::ListenTogetherInvite)
        }
        if (link.scheme == "vnd.youtube" || link.scheme == "vnd.youtube.launch") {
            // The registered vnd.youtube schemes carry the video id in the authority or the path.
            val videoId = link.host?.lowercase()?.takeUnless { it.isEmpty() }
                ?: link.segments.firstOrNull().orEmpty()
            return videoId.takeUnless { it.isEmpty() }
                ?.let { ExternalLink.YouTubeWatch(it, link.query["list"], link.requestsShuffle()) }
        }
        if (link.scheme != "https" && link.scheme != "http") return null
        val host = link.host?.lowercase() ?: return null
        return when (host) {
            "youtu.be" -> link.segments.firstOrNull()
                ?.let { ExternalLink.YouTubeWatch(it, link.query["list"], link.requestsShuffle()) }
            in YOUTUBE_HOSTS -> youtubeLink(link)
            SPOTIFY_HOST -> spotifyLink(link)
            in TELEGRAM_HOSTS -> link.segments.firstOrNull()?.let { ExternalLink.Route("telegram/browse") }
            else -> null
        }
    }

    /**
     * The route a link may open, or null when it opens none. [ArchiveRoutes] decides the destination, and the refusal
     * every host keeps stays: the sign-in page and a recognition auto-start request are never opened by a link, so
     * another program cannot start a sign-in or the microphone.
     */
    fun openableRoute(route: String): String? {
        val resolved = ArchiveRoutes.resolve(route) ?: return null
        val opensAction = resolved.destination == ArchiveDestination.LOGIN ||
            resolved.arguments.containsKey("autoStart")
        return route.takeUnless { opensAction }
    }

    /** The Android page table for a YouTube host: playlist, browse and channel map to routes; watch plays. */
    private fun youtubeLink(link: ParsedLink): ExternalLink? =
        when (link.segments.firstOrNull()) {
            "playlist" -> link.query["list"]
                ?.let { ExternalLink.Route("online_playlist/${encodeRouteArg(it)}") }
            "browse" -> link.segments.lastOrNull()
                ?.let { ExternalLink.Route("album/${encodeRouteArg(it)}") }
            "channel", "c" -> link.segments.lastOrNull()
                ?.let { ExternalLink.Route("artist/${encodeRouteArg(it)}") }
            else -> {
                val videoId = if (link.segments.firstOrNull() == "watch") {
                    link.query["v"]?.takeUnless { it.isBlank() }
                } else {
                    null
                }
                val playlistId = link.query["list"]
                when {
                    videoId != null ->
                        ExternalLink.YouTubeWatch(videoId, playlistId, link.requestsShuffle())
                    link.segments.firstOrNull() == "watch" && !playlistId.isNullOrBlank() ->
                        ExternalLink.Route("online_playlist/${encodeRouteArg(playlistId)}")
                    else -> null
                }
            }
        }

    /**
     * A Spotify share link onto the Android route vocabulary. Playlist, album and artist map; a track, show or
     * episode names no route here and stays unmapped, the no-op the Android handler gives every Spotify link.
     */
    private fun spotifyLink(link: ParsedLink): ExternalLink? {
        val segments = link.segments.dropWhile { it.startsWith("intl-") }
        val kind = segments.firstOrNull() ?: return null
        val id = segments.getOrNull(1)?.takeUnless { it.isBlank() } ?: return null
        val route = when (kind) {
            "playlist" -> "spotify_playlist/${encodeRouteArg(id)}?provider=spotify"
            "album" -> "album/${encodeRouteArg(id)}?provider=spotify"
            "artist" -> "artist/${encodeRouteArg(id)}?provider=spotify"
            else -> return null
        }
        return ExternalLink.Route(route)
    }

    /** The parsed view of one link: scheme, lowercased host, raw path and query, decoded segments and parameters. */
    private class ParsedLink(
        val scheme: String,
        val host: String?,
        val rawPath: String,
        val rawQuery: String?,
        val segments: List<String>,
        val query: Map<String, String>,
    ) {
        /** The `?shuffle` request the Android handler reads: `1` or `true`, case-insensitive. */
        fun requestsShuffle(): Boolean = query["shuffle"]?.trim()?.lowercase().let { it == "1" || it == "true" }
    }

    private fun parse(raw: String): ParsedLink? {
        if (raw.isBlank() || raw.length > MAX_LINK_LENGTH || raw.any { it.code < 32 }) return null
        val schemeEnd = raw.indexOf(':')
        if (schemeEnd <= 0) return null
        val scheme = raw.substring(0, schemeEnd)
        if (!scheme.matches(SCHEME)) return null
        var rest = raw.substring(schemeEnd + 1)
        var host: String? = null
        if (rest.startsWith("//")) {
            val authorityEnd = rest.indexOfAny(charArrayOf('/', '?', '#'), 2).takeIf { it >= 0 } ?: rest.length
            val authority = rest.substring(2, authorityEnd)
            host = authority.substringAfterLast('@').substringBefore(':').lowercase().ifBlank { null }
            rest = rest.substring(authorityEnd)
        }
        val rawPath = rest.substringBefore('?').substringBefore('#')
        val rawQuery = rest.substringAfter('?', "").substringBefore('#').ifEmpty { null }
        val segments = rawPath.split('/').filter { it.isNotEmpty() }.map(::decodeComponent)
        // Android's getQueryParameter returns the first value of a repeated name.
        val query = LinkedHashMap<String, String>()
        rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.forEach { pair ->
            val name = decodeComponent(pair.substringBefore('='))
            if (name.isNotEmpty() && name !in query) query[name] = decodeComponent(pair.substringAfter('=', ""))
        }
        return ParsedLink(scheme.lowercase(), host, rawPath, rawQuery, segments, query)
    }

    /** Percent-decodes one path segment or query component, leaving any malformed escape as written. */
    private fun decodeComponent(value: String): String {
        if ('%' !in value) return value
        val bytes = ArrayList<Byte>()
        var index = 0
        while (index < value.length) {
            if (value[index] == '%' && index + 2 < value.length) {
                val byte = value.substring(index + 1, index + 3).toIntOrNull(16)
                if (byte != null) {
                    bytes += byte.toByte()
                    index += 3
                    continue
                }
            }
            val end = value.indexOf('%', index + 1).takeIf { it >= 0 } ?: value.length
            value.substring(index, end).encodeToByteArray().forEach { bytes += it }
            index = end
        }
        return bytes.toByteArray().decodeToString()
    }

    /** Encodes one route argument the way [ArchiveRoutes] encodes its own segments. */
    private fun encodeRouteArg(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val n = byte.toInt() and 255
            if (n in 65..90 || n in 97..122 || n in 48..57 || n.toChar() in "-._~") append(n.toChar())
            else {
                append('%')
                append(HEX[n shr 4])
                append(HEX[n and 15])
            }
        }
    }

    private const val HEX = "0123456789ABCDEF"
}
