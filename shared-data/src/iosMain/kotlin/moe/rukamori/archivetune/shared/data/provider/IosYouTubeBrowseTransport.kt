package moe.rukamori.archivetune.shared.data.provider

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.models.NavigationEndpoint
import com.metrolist.innertubex.models.Run
import com.metrolist.innertubex.models.Runs
import com.metrolist.innertubex.models.Thumbnails
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.models.YouTubeLocale
import com.metrolist.innertubex.models.response.BrowseResponse
import com.metrolist.innertubex.models.response.NextResponse
import com.metrolist.innertubex.models.response.SearchResponse
import com.metrolist.innertubex.models.splitBySeparator
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import moe.rukamori.archivetune.shared.domain.repository.ContentCredit
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentSearchFilter
import moe.rukamori.archivetune.shared.domain.repository.UnsupportedContentOperation
import moe.rukamori.archivetune.shared.domain.settings.DataSyncIdKey
import moe.rukamori.archivetune.shared.domain.settings.InnerTubeCookieKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceDefaults
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.UseLoginForBrowse
import moe.rukamori.archivetune.shared.domain.settings.VisitorDataKey
import moe.rukamori.archivetune.shared.domain.settings.YouTubeMusicRegionKey

/** The YouTube Music client every catalogue and player request goes through, as the Android host does. */
private val BROWSE_CLIENT = YouTubeClient.WEB_REMIX

private val VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}\$")
private val DURATION_REGEX = Regex("^\\d{1,2}:\\d{2}(?::\\d{2})?\$")

/**
 * The iOS InnerTube boundary. One InnerTubeX `InnerTube` session serves the anonymous and the signed-in catalogue:
 * [applySession] points it at the persisted cookie, which is exactly what the Android app does on every preference
 * change. The engine sees only the JSON the provider returned; renderer payloads are decoded into the shelf models
 * below and reduced to [YouTubeEntry] the same way the Android catalogue does (watch endpoints are songs, browse
 * endpoints are albums, artists or playlists by the id's shape).
 *
 * The six Android `SearchFilter` params are served, and ALL is served as YouTube's own summary page — the same request
 * the Android app makes without a param. PLAYLISTS, PODCASTS and EPISODES have no verified param in the Android app
 * either, so they keep the explicit refusal rather than searching a different surface.
 */
