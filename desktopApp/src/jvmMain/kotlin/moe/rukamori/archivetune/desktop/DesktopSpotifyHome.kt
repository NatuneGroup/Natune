package moe.rukamori.archivetune.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.ArtistItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface
import moe.rukamori.archivetune.shared.domain.repository.QueueCollectionKind
import moe.rukamori.archivetune.shared.domain.repository.QueueLoadRequest
import moe.rukamori.archivetune.shared.domain.repository.QueueStart
import moe.rukamori.archivetune.shared.domain.settings.InlineSpotifyProfileArtistsKey
import moe.rukamori.archivetune.shared.domain.settings.InlineSpotifyProfileCacheTsKey
import moe.rukamori.archivetune.shared.domain.settings.InlineSpotifyProfileRecentItemsKey
import moe.rukamori.archivetune.shared.domain.settings.InlineSpotifyProfileTopTracksKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.ui.home.variants.HomeVariantPlay
import moe.rukamori.archivetune.shared.ui.home.variants.SpotifyHomeContent
import moe.rukamori.archivetune.shared.ui.home.variants.SpotifyHomeShelf
import moe.rukamori.archivetune.shared.ui.home.variants.SpotifyHomeUi
import moe.rukamori.archivetune.shared.ui.home.variants.SpotifyShelfTitle
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoutes
import moe.rukamori.archivetune.spotify.Spotify
import moe.rukamori.archivetune.spotify.SpotifyMapper
import moe.rukamori.archivetune.spotify.models.SpotifyAlbum
import moe.rukamori.archivetune.spotify.models.SpotifyArtist
import moe.rukamori.archivetune.spotify.models.SpotifyHomeFeedItem
import moe.rukamori.archivetune.spotify.models.SpotifyTrack

/**
 * The desktop Spotify home owner. It keeps Android's four-call feed shape, its offline snapshot, and its
 * album/artist resolution order; the shared variant stays only a renderer over the state published here.
 */