class IosYouTubeBrowseTransport(
    private val http: HttpClient,
) : YouTubeBrowseTransport, YouTubeAccountTransport {

    // A derived client shares the graph's engine and adds what InnerTubeX expects: status exceptions and JSON
    // bodies. It is never closed on its own, because closing it would close the shared engine; the graph owns that.
    private val client by lazy { createHttpClient() }
    private val innerTube by lazy { InnerTube(httpClient = client) }

    /**
     * The one InnerTubeX session of this host. The shelf transport ([catalogueTransport]) reads it as well, so the
     * home feed and the account listings act for the same sign-in.
     */
    internal val innerTubeSession: InnerTube get() = innerTube

    // The device locale the library computed, latched before any region override so SYSTEM_DEFAULT restores it.
    private var initialLocale: YouTubeLocale? = null

    private val json: Json = Json { ignoreUnknownKeys = true }

    /** Points the session at the persisted YouTube sign-in. A null cookie leaves the client signed out. */
    fun applySession(cookie: String?, visitorData: String?, dataSyncId: String?, useLoginForBrowse: Boolean) {
        innerTube.replaceSession(
            cookie = cookie?.takeIf(String::isNotBlank),
            visitorData = visitorData?.takeIf(String::isNotBlank),
            dataSyncId = dataSyncId?.takeIf(String::isNotBlank),
            authUser = "0",
            useLoginForBrowse = useLoginForBrowse,
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun createHttpClient(): HttpClient =
        http.config {
            expectSuccess = true
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        explicitNulls = false
                        encodeDefaults = true
                    },
                )
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000L
                connectTimeoutMillis = 15_000L
                socketTimeoutMillis = 30_000L
            }
        }

    /** Re-reads the persisted session keys into the session, so a preference change needs no restart. */
    fun refreshSession(preferences: PreferenceSnapshot?) {
        applySession(
            cookie = preferences?.get(InnerTubeCookieKey),
            visitorData = preferences?.get(VisitorDataKey),
            dataSyncId = preferences?.get(DataSyncIdKey),
            useLoginForBrowse = preferences?.get(UseLoginForBrowse) != false,
        )
        // The region override rides the same refresh: a picked region forces gl on every request and drops visitor
        // data so region-sensitive endpoints answer anonymously, which is Android's regionSpooferActive behaviour.
        if (initialLocale == null) initialLocale = innerTube.locale
        val region = preferences?.get(YouTubeMusicRegionKey)
            ?.takeIf { it.isNotBlank() && it != PreferenceDefaults.youtubeMusicRegion }
        innerTube.regionOverrideActive = region != null
        innerTube.locale = if (region != null) {
            (initialLocale ?: innerTube.locale).copy(gl = region)
        } else {
            initialLocale ?: innerTube.locale
        }
    }

    /** The signed-in session the account binding reads; an anonymous session answers false. */
    override fun signedIn(): Boolean = innerTube.sessionSnapshot().sapisid?.isNotBlank() == true

    override suspend fun page(browse: YouTubeBrowse, continuation: String?): YouTubeEntryPage =
        when (browse) {
            is YouTubeBrowse.Playlist -> shelfPage(browseId = "VL${browse.playlistId}", continuation = continuation)
            is YouTubeBrowse.Album -> shelfPage(browseId = browse.browseId, continuation = continuation)
            is YouTubeBrowse.Search -> searchPage(browse, continuation)
            is YouTubeBrowse.Section -> sectionPage(browse)
            is YouTubeBrowse.ArtistItems -> artistItemsPage(browse, continuation)
            is YouTubeBrowse.Watch -> watchPage(browse, continuation)
            is YouTubeBrowse.WatchNext -> watchNextPage(browse, continuation)
        }

    /**
     * The watch-next queue page of `YouTubeQueue.getInitialStatus`/`nextPage`: the InnerTube `next` endpoint with
     * the seed fields the queue was opened with. The Up-next tab carries the `playlistPanelRenderer` the core
     * client's queue parse reads; each page re-calls `next` with the continuation, exactly like Android.
     */
    private suspend fun watchNextPage(browse: YouTubeBrowse.WatchNext, continuation: String?): YouTubeEntryPage =
        nextPanelPage(
            videoId = browse.videoId,
            playlistId = browse.playlistId,
            playlistSetVideoId = browse.playlistSetVideoId,
            params = browse.params,
            continuation = continuation,
        )

    private suspend fun watchPage(browse: YouTubeBrowse.Watch, continuation: String?): YouTubeEntryPage =
        nextPanelPage(
            videoId = browse.videoId,
            playlistId = browse.playlistId,
            playlistSetVideoId = null,
            params = browse.params,
            continuation = continuation,
        )

    /**
     * A provider section's whole page: `YouTube.browse(browseId, params)` on Android, answered in one response
     * the way the desktop transport returns it — a section carries no continuation of its own.
     */
    private suspend fun sectionPage(browse: YouTubeBrowse.Section): YouTubeEntryPage {
        val response = innerTube.browse(BROWSE_CLIENT, browseId = browse.browseId, params = browse.params).body<BrowseResponse>()
        val section = sectionListOf(response.contents) ?: return YouTubeEntryPage(emptyList())
        return sectionPage(section, includeContinuation = false)
    }

    /**
     * An artist "see all" page: the same `browse` call `YouTube.artistItems`/`artistItemsContinuation` makes, the
     * grid or shelf parse reduced to entries like the desktop catalogue transport does.
     */
    private suspend fun artistItemsPage(browse: YouTubeBrowse.ArtistItems, continuation: String?): YouTubeEntryPage {
        val response =
            if (continuation == null) {
                innerTube.browse(BROWSE_CLIENT, browseId = browse.browseId, params = browse.params)
            } else {
                innerTube.browse(BROWSE_CLIENT, continuation = continuation)
            }.body<BrowseResponse>()
        if (continuation != null) return continuationPage(response.continuationContents)
        val section = sectionListOf(response.contents) ?: return YouTubeEntryPage(emptyList())
        return sectionPage(section)
    }

    /** The section list under whichever results renderer the response carries. */
    private fun sectionListOf(contents: BrowseResponse.Contents?): SectionListRenderer? {
        contents ?: return null
        contents.sectionListRenderer?.let { return json.decodeFromJsonElement(it) }
        val single =
            (contents.singleColumnBrowseResultsRenderer?.get("tabs") as? JsonArray)
                ?.firstOrNull()?.jsonObject?.get("tabRenderer")?.jsonObject?.get("content")
                ?.jsonObject?.get("sectionListRenderer")
        single?.let { return json.decodeFromJsonElement(it) }
        return contents.twoColumnBrowseResultsRenderer?.let { json.decodeFromJsonElement<TwoColumn>(it) }
            ?.secondaryContents?.sectionListRenderer?.let { json.decodeFromJsonElement(it) }
    }

    private suspend fun nextPanelPage(
        videoId: String?,
        playlistId: String?,
        playlistSetVideoId: String?,
        params: String?,
        continuation: String?,
    ): YouTubeEntryPage {
        val response =
            innerTube.next(
                BROWSE_CLIENT,
                videoId = videoId,
                playlistId = playlistId,
                playlistSetVideoId = playlistSetVideoId,
                params = params,
                continuation = continuation,
            ).body<NextResponse>()
        val panel =
            if (continuation == null) {
                firstPanel(response.contents?.singleColumnMusicWatchNextResultsRenderer)
            } else {
                response.continuationContents?.playlistPanelContinuation
            }
        val entries = mutableListOf<YouTubeEntry>()
        var cellToken: String? = null
        panel?.get("contents")?.jsonArray?.forEach { cell ->
            cell.jsonObject.playlistPanelVideo()?.let(entries::add)
            cell.jsonObject.continuationCommandToken()?.let { cellToken = it }
        }
        return YouTubeEntryPage(entries, nextRadioToken(panel) ?: cellToken)
    }

    /** The Up-next tab's `playlistPanelRenderer`: the first tab is the queue, matching the core parse. */
    private fun firstPanel(singleColumn: JsonObject?): JsonObject? {
        val tabs =
            (singleColumn?.get("tabbedRenderer")?.jsonObject?.get("watchNextTabbedResultsRenderer")
                ?.jsonObject?.get("tabs") as? JsonArray)
        val sectionList =
            tabs?.firstOrNull()?.jsonObject?.get("tabRenderer")?.jsonObject?.get("content")
                ?.jsonObject?.get("sectionListRenderer")?.jsonObject
        val sections = sectionList?.get("contents") as? JsonArray
        return sections?.firstOrNull()?.jsonObject?.get("playlistPanelRenderer")?.jsonObject
    }

    /** The panel-level `nextRadioContinuationData` token, the next `next` page's handle. */
    private fun nextRadioToken(panel: JsonObject?): String? {
        val continuations = panel?.get("continuations") as? JsonArray ?: return null
        return continuations.firstNotNullOfOrNull { next ->
            ((((next as? JsonObject)?.get("nextRadioContinuationData") as? JsonObject)
                ?.get("continuation") as? JsonPrimitive)?.contentOrNull)
        }
    }

    private fun JsonObject.playlistPanelVideo(): YouTubeEntry? {
        val wrapped =
            (this["playlistPanelVideoRenderer"] as? JsonObject)
                ?: ((this["playlistPanelVideoWrapperRenderer"] as? JsonObject)
                    ?.get("primaryRenderer")?.jsonObject?.get("playlistPanelVideoRenderer") as? JsonObject)
                ?: return null
        val item = json.decodeFromJsonElement<PanelVideo>(wrapped)
        val videoId = item.videoId ?: return null
        val title = item.title?.runs?.text().takeUnless { it.isNullOrBlank() } ?: return null
        // The byline carries the artist and album credit links exactly like a list row's, so the
        // queue entry and the song a refetch refreshes keep the identities the provider gave.
        val groups = item.longBylineText?.runs?.splitBySeparator().orEmpty()
        val artists = groups.artistCredits()
        val album = groups.firstNotNullOfOrNull { group -> group.firstNotNullOfOrNull { run -> run.albumId() } }
        return YouTubeEntry(
            kind = ContentKind.SONG,
            id = videoId,
            title = title,
            subtitle = item.longBylineText?.runs?.text().orEmpty(),
            artworkUri = item.thumbnail?.thumbnails?.lastOrNull()?.url,
            durationSeconds = item.lengthText?.runs?.text()?.toSeconds(),
            explicit = item.badges.orEmpty().any { it.musicInlineBadgeRenderer?.iconType == "MUSIC_EXPLICIT_BADGE" },
            setVideoId = item.playlistEntryId,
            album = album?.second,
            artists = artists,
            albumId = album?.first,
            isVideo = item.videoType == "MUSIC_VIDEO_TYPE_OMV" || item.videoType == "MUSIC_VIDEO_TYPE_UGC",
        )
    }

    /** A `continuationItemRenderer` cell's continuation token, the same page handle as on Android. */
    private fun JsonObject.continuationCommandToken(): String? =
        ((((this["continuationItemRenderer"] as? JsonObject)
            ?.get("continuationEndpoint")?.jsonObject?.get("continuationCommand") as? JsonObject)
            ?.get("token") as? JsonPrimitive)?.contentOrNull)

    private suspend fun shelfPage(browseId: String, continuation: String?): YouTubeEntryPage {
        val response =
            if (continuation == null) {
                innerTube.browse(BROWSE_CLIENT, browseId = browseId).body<BrowseResponse>()
            } else {
                innerTube.browse(BROWSE_CLIENT, continuation = continuation).body<BrowseResponse>()
            }
        if (continuation != null) {
            return continuationPage(response.continuationContents)
        }
        val contents = response.contents ?: return YouTubeEntryPage(emptyList())
        return sectionListOf(contents)?.let { sectionPage(it) } ?: YouTubeEntryPage(emptyList())
    }

    private fun sectionPage(section: SectionListRenderer, includeContinuation: Boolean = true): YouTubeEntryPage {
        val entries = mutableListOf<YouTubeEntry>()
        var continuation: String? = null
        section.contents.orEmpty().forEach { item ->
            item.musicShelfRenderer?.let { shelf -> entries += shelfEntries(shelf) }
            item.musicPlaylistShelfRenderer?.let { shelf -> entries += shelfEntries(shelf) }
            item.gridRenderer?.items.orEmpty().forEach { cell -> cell.toEntry()?.let(entries::add) }
            item.musicResponsiveListItemRenderer?.toEntry()?.let(entries::add)
            if (includeContinuation) {
                item.continuationItemRenderer?.let { renderer -> continuation = renderer.token() }
                // Continuations also live at the shelf level for playlist, search and library pages.
                item.musicShelfRenderer?.continuations.orEmpty().forEach { next -> next.token()?.let { continuation = it } }
                item.musicPlaylistShelfRenderer?.continuations.orEmpty().forEach { next -> next.token()?.let { continuation = it } }
                item.gridRenderer?.continuations.orEmpty().forEach { next -> next.token()?.let { continuation = it } }
            }
        }
        return YouTubeEntryPage(entries, continuation)
    }

    private fun continuationPage(contents: BrowseResponse.ContinuationContents?): YouTubeEntryPage {
        contents ?: return YouTubeEntryPage(emptyList())
        contents.musicPlaylistShelfContinuation?.let { raw ->
            val shelf = json.decodeFromJsonElement<Shelf>(raw)
            return YouTubeEntryPage(shelfEntries(shelf), shelf.continuations.firstToken())
        }
        contents.musicShelfContinuation?.let { raw ->
            val shelf = json.decodeFromJsonElement<Shelf>(raw)
            return YouTubeEntryPage(shelfEntries(shelf), shelf.continuations.firstToken())
        }
        contents.sectionListContinuation?.let { section ->
            return sectionPage(json.decodeFromJsonElement<SectionListRenderer>(section))
        }
        contents.gridContinuation?.let { raw ->
            val grid = json.decodeFromJsonElement<Grid>(raw)
            val entries = grid.items.orEmpty().mapNotNull { cell -> cell.toEntry() }
            return YouTubeEntryPage(entries, grid.continuations.firstToken())
        }
        return YouTubeEntryPage(emptyList())
    }

    private fun shelfEntries(shelf: Shelf): List<YouTubeEntry> =
        shelf.contents.orEmpty().mapNotNull { cell -> cell.toEntry() }

    private suspend fun searchPage(browse: YouTubeBrowse.Search, continuation: String?): YouTubeEntryPage {
        val params = youTubeSearchFilterParam(browse.filter)
        if (params == null) {
            // The ALL filter is YouTube's summary page: the same request without `params`, answered with a top-result
            // card, inline snippets and the type shelves. The Android app reads it through `YouTube.searchSummary`,
            // and the shared section-list parser reduces it to the same rows. The summary carries no continuation.
            if (browse.filter != ContentSearchFilter.ALL || continuation != null) throw UnsupportedContentOperation()
            val response = innerTube.search(BROWSE_CLIENT, query = browse.query).body<SearchResponse>()
            val contents = response.contents
            return YouTubeEntryPage(
                youTubeSearchSummaryPage(
                    tabbedSearchResultsRenderer = contents?.tabbedSearchResultsRenderer,
                    sectionList = contents?.sectionListRenderer,
                ),
            )
        }
        val response =
            if (continuation == null) {
                innerTube.search(BROWSE_CLIENT, query = browse.query, params = params).body<SearchResponse>()
            } else {
                innerTube.search(BROWSE_CLIENT, continuation = continuation).body<SearchResponse>()
            }
        if (continuation != null) {
            response.continuationContents?.musicShelfContinuation?.let { raw ->
                val shelf = json.decodeFromJsonElement<Shelf>(raw)
                return YouTubeEntryPage(shelfEntries(shelf), shelf.continuations.firstToken())
            }
            return YouTubeEntryPage(emptyList())
        }
        val section =
            response.contents?.sectionListRenderer?.let { json.decodeFromJsonElement<SectionListRenderer>(it) }
                ?: response.contents?.tabbedSearchResultsRenderer
                    ?.let { json.decodeFromJsonElement<TabbedSearch>(it) }
                    ?.tabs.orEmpty()
                    .firstOrNull()
                    ?.tabRenderer
                    ?.content
                    ?.sectionListRenderer
                    ?.let { json.decodeFromJsonElement<SectionListRenderer>(it) }
                ?: return YouTubeEntryPage(emptyList())
        return sectionPage(section)
    }

    /** The first song hit for a playback search, or null when the provider answered none. */
    internal suspend fun firstSongVideoId(query: String): String? =
        searchPage(YouTubeBrowse.Search(query, ContentSearchFilter.SONGS), continuation = null)
            .entries
            .firstOrNull { it.kind == ContentKind.SONG }
            ?.id
            ?.takeIf { VIDEO_ID_REGEX.matches(it) }

    override suspend fun listing(listing: YouTubeListing, continuation: String?): YouTubeEntryPage {
        val browseId =
            when (listing) {
                YouTubeListing.LIKED_SONGS -> "FEmusic_liked_videos"
                YouTubeListing.LIKED_ALBUMS -> "FEmusic_liked_albums"
                YouTubeListing.SUBSCRIBED_ARTISTS -> "FEmusic_library_corpus_artists"
                YouTubeListing.LIBRARY_PLAYLISTS -> "FEmusic_liked_playlists"
            }
        return shelfPage(browseId, continuation)
    }

    override suspend fun history(continuation: String?): YouTubeEntryPage = historySections(continuation).entriesPage()

    suspend fun historySections(continuation: String? = null): YouTubeHistoryPage {
        val session = innerTube.sessionSnapshot()
        val response = innerTube.browse(
            BROWSE_CLIENT,
            browseId = if (continuation == null) "FEmusic_history" else null,
            continuation = continuation,
            setLogin = true,
        ).body<JsonObject>()
        val current = innerTube.sessionSnapshot()
        if (session.cookie != current.cookie || session.dataSyncId != current.dataSyncId || session.authUser != current.authUser) {
            throw moe.rukamori.archivetune.shared.domain.repository.ContentAccountChanged()
        }
        return parseYouTubeHistoryPage(response, ::historyEntry)
    }

    override suspend fun likeSong(videoId: String, liked: Boolean) {
        if (liked) innerTube.likeVideo(BROWSE_CLIENT, videoId) else innerTube.unlikeVideo(BROWSE_CLIENT, videoId)
    }

    override suspend fun likeAlbum(browseId: String, liked: Boolean) {
        // Only the album page carries the playlist id that the provider keys an album like on.
        val playlistId = albumPlaylistId(browseId) ?: throw IllegalStateException("The album has no playlist to like")
        if (liked) innerTube.likePlaylist(BROWSE_CLIENT, playlistId) else innerTube.unlikePlaylist(BROWSE_CLIENT, playlistId)
    }

    override suspend fun subscribeArtist(browseId: String, subscribed: Boolean) {
        // An artist's browse id is its channel id; the subscribe endpoint takes the same value.
        if (!browseId.startsWith("UC") && !browseId.startsWith("FEMC")) {
            throw IllegalStateException("The artist has no channel to subscribe to")
        }
        if (subscribed) innerTube.subscribeChannel(BROWSE_CLIENT, browseId) else innerTube.unsubscribeChannel(BROWSE_CLIENT, browseId)
    }

    suspend fun createPlaylist(title: String): String {
        val response = innerTube.createPlaylist(BROWSE_CLIENT, title.trim()).body<JsonObject>()
        val playlistId = (response["playlistId"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
        return playlistId ?: throw IllegalStateException("Create playlist returned no playlistId")
    }

    suspend fun addSongsToPlaylist(playlistId: String, videoIds: List<String>) {
        val normalizedId = playlistId.removePrefix("VL")
        for (videoId in videoIds) {
            innerTube.addToPlaylist(BROWSE_CLIENT, normalizedId, videoId)
        }
    }

    /** The playlist id the album's own header play endpoint carries, used by the album like. */
    private suspend fun albumPlaylistId(browseId: String): String? {
        val response = innerTube.browse(BROWSE_CLIENT, browseId = browseId).body<BrowseResponse>()
        val header = response.header ?: return null
        listOf(
                header.musicResponsiveHeaderRenderer,
                header.musicDetailHeaderRenderer,
                header.musicImmersiveHeaderRenderer,
                header.musicVisualHeaderRenderer,
            ).forEach { candidate ->
                candidate?.let { findFirstPlaylistId(it) }?.let { return it }
            }
        return null
    }
}

/** The first `watchPlaylistEndpoint` playlist id anywhere in a header payload. */
private fun findFirstPlaylistId(node: JsonObject): String? {
    node["watchPlaylistEndpoint"]?.let { endpoint ->
        (endpoint as? JsonObject)?.get("playlistId")
            ?.let { element -> element.toString().trim('"').takeIf(String::isNotBlank) }
            ?.let { return it }
    }
    node.values.forEach { element ->
        (element as? JsonObject)?.let { findFirstPlaylistId(it) }?.let { return it }
    }
    return null
}

private fun NavigationEndpoint?.endpointKind(): ContentKind? {
    this ?: return null
    watchEndpoint?.let { return ContentKind.SONG }
    watchPlaylistEndpoint?.let { return ContentKind.PLAYLIST }
    browseEndpoint?.let { endpoint ->
        return when {
            endpoint.browseId.startsWith("MPRE") -> ContentKind.ALBUM
            endpoint.browseId.startsWith("UC") || endpoint.browseId.startsWith("FEMC") -> ContentKind.ARTIST
            endpoint.browseId.startsWith("VL") || endpoint.browseId.startsWith("PL") ||
                endpoint.browseId.startsWith("FEmusic") -> ContentKind.PLAYLIST
            else -> null
        }
    }
    return null
}

private fun NavigationEndpoint?.endpointId(): String? {
    this ?: return null
    watchEndpoint?.videoId?.let { return it }
    watchPlaylistEndpoint?.playlistId?.let { return it.removePrefix("VL") }
    browseEndpoint?.browseId?.let { return it.removePrefix("VL") }
    return null
}

private fun ResponsiveItem.toEntry(): YouTubeEntry? {
    val titleRuns = flexColumns?.getOrNull(0)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs
        ?: return null
    val title = titleRuns.joinToString("") { it.text }.takeIf(String::isNotBlank) ?: return null
    val itemEndpoint = titleRuns.firstNotNullOfOrNull { it.navigationEndpoint } ?: navigationEndpoint
    var kind = itemEndpoint.endpointKind()
    val id = itemEndpoint.endpointId() ?: return null
    // A watch item with no clearer signal is a song; a browse item must name its surface or it is dropped.
    if (kind == null) {
        kind = if (itemEndpoint?.watchEndpoint != null) ContentKind.SONG else return null
    }
    val groups = subtitleGroups()
    // Search shelves label the result type in the first subtitle group ("Song", "Video", "Album", ...). The label is
    // only a hint: when it names a different surface than the endpoint did, the endpoint stays authoritative.
    // A linked run in that group is the artist or album credit itself, so only plain text can carry the label.
    val labelGroup = groups.firstOrNull()?.takeIf { group -> group.none { it.navigationEndpoint != null } }
    val labeledKind = labelGroup?.typeLabel()?.contentKind()
    if (labeledKind != null && kind == ContentKind.SONG) kind = labeledKind
    val artists = groups.artistCredits()
    val album = groups.firstNotNullOfOrNull { group -> group.firstNotNullOfOrNull { run -> run.albumId() } }
    val duration = groups.lastOrNull()?.joinToString("") { it.text }?.takeIf { DURATION_REGEX.matches(it) }
    return YouTubeEntry(
        kind = kind,
        id = id,
        title = title,
        subtitle = artists.joinToString(", ") { it.name }.ifBlank { groups.firstOrNull()?.text().orEmpty() },
        artworkUri = thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails?.lastOrNull()?.url,
        durationSeconds = duration?.toSeconds(),
        explicit = badges.orEmpty().any { it.musicInlineBadgeRenderer?.iconType == "MUSIC_EXPLICIT_BADGE" },
        setVideoId = playlistItemData?.playlistSetVideoId,
        album = album?.second,
        artists = artists,
        albumId = album?.first,
    )
}

private fun ResponsiveItem.subtitleGroups(): List<List<Run>> {
    val groups = mutableListOf<List<Run>>()
    flexColumns.orEmpty().drop(1).forEach { column ->
        column.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.splitBySeparator()?.let { groups += it }
    }
    return groups.filter { it.any { run -> run.text.isNotBlank() } }
}

private fun List<Run>.typeLabel(): String? {
    val label = joinToString("") { it.text }.trim()
    return label.takeIf { it.isNotEmpty() }
}

private fun String.contentKind(): ContentKind? =
    when (this) {
        "Song" -> ContentKind.SONG
        // A music video is a playable song in the catalogue; the contract has no separate video kind.
        "Video" -> ContentKind.SONG
        "Album" -> ContentKind.ALBUM
        "Artist" -> ContentKind.ARTIST
        "Playlist" -> ContentKind.PLAYLIST
        "Podcast" -> ContentKind.PODCAST
        "Episode" -> ContentKind.EPISODE
        else -> null
    }

private fun List<List<Run>>.artistCredits(): List<ContentCredit> =
    firstOrNull()?.mapNotNull { run ->
        val browseId = run.navigationEndpoint?.browseEndpoint?.browseId
        val text = run.text.takeIf(String::isNotBlank)
        when {
            text == null -> null
            browseId != null && (browseId.startsWith("UC") || browseId.startsWith("FEMC")) ->
                ContentCredit(text, browseId)
            else -> null
        }
    }.orEmpty()

/** The (browseId, title) of the album credit, keyed on the album browse id's MPRE prefix. */
private fun Run.albumId(): Pair<String, String>? {
    val browseId = navigationEndpoint?.browseEndpoint?.browseId?.takeIf { it.startsWith("MPRE") } ?: return null
    return text.takeIf(String::isNotBlank)?.let { browseId to it }
}

/** The two-row tile of artist-items grids and home sections: its endpoint names the entry the way a list cell does. */
private fun TwoRowItem.toEntry(): YouTubeEntry? {
    val kind = navigationEndpoint.endpointKind() ?: return null
    val id = navigationEndpoint.endpointId() ?: return null
    val name = title?.runs?.text().takeUnless { it.isNullOrBlank() } ?: return null
    return YouTubeEntry(
        kind = kind,
        id = id,
        title = name,
        subtitle = subtitle?.runs?.text().orEmpty(),
        artworkUri = thumbnailRenderer?.musicThumbnailRenderer?.thumbnail?.thumbnails?.lastOrNull()?.url,
    )
}

private fun List<Run>.text(): String = joinToString("") { it.text }.trim()

private fun String.toSeconds(): Int? {
    val parts = split(":").mapNotNull { it.toIntOrNull() }
    return when (parts.size) {
        2 -> parts[0] * 60 + parts[1]
        3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
        else -> null
    }
}

private fun List<NextContinuation>?.firstToken(): String? = orEmpty().firstNotNullOfOrNull { it.token() }

private fun NextContinuation.token(): String? =
    nextContinuationData?.continuation
        ?: continuationEndpoint?.continuationCommand?.token

private fun ContinuationItemRenderer.token(): String? =
    continuationEndpoint?.continuationCommand?.token

// The browse and search payloads the InnerTubeX response types leave as raw objects. Each model covers the renderer
// shapes YouTube Music returns for shelves, playlist shelves, grid cells and continuations; anything else is ignored
// because `ignoreUnknownKeys` is set.

@Serializable
private data class TabbedSearch(val tabs: List<SearchTab>? = null)

@Serializable
private data class SearchTab(val tabRenderer: TabContent? = null)

@Serializable
private data class TabContent(val content: SectionContent? = null)

@Serializable
private data class SectionContent(val sectionListRenderer: JsonObject? = null)

@Serializable
private data class TwoColumn(val secondaryContents: SectionContent? = null)

@Serializable
private data class SectionListRenderer(val contents: List<SectionItem>? = null)

@Serializable
private data class SectionItem(
    val musicShelfRenderer: Shelf? = null,
    val musicPlaylistShelfRenderer: Shelf? = null,
    val gridRenderer: Grid? = null,
    val musicResponsiveListItemRenderer: ResponsiveItem? = null,
    val continuationItemRenderer: ContinuationItemRenderer? = null,
)

@Serializable
private data class Shelf(
    val title: Runs? = null,
    val contents: List<ShelfCell>? = null,
    val continuations: List<NextContinuation>? = null,
)

@Serializable
private data class ShelfCell(
    val musicResponsiveListItemRenderer: ResponsiveItem? = null,
    val musicTwoRowItemRenderer: TwoRowItem? = null,
) {
    /** The shelf or grid cell reduced to an entry: the compact list row, then the two-row tile. */
    fun toEntry(): YouTubeEntry? =
        musicResponsiveListItemRenderer?.toEntry() ?: musicTwoRowItemRenderer?.toEntry()
}

@Serializable
private data class TwoRowItem(
    val title: Runs? = null,
    val subtitle: Runs? = null,
    val navigationEndpoint: NavigationEndpoint? = null,
    val thumbnailRenderer: TwoRowThumbnail? = null,
) {
    @Serializable
    data class TwoRowThumbnail(val musicThumbnailRenderer: ResponsiveItem.ThumbnailHolder? = null)
}

@Serializable
private data class Grid(
    val items: List<ShelfCell>? = null,
    val continuations: List<NextContinuation>? = null,
)

@Serializable
private data class NextContinuation(
    val nextContinuationData: ContinuationData? = null,
    val continuationEndpoint: ContinuationEndpoint? = null,
)

@Serializable
private data class ContinuationData(val continuation: String? = null)

@Serializable
private data class ContinuationEndpoint(val continuationCommand: ContinuationCommand? = null)

@Serializable
private data class ContinuationCommand(val token: String? = null)

@Serializable
private data class ContinuationItemRenderer(val continuationEndpoint: ContinuationEndpoint? = null)

@Serializable
private data class ResponsiveItem(
    val flexColumns: List<FlexColumn>? = null,
    val thumbnail: ThumbnailWrap? = null,
    val playlistItemData: PlaylistItemData? = null,
    val navigationEndpoint: NavigationEndpoint? = null,
    val badges: List<Badge>? = null,
) {
    @Serializable
    data class FlexColumn(val musicResponsiveListItemFlexColumnRenderer: TextColumn? = null)

    @Serializable
    data class TextColumn(val text: Runs? = null)

    @Serializable
    data class ThumbnailWrap(val musicThumbnailRenderer: ThumbnailHolder? = null)

    @Serializable
    data class ThumbnailHolder(val thumbnail: Thumbnails? = null)

    @Serializable
    data class PlaylistItemData(val videoId: String? = null, val playlistSetVideoId: String? = null)
}

@Serializable
private data class PanelVideo(
    val videoId: String? = null,
    val title: Runs? = null,
    val longBylineText: Runs? = null,
    val lengthText: Runs? = null,
    val thumbnail: Thumbnails? = null,
    val playlistSetVideoId: String? = null,
    val setVideoId: String? = null,
    val badges: List<Badge>? = null,
    val videoType: String? = null,
) {
    val playlistEntryId: String? get() = playlistSetVideoId ?: setVideoId
}

@Serializable
private data class Badge(val musicInlineBadgeRenderer: InlineBadge? = null)

@Serializable
private data class InlineBadge(val iconType: String? = null)