internal class DesktopSpotifyHome(
    private val graph: DesktopSessionGraph,
    private val accounts: DesktopSpotifyAccounts,
    private val openRoute: (String) -> Unit,
) {
    private val scope: CoroutineScope get() = graph.scope
    @Volatile private var loadJob: Job? = null
    private var selectionJob: Job? = null
    private var selectionGeneration = 0L
    private var trackRows = emptyMap<String, SpotifyTrack>()
    private var refreshing by mutableStateOf(false)
    var ui by mutableStateOf<SpotifyHomeUi>(SpotifyHomeUi.Loading)
        private set

    fun load(force: Boolean = false) {
        loadJob?.cancel()
        loadJob = null
        cancelSelection()
        if (!force) ui = SpotifyHomeUi.Loading
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val thisJob = coroutineContext[Job]
            markRefreshing(force)
            val cached = restoreProfile()
            cached?.takeUnless { it.isBlank() }?.let { ui = it.toUi() }
            if (!accounts.restoreCatalogSession()) {
                ui = SpotifyHomeUi.Failed(notConnected = true)
                if (loadJob === thisJob) markRefreshing(false)
                return@launch
            }
            try {
                val loaded = fetchHome(cached)
                ui = if (loaded.isBlank()) SpotifyHomeUi.Empty else loaded.toUi()
                if (!loaded.isBlank()) persistProfile(loaded)
                if (loadJob === thisJob) markRefreshing(false)
            } catch (cancelled: CancellationException) {
                if (loadJob === thisJob) markRefreshing(false)
                throw cancelled
            } catch (failure: Exception) {
                graph.report("spotify home not loaded: ${failure.javaClass.simpleName}")
                ui = SpotifyHomeUi.Failed(
                    notConnected = (failure as? Spotify.SpotifyException)?.statusCode == 401,
                )
                if (loadJob === thisJob) markRefreshing(false)
            }
        }
        loadJob = job
        job.start()
    }

    fun play(play: HomeVariantPlay) {
        if (play !is HomeVariantPlay.Shelf) return
        val tapped = play.occurrences.getOrNull(play.startIndex) ?: return
        val tappedTrack = trackRows[tapped.occurrenceId] ?: return
        val tracks = play.occurrences.map { trackRows[it.occurrenceId] ?: return }
        if (tracks.isEmpty()) return
        if (play.toggleIfActive && activeSpotifyRef() == tapped.item.ref) {
            val snapshot = graph.queue.player.snapshot.value
            if (snapshot?.playWhenReady == true) graph.queue.player.pause() else graph.queue.player.play()
            return
        }
        resolve(
            ref = tapped.item.ref,
            unavailableKey = "spotify_track_unavailable",
            resolve = { accounts.resolveTrack(tappedTrack) },
        ) {
            accounts.playTrackList("spotify_home:${play.title}", tracks, play.startIndex)
        }
    }

    fun select(item: ContentItem) {
        resolve(item.ref, "spotify_item_unavailable", {
            when (item.ref.kind) {
                ContentKind.ALBUM -> resolveAlbumTarget(item)
                ContentKind.ARTIST -> resolveArtistTarget(item)
                else -> null
            }
        }) { target -> openTarget(target) }
    }

    fun openPlaylist(ref: ContentRef) {
        cancelSelection()
        if (ref.id == SPOTIFY_HOME_DJ_PLAYLIST_ID) {
            graph.report(DesktopStrings["spotify_dj_unsupported"])
            openDesktopUri("https://open.spotify.com/playlist/${ref.id}", graph::report)
        } else {
            openRoute(ArchiveRoutes.content(ref))
        }
    }

    fun activeSpotifyRef(): ContentRef? {
        val occurrenceId = graph.queue.state.value.currentOccurrenceId ?: return null
        val item = graph.queue.state.value.entries.firstOrNull { it.occurrenceId == occurrenceId }?.item ?: return null
        return accounts.spotifyRefForResolved(item.ref)
    }

    private fun cancelSelection() {
        selectionJob?.cancel()
        selectionJob = null
        selectionGeneration += 1
        setResolving(null)
    }

    private fun <T> resolve(
        ref: ContentRef?,
        unavailableKey: String,
        resolve: suspend () -> T?,
        action: suspend (T) -> Unit,
    ) {
        if (ref != null && (ui as? SpotifyHomeUi.Ready)?.content?.resolving == ref) return
        cancelSelection()
        setResolving(ref)
        val generation = selectionGeneration
        selectionJob = scope.launch {
            try {
                val resolved = withTimeoutOrNull(SPOTIFY_HOME_RESOLVE_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { resolve() }
                }
                if (resolved == null) {
                    graph.report(DesktopStrings[unavailableKey])
                } else {
                    setResolving(null)
                    scope.launch {
                        try {
                            action(resolved)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            graph.report(DesktopStrings[unavailableKey])
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report(DesktopStrings[unavailableKey])
            } finally {
                if (selectionGeneration == generation) setResolving(null)
            }
        }
    }

    private suspend fun resolveAlbumTarget(item: ContentItem): SpotifyHomeTarget? {
        val query = listOf(item.title, item.artists.firstOrNull()?.name.orEmpty()).filter(String::isNotBlank).joinToString(" ")
        searchYouTube<AlbumItem>(query, YouTube.SearchFilter.FILTER_ALBUM)
            ?.browseId?.takeIf(String::isNotBlank)
            ?.let { return SpotifyHomeTarget.AlbumPage(it) }
        val song = searchYouTube<SongItem>(query, YouTube.SearchFilter.FILTER_SONG)
        if (song != null) {
            song.album?.id?.takeIf(String::isNotBlank)?.let { return SpotifyHomeTarget.AlbumPage(it) }
            return SpotifyHomeTarget.Song(song)
        }
        return accounts.catalogCall { Spotify.album(item.ref.id).getOrNull()?.tracks?.items.orEmpty() }
            .takeIf { it.isNotEmpty() }
            ?.let { SpotifyHomeTarget.SpotifyTracks(item.ref.id, it) }
    }

    private suspend fun resolveArtistTarget(item: ContentItem): SpotifyHomeTarget? =
        searchYouTube<ArtistItem>(item.title, YouTube.SearchFilter.FILTER_ARTIST)
            ?.id?.takeIf(String::isNotBlank)
            ?.let { SpotifyHomeTarget.ArtistPage(it) }

    private suspend fun openTarget(target: SpotifyHomeTarget) {
        when (target) {
            is SpotifyHomeTarget.AlbumPage ->
                openRoute(ArchiveRoutes.content(ContentRef(ContentProvider.YOUTUBE.id, ContentKind.ALBUM, target.browseId)))
            is SpotifyHomeTarget.ArtistPage ->
                openRoute(ArchiveRoutes.content(ContentRef(ContentProvider.YOUTUBE.id, ContentKind.ARTIST, target.id)))
            is SpotifyHomeTarget.Song -> playYouTubeSong(target.song)
            is SpotifyHomeTarget.SpotifyTracks ->
                accounts.playTrackList("spotify_album:${target.albumId}", target.tracks, 0)
        }
    }

    private suspend fun playYouTubeSong(song: SongItem) {
        val ref = ContentRef(ContentProvider.YOUTUBE.id, ContentKind.SONG, song.id)
        graph.queue.load(
            QueueLoadRequest(
                collection = ContentRequest(ContentProvider.YOUTUBE, ContentSurface.RADIO, parent = ref),
                kind = QueueCollectionKind.RADIO,
                start = QueueStart(ref),
                playWhenReady = true,
            ),
        )
    }

    private suspend inline fun <reified T : moe.rukamori.archivetune.innertube.models.YTItem> searchYouTube(
        query: String,
        filter: YouTube.SearchFilter,
    ): T? {
        val anonymous = YouTube.search(query, filter, useAccountContext = false)
        currentCoroutineContext().ensureActive()
        anonymous.getOrNull()?.items?.filterIsInstance<T>()?.firstOrNull()?.let { return it }
        val fallback = YouTube.search(query, filter)
        currentCoroutineContext().ensureActive()
        return fallback.getOrThrow().items.filterIsInstance<T>().firstOrNull()
    }

    private suspend fun fetchHome(cached: SpotifyHomeSnapshot?): SpotifyHomeSnapshot = coroutineScope {
        val topTracksDeferred = async { accounts.catalogCall { Spotify.topTracks(limit = 20) } }
        val newReleasesDeferred = async { accounts.catalogCall { Spotify.newReleases(limit = 20) } }
        val homeDeferred = async { accounts.catalogCall { Spotify.home(sectionItemsLimit = 10) } }
        val topArtistsDeferred = async { accounts.catalogCall { Spotify.topArtists(limit = 20) } }

        val topTracks = topTracksDeferred.await().getOrNull()?.items.orEmpty()
        val displayedTopTracks = topTracks.ifEmpty { cached?.topTracks.orEmpty() }
        val topArtists = topArtistsDeferred.await().getOrNull()?.items.orEmpty().ifEmpty { cached?.frequentArtists.orEmpty() }
        val shelves = mutableListOf<SpotifyHomeShelf>()
        if (displayedTopTracks.isNotEmpty()) {
            shelves += SpotifyHomeShelf.Tracks(
                "spotify_top_tracks",
                SpotifyShelfTitle.TopTracks,
                trackRows(displayedTopTracks, "top"),
            )
        }
        val newReleases = newReleasesDeferred.await().getOrNull()?.albums?.items.orEmpty()
        if (newReleases.isNotEmpty()) {
            shelves += SpotifyHomeShelf.Cards("spotify_new_releases", SpotifyShelfTitle.NewReleases, albumRows(newReleases, "new"))
        }
        val feed = homeDeferred.await().getOrNull()
        var recentItems = emptyList<SpotifyRecentItem>()
        feed?.sections?.forEach { section ->
            if (section.sectionUri.contains("recent", ignoreCase = true)) {
                recentItems = section.items.mapNotNull(::recentItem)
            } else if (!section.title.isNullOrBlank() && section.items.isNotEmpty()) {
                val title = section.title ?: return@forEach
                shelves += SpotifyHomeShelf.Cards(
                    key = section.sectionUri.ifBlank { title },
                    title = shelfTitle(title),
                    items = section.items.mapIndexedNotNull { index, item -> feedOccurrence(item, section.sectionUri, index) },
                )
            }
        }
        currentCoroutineContext().ensureActive()
        SpotifyHomeSnapshot(recentItems, topTracks, topArtists, shelves)
    }

    private fun trackRows(tracks: List<SpotifyTrack>, shelf: String): List<ContentOccurrence> =
        tracks.mapIndexed { index, track ->
            val occurrence = ContentOccurrence("spotify:home:$shelf:$index:${track.id}", accounts.spotifyTrackItem(track))
            trackRows += occurrence.occurrenceId to track
            occurrence
        }

    private fun albumRows(albums: List<SpotifyAlbum>, shelf: String): List<ContentOccurrence> =
        albums.mapIndexed { index, album ->
            ContentOccurrence("spotify:home:$shelf:$index:${album.id}", albumItem(album))
        }

    private fun feedOccurrence(item: SpotifyHomeFeedItem, shelf: String, index: Int): ContentOccurrence? =
        when (item) {
            is SpotifyHomeFeedItem.Playlist -> ContentOccurrence(
                "spotify:home:$shelf:$index:${item.id}",
                ContentItem(
                    ref = ContentRef(ContentProvider.SPOTIFY_CATALOG.id, ContentKind.PLAYLIST, item.id),
                    title = item.name,
                    subtitle = listOfNotNull(
                        item.ownerName,
                        item.totalCount.takeIf { it > 0 }?.toString(),
                    ).joinToString(" • "),
                    artworkUri = item.imageUrl,
                ),
            )
            is SpotifyHomeFeedItem.Album -> ContentOccurrence(
                "spotify:home:$shelf:$index:${item.id}",
                albumItem(item),
            )
            is SpotifyHomeFeedItem.Artist -> ContentOccurrence(
                "spotify:home:$shelf:$index:${item.id}",
                ContentItem(
                    ref = ContentRef(ContentProvider.SPOTIFY_CATALOG.id, ContentKind.ARTIST, item.id),
                    title = item.name,
                    artworkUri = item.imageUrl,
                ),
            )
        }

    private fun recentItem(item: SpotifyHomeFeedItem): SpotifyRecentItem? =
        when (item) {
            is SpotifyHomeFeedItem.Playlist -> SpotifyRecentItem(
                kind = "playlist", id = item.id, name = item.name, imageUrl = item.imageUrl,
            )
            is SpotifyHomeFeedItem.Album -> SpotifyRecentItem(
                kind = "album", id = item.id, name = item.name, imageUrl = item.imageUrl,
                artists = item.artists.map { SpotifyArtist(id = it.id.orEmpty(), name = it.name, uri = it.uri) },
            )
            else -> null
        }

    private fun albumItem(album: SpotifyAlbum): ContentItem =
        ContentItem(
            ref = ContentRef(ContentProvider.SPOTIFY_CATALOG.id, ContentKind.ALBUM, album.id),
            title = album.name,
            subtitle = album.artists.joinToString(", ") { it.name },
            artworkUri = album.images.maxByOrNull { it.width ?: 0 }?.url,
            artists = album.artists.mapNotNull { artist ->
                artist.name.takeIf(String::isNotBlank)?.let {
                    moe.rukamori.archivetune.shared.domain.repository.ContentCredit(it, artist.id?.takeIf(String::isNotBlank))
                }
            },
        )

    private fun albumItem(album: SpotifyHomeFeedItem.Album): ContentItem =
        ContentItem(
            ref = ContentRef(ContentProvider.SPOTIFY_CATALOG.id, ContentKind.ALBUM, album.id),
            title = album.name,
            subtitle = album.artists.joinToString(", ") { it.name },
            artworkUri = album.imageUrl,
            artists = album.artists.mapNotNull { artist ->
                artist.name.takeIf(String::isNotBlank)?.let {
                    moe.rukamori.archivetune.shared.domain.repository.ContentCredit(it, artist.id?.takeIf(String::isNotBlank))
                }
            },
        )

    private fun shelfTitle(raw: String): SpotifyShelfTitle =
        when {
            raw.startsWith("spotify_because_you_like:") ->
                SpotifyShelfTitle.BecauseYouLike(raw.removePrefix("spotify_because_you_like:"))
            raw == "spotify_top_tracks" -> SpotifyShelfTitle.TopTracks
            raw == "spotify_top_artists" -> SpotifyShelfTitle.TopArtists
            raw == "spotify_made_for_you" -> SpotifyShelfTitle.MadeForYou
            raw == "spotify_discover" -> SpotifyShelfTitle.Discover
            raw == "spotify_your_playlists" -> SpotifyShelfTitle.YourPlaylists
            raw == "spotify_new_releases" -> SpotifyShelfTitle.NewReleases
            else -> SpotifyShelfTitle.Plain(raw)
        }

    private fun restoreProfile(): SpotifyHomeSnapshot? {
        val prefs = snapshot()
        val recent = prefs[InlineSpotifyProfileRecentItemsKey].orEmpty().let(::decodeRecentItems)
        val tracks = prefs[InlineSpotifyProfileTopTracksKey].orEmpty().let { raw ->
            runCatching { homeJson.decodeFromString(ListSerializer(serializer<SpotifyTrack>()), raw) }.getOrNull().orEmpty()
        }
        val artists = prefs[InlineSpotifyProfileArtistsKey].orEmpty().let { raw ->
            runCatching { homeJson.decodeFromString(ListSerializer(serializer<SpotifyArtist>()), raw) }.getOrNull().orEmpty()
        }
        return if (recent.isEmpty() && tracks.isEmpty() && artists.isEmpty()) {
            null
        } else {
            SpotifyHomeSnapshot(
                recentItems = recent,
                topTracks = tracks,
                frequentArtists = artists,
                shelves = tracks.takeIf { it.isNotEmpty() }?.let {
                    listOf(SpotifyHomeShelf.Tracks("spotify_top_tracks", SpotifyShelfTitle.TopTracks, trackRows(it, "top")))
                }.orEmpty(),
            )
        }
    }

    private suspend fun persistProfile(snapshotData: SpotifyHomeSnapshot) {
        graph.settings.edit {
            this[InlineSpotifyProfileCacheTsKey] = System.currentTimeMillis()
            if (snapshotData.recentItems.isNotEmpty()) {
                this[InlineSpotifyProfileRecentItemsKey] =
                    encodeRecentItems(snapshotData.recentItems).toString()
            }
            if (snapshotData.topTracks.isNotEmpty()) {
                this[InlineSpotifyProfileTopTracksKey] =
                    homeJson.encodeToString(ListSerializer(serializer<SpotifyTrack>()), snapshotData.topTracks)
            }
            if (snapshotData.frequentArtists.isNotEmpty()) {
                this[InlineSpotifyProfileArtistsKey] =
                    homeJson.encodeToString(ListSerializer(serializer<SpotifyArtist>()), snapshotData.frequentArtists)
            }
        }
    }

    private fun markRefreshing(value: Boolean) {
        refreshing = value
        val ready = ui as? SpotifyHomeUi.Ready ?: return
        ui = ready.copy(content = ready.content.copy(isRefreshing = value))
    }

    private fun setResolving(ref: ContentRef?) {
        val ready = ui as? SpotifyHomeUi.Ready ?: return
        ui = ready.copy(content = ready.content.copy(resolving = ref))
    }

    private fun SpotifyHomeSnapshot.toUi(): SpotifyHomeUi =
        if (isBlank()) {
            SpotifyHomeUi.Empty
        } else {
            SpotifyHomeUi.Ready(
                SpotifyHomeContent(
                    recentItems = recentItems.map(::recentContentItem),
                    frequentArtists = frequentArtists.map(::artistContentItem),
                    shelves = shelves,
                    isRefreshing = refreshing,
                ),
            )
        }

    private fun SpotifyHomeSnapshot.isBlank(): Boolean = recentItems.isEmpty() && shelves.isEmpty() && frequentArtists.isEmpty()

    private fun recentContentItem(item: SpotifyRecentItem): ContentItem =
        ContentItem(
            ref = ContentRef(
                ContentProvider.SPOTIFY_CATALOG.id,
                if (item.kind == "playlist") ContentKind.PLAYLIST else ContentKind.ALBUM,
                item.id,
            ),
            title = item.name,
            subtitle = item.artists.joinToString(", ") { it.name },
            artworkUri = item.imageUrl,
            artists = item.artists.mapNotNull { artist ->
                artist.name.takeIf(String::isNotBlank)?.let {
                    moe.rukamori.archivetune.shared.domain.repository.ContentCredit(it, artist.id.takeIf(String::isNotBlank))
                }
            },
        )

    private fun artistContentItem(artist: SpotifyArtist): ContentItem =
        ContentItem(
            ref = ContentRef(ContentProvider.SPOTIFY_CATALOG.id, ContentKind.ARTIST, artist.id),
            title = artist.name,
            artworkUri = SpotifyMapper.getArtistThumbnail(artist),
        )

    private fun snapshot(): PreferenceSnapshot = graph.settings.snapshots.value ?: PreferenceSnapshot.EMPTY
}

private const val SPOTIFY_HOME_RESOLVE_TIMEOUT_MS = 20_000L
private const val SPOTIFY_HOME_DJ_PLAYLIST_ID = "37i9dQZF1EYkqdzj48dyYq"
private val homeJson = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

private data class SpotifyRecentItem(
    val kind: String,
    val id: String,
    val name: String,
    val imageUrl: String? = null,
    val artists: List<SpotifyArtist> = emptyList(),
)

private data class SpotifyHomeSnapshot(
    val recentItems: List<SpotifyRecentItem>,
    val topTracks: List<SpotifyTrack>,
    val frequentArtists: List<SpotifyArtist>,
    val shelves: List<SpotifyHomeShelf>,
)

private sealed interface SpotifyHomeTarget {
    data class AlbumPage(val browseId: String) : SpotifyHomeTarget
    data class ArtistPage(val id: String) : SpotifyHomeTarget
    data class Song(val song: SongItem) : SpotifyHomeTarget
    data class SpotifyTracks(val albumId: String, val tracks: List<SpotifyTrack>) : SpotifyHomeTarget
}

private fun encodeRecentItems(items: List<SpotifyRecentItem>): JsonArray = buildJsonArray {
    items.forEach { item ->
        add(
            buildJsonObject {
                put("kind", item.kind)
                put("id", item.id)
                put("name", item.name)
                item.imageUrl?.let { put("imageUrl", it) }
                put(
                    "artists",
                    buildJsonArray {
                        item.artists.forEach { artist ->
                            add(
                                buildJsonObject {
                                    put("id", artist.id)
                                    put("name", artist.name)
                                    artist.uri?.let { put("uri", it) }
                                },
                            )
                        }
                    },
                )
            },
        )
    }
}

private fun decodeRecentItems(raw: String): List<SpotifyRecentItem> {
    if (raw.isBlank()) return emptyList()
    return runCatching {
        homeJson.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val kind = item["kind"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val id = item["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = item["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val artists = item["artists"]?.jsonArray?.mapNotNull { artistElement ->
                val artist = artistElement as? JsonObject ?: return@mapNotNull null
                SpotifyArtist(
                    id = artist["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    name = artist["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    uri = artist["uri"]?.jsonPrimitive?.contentOrNull,
                )
            }.orEmpty()
            SpotifyRecentItem(
                kind = kind,
                id = id,
                name = name,
                imageUrl = item["imageUrl"]?.jsonPrimitive?.contentOrNull,
                artists = artists,
            )
        }
    }.getOrDefault(emptyList())
}
