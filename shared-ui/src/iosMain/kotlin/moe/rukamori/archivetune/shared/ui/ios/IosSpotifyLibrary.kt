package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.rukamori.archivetune.shared.data.playlistimport.YouTubePlaylistImportSearch
import moe.rukamori.archivetune.shared.domain.library.DefaultLibraryFilterOrder
import moe.rukamori.archivetune.shared.domain.library.LibraryDefaults
import moe.rukamori.archivetune.shared.domain.library.LibraryFilter
import moe.rukamori.archivetune.shared.domain.library.LibrarySource
import moe.rukamori.archivetune.shared.domain.library.toLibraryFilterOrder
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSearchFilter
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface
import moe.rukamori.archivetune.shared.domain.repository.HistoryOrigin
import moe.rukamori.archivetune.shared.domain.repository.QueueStart
import moe.rukamori.archivetune.shared.domain.repository.hasBlockedArtist
import moe.rukamori.archivetune.shared.domain.settings.ChipSortTypeKey
import moe.rukamori.archivetune.shared.domain.settings.HideAiMixKey
import moe.rukamori.archivetune.shared.domain.settings.HideCachedCardKey
import moe.rukamori.archivetune.shared.domain.settings.HideLikedSongsCardKey
import moe.rukamori.archivetune.shared.domain.settings.HideLocalFilesCardKey
import moe.rukamori.archivetune.shared.domain.settings.HideOfflineCardKey
import moe.rukamori.archivetune.shared.domain.settings.HideTop50CardKey
import moe.rukamori.archivetune.shared.domain.settings.LibraryChipOrderKey
import moe.rukamori.archivetune.shared.domain.settings.LibrarySourceKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.ShowSpotifyPlaylistsKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifySpDcKey
import moe.rukamori.archivetune.shared.domain.settings.TopSize
import moe.rukamori.archivetune.shared.domain.settings.toEnum
import moe.rukamori.archivetune.shared.ui.IosHost
import moe.rukamori.archivetune.shared.ui.host.hostCatalogDetailPage
import moe.rukamori.archivetune.shared.ui.host.hostLibraryPage
import moe.rukamori.archivetune.shared.ui.host.queuedEntry
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveDestination
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoute
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoutes
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.ios_library_source_spotify
import moe.rukamori.archivetune.shared.ui.resources.ios_library_source_ytm
import moe.rukamori.archivetune.shared.ui.resources.ios_library_top_mixes_unavailable
import moe.rukamori.archivetune.shared.ui.resources.ios_spotify_liked_songs
import moe.rukamori.archivetune.shared.ui.resources.ios_spotify_your_artists
import moe.rukamori.archivetune.shared.ui.resources.ios_spotify_your_playlists
import moe.rukamori.archivetune.shared.ui.screens.ContentActions
import moe.rukamori.archivetune.shared.ui.screens.DetailActions
import moe.rukamori.archivetune.shared.ui.screens.DetailKind
import moe.rukamori.archivetune.shared.ui.screens.LibraryMixHeaderActions
import moe.rukamori.archivetune.shared.ui.screens.LibraryMixHeaderBinding
import moe.rukamori.archivetune.shared.ui.screens.LibraryMixHeaderUi
import moe.rukamori.archivetune.shared.ui.screens.LibraryMixRailUi
import moe.rukamori.archivetune.shared.ui.screens.LibraryShortcutsUi
import moe.rukamori.archivetune.shared.ui.screens.LibraryTopMixesUi
import moe.rukamori.archivetune.shared.ui.screens.PagedContentState
import org.jetbrains.compose.resources.stringResource

private const val IOS_SPOTIFY_LIKED_SONGS_ID = "__spotify_liked_songs__"
private const val IOS_SPOTIFY_DEFAULT_TOP_SIZE = 50
private const val IOS_SPOTIFY_RECENT_LIMIT = 15

internal fun PreferenceSnapshot?.iosSpotifyLibraryAvailable(): Boolean =
    this != null && !get(SpotifySpDcKey).isNullOrBlank() && (get(ShowSpotifyPlaylistsKey) ?: true)

@Composable
internal fun IosLibrarySourceSelector(graph: IosSessionGraph) {
    val snapshot by graph.settings.snapshots.collectAsState()
    val available = snapshot.iosSpotifyLibraryAvailable()
    if (!available) return
    val stored = snapshot?.get(LibrarySourceKey).toEnum(LibraryDefaults.source)
    val selected = stored.resolved(available)
    Row(
        Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == LibrarySource.YTM,
            onClick = { graph.scope.launch { graph.settings.edit { this[LibrarySourceKey] = LibrarySource.YTM.name } } },
            label = { Text(stringResource(Res.string.ios_library_source_ytm)) },
        )
        FilterChip(
            selected = selected == LibrarySource.SPOTIFY,
            onClick = { graph.scope.launch { graph.settings.edit { this[LibrarySourceKey] = LibrarySource.SPOTIFY.name } } },
            label = { Text(stringResource(Res.string.ios_library_source_spotify)) },
        )
    }
}

@Composable
internal fun iosLibraryOrSpotifyPage(
    graph: IosSessionGraph,
    openRoute: (String) -> Unit,
): ArchiveGraphPage {
    val snapshot by graph.settings.snapshots.collectAsState()
    val stored = snapshot?.get(LibrarySourceKey).toEnum(LibraryDefaults.source)
    return when (stored.resolved(snapshot.iosSpotifyLibraryAvailable())) {
        LibrarySource.YTM ->
            hostLibraryPage(graph.pageOwners, stringResource(Res.string.ios_library_top_mixes_unavailable), openRoute)
        LibrarySource.SPOTIFY ->
            iosSpotifyLibraryPage(graph, snapshot, openRoute)
    }
}

@Composable
private fun iosSpotifyLibraryPage(
    graph: IosSessionGraph,
    snapshot: PreferenceSnapshot?,
    openRoute: (String) -> Unit,
): ArchiveGraphPage {
    var chosen by remember { mutableStateOf<LibraryFilter?>(null) }
    var attempt by remember { mutableStateOf(0) }
    val order = snapshot?.get(LibraryChipOrderKey)?.toLibraryFilterOrder() ?: DefaultLibraryFilterOrder
    val defaultFilter = snapshot?.get(ChipSortTypeKey).toEnum(LibraryDefaults.chipSortType)
    val filter = chosen ?: defaultFilter.takeIf { it in order } ?: order.first()
    val accountKey = snapshot?.get(SpotifySpDcKey).orEmpty()
    val loaded = remember(accountKey) { mutableStateMapOf<LibraryFilter, List<ContentOccurrence>>() }
    var mixPlaylists by remember(accountKey) { mutableStateOf<List<ContentOccurrence>>(emptyList()) }
    var mixArtists by remember(accountKey) { mutableStateOf<List<ContentOccurrence>>(emptyList()) }
    var loading by remember(accountKey) { mutableStateOf(true) }
    var failed by remember(accountKey) { mutableStateOf(false) }
    val client = remember(graph) { IosSpotifyRestClient(graph) }
    val request = ContentRequest(ContentProvider.SPOTIFY_CATALOG, filter.toSurface())

    LaunchedEffect(filter, attempt, accountKey) {
        if (filter == LibraryFilter.LIBRARY) {
            loading = false
            failed = false
            runCatching { client.fetchPlaylists().take(8) }.onSuccess { mixPlaylists = it }
            runCatching { client.fetchArtists().take(10) }.onSuccess { mixArtists = it }
            return@LaunchedEffect
        }
        if (loaded[filter] != null && attempt == 0) {
            loading = false
            failed = false
            return@LaunchedEffect
        }
        loading = true
        failed = false
        try {
            val section = when (filter) {
                LibraryFilter.PLAYLISTS -> client.fetchPlaylists()
                LibraryFilter.SONGS -> client.fetchLikedTracks()
                LibraryFilter.ARTISTS -> client.fetchArtists()
                LibraryFilter.ALBUMS -> client.fetchAlbums()
                LibraryFilter.LIBRARY -> emptyList()
            }
            loaded[filter] = section
            if (filter == LibraryFilter.SONGS) IosSpotifySongCache.record(request, section)
            loading = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            graph.report("spotify library ${filter.name.lowercase()} failed: ${failure::class.simpleName}")
            loading = false
            failed = true
        }
    }

    val myTopSize = snapshot?.takeUnless { it.get(HideTop50CardKey) == true }
        ?.let { it.get(TopSize)?.trim()?.toIntOrNull()?.takeIf { n -> n > 0 } ?: IOS_SPOTIFY_DEFAULT_TOP_SIZE }
    return ArchiveGraphPage.Library(
        filter = filter,
        order = order,
        filterChange = { chosen = it },
        page = PagedContentState(
            request = request,
            items = loaded[filter].orEmpty(),
            loading = if (filter == LibraryFilter.LIBRARY) false else loading,
            initialized = filter == LibraryFilter.LIBRARY || !loading || failed,
            failed = filter != LibraryFilter.LIBRARY && failed,
        ),
        loadMore = {},
        retry = { attempt += 1 },
        myTopSize = myTopSize,
        mixHeader = if (filter == LibraryFilter.LIBRARY) {
            iosSpotifyMixHeader(graph, snapshot, myTopSize, attempt, mixPlaylists, mixArtists, openRoute, { chosen = it }) {
                attempt += 1
            }
        } else {
            null
        },
    )
}

@Composable
private fun iosSpotifyMixHeader(
    graph: IosSessionGraph,
    snapshot: PreferenceSnapshot?,
    myTopSize: Int?,
    attempt: Int,
    playlists: List<ContentOccurrence>,
    artists: List<ContentOccurrence>,
    openRoute: (String) -> Unit,
    selectFilter: (LibraryFilter) -> Unit,
    retry: (String) -> Unit,
): LibraryMixHeaderBinding {
    val owners = graph.pageOwners
    val blocked by remember { owners.blocked }.collectAsState(emptySet())
    var recent by remember { mutableStateOf<List<ContentOccurrence>>(emptyList()) }
    LaunchedEffect(attempt, blocked) {
        recent = runCatching {
            owners.history.page(HistoryOrigin.LOCAL).events
                .map { it.item }
                .filter { it.item.ref.kind == ContentKind.SONG && !it.item.hasBlockedArtist(blocked) }
                .distinctBy { it.item.ref }
                .take(IOS_SPOTIFY_RECENT_LIMIT)
        }.getOrDefault(emptyList())
    }
    val spotifyLabel = stringResource(Res.string.ios_library_source_spotify)
    val mixesUnavailable = stringResource(Res.string.ios_library_top_mixes_unavailable)
    val yourPlaylistsTitle = stringResource(Res.string.ios_spotify_your_playlists)
    val yourArtistsTitle = stringResource(Res.string.ios_spotify_your_artists)
    val state = LibraryMixHeaderUi(
        shortcuts = LibraryShortcutsUi(
            liked = snapshot?.get(HideLikedSongsCardKey) != true,
            likedCount = null,
            offline = snapshot?.get(HideOfflineCardKey) != true,
            cached = snapshot?.get(HideCachedCardKey) != true,
            localFiles = snapshot?.get(HideLocalFilesCardKey) != true,
            myTopSize = myTopSize,
            likedDetail = spotifyLabel,
            likedRoute = "spotify_playlist/$IOS_SPOTIFY_LIKED_SONGS_ID",
        ),
        recent = recent,
        topMixes = if (snapshot?.get(HideAiMixKey) == true) null else LibraryTopMixesUi.Message(mixesUnavailable),
        rails = listOf(
            LibraryMixRailUi(key = "playlists", title = yourPlaylistsTitle, items = playlists, target = LibraryFilter.PLAYLISTS),
            LibraryMixRailUi(key = "artists", title = yourArtistsTitle, items = artists, target = LibraryFilter.ARTISTS),
        ),
    )
    val actions = remember(owners, openRoute, selectFilter, retry) {
        LibraryMixHeaderActions(
            openRoute = openRoute,
            play = { song ->
                owners.scope.launch {
                    val entry = song.queuedEntry()
                    owners.queue.replace(listOf(entry), entry.occurrenceId, playWhenReady = true)
                }
            },
            open = { occurrence -> openRoute(ArchiveRoutes.content(occurrence.item.ref)) },
            selectFilter = selectFilter,
            retryRail = retry,
        )
    }
    return LibraryMixHeaderBinding(state, actions, replacesRows = true)
}

@Composable
internal fun iosSpotifyPlaylistPage(
    graph: IosSessionGraph,
    playlistId: String,
    back: () -> Unit,
): ArchiveGraphPage {
    var attempt by remember { mutableStateOf(0) }
    var header by remember(playlistId) { mutableStateOf<ContentItem?>(null) }
    var items by remember(playlistId) { mutableStateOf<List<ContentOccurrence>>(emptyList()) }
    var loading by remember(playlistId) { mutableStateOf(true) }
    var failed by remember(playlistId) { mutableStateOf(false) }
    val client = remember(graph) { IosSpotifyRestClient(graph) }
    val likedSongsTitle = stringResource(Res.string.ios_spotify_liked_songs)
    val spotifySubtitle = stringResource(Res.string.ios_library_source_spotify)
    val request = ContentRequest(
        provider = ContentProvider.SPOTIFY_CATALOG,
        surface = ContentSurface.LIBRARY_PLAYLIST_ITEMS,
        parent = ContentRef(ContentProvider.SPOTIFY_CATALOG.id, ContentKind.PLAYLIST, playlistId),
    )
    LaunchedEffect(playlistId, attempt) {
        loading = true
        failed = false
        try {
            if (playlistId == IOS_SPOTIFY_LIKED_SONGS_ID) {
                val tracks = client.fetchLikedTracks()
                header = ContentItem(
                    ref = ContentRef(ContentProvider.SPOTIFY_CATALOG.id, ContentKind.PLAYLIST, playlistId),
                    title = likedSongsTitle,
                    subtitle = spotifySubtitle,
                )
                items = tracks
            } else {
                val loaded = client.fetchPlaylistDetail(playlistId, spotifySubtitle)
                header = loaded.first
                items = loaded.second
            }
            IosSpotifySongCache.record(request, items)
            loading = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            graph.report("spotify playlist not loaded: ${failure::class.simpleName}")
            loading = false
            failed = true
        }
    }
    return ArchiveGraphPage.Detail(
        kind = DetailKind.Playlist,
        header = header,
        page = PagedContentState(request = request, items = items, initialized = !loading || failed, loading = loading, failed = failed),
        actions = DetailActions(
            content = ContentActions(
                open = { occ ->
                    val idx = items.indexOfFirst { it.occurrenceId == occ.occurrenceId }
                    if (idx >= 0) {
                        iosPlaySpotifyTrackList(graph, playlistId, items, startIndex = idx, shuffled = false)
                    }
                },
                more = null,
                loadMore = {},
                retry = { attempt += 1 },
            ),
            back = back,
            playAll = {
                if (items.isNotEmpty()) {
                    iosPlaySpotifyTrackList(graph, playlistId, items, startIndex = 0, shuffled = false)
                }
            },
            shuffle = {
                if (items.isNotEmpty()) {
                    iosPlaySpotifyTrackList(graph, playlistId, items, startIndex = 0, shuffled = true)
                }
            },
            toggleLibrary = null,
            more = null,
        ),
    )
}

@Composable
internal fun iosSpotifyArtistPage(
    graph: IosSessionGraph,
    host: IosHost,
    catalogue: IosCatalogueOwners,
    route: ArchiveRoute,
    menu: IosItemMenuState,
    openRoute: (String) -> Unit,
    back: () -> Unit,
): ArchiveGraphPage {
    val artistId = route.arguments.getValue("artistId")
    var attempt by remember(artistId) { mutableStateOf(0) }
    var resolvedRef by remember(artistId) { mutableStateOf<ContentRef?>(null) }
    var loading by remember(artistId) { mutableStateOf(true) }
    var failed by remember(artistId) { mutableStateOf(false) }
    val spotifyRef = remember(artistId) { ContentRef(ContentProvider.SPOTIFY_CATALOG.id, ContentKind.ARTIST, artistId) }
    val spotifySubtitle = stringResource(Res.string.ios_library_source_spotify)
    LaunchedEffect(artistId, attempt) {
        loading = true
        failed = false
        try {
            val ytRef = iosResolveSpotifyCatalogRef(graph, spotifyRef, null) ?: error("No YouTube artist match")
            resolvedRef = ytRef
            loading = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            graph.report("spotify artist not resolved: ${failure::class.simpleName}")
            loading = false
            failed = true
        }
    }
    val resolved = resolvedRef
    if (resolved != null) {
        val ytRoute = remember(resolved) {
            ArchiveRoute(
                destination = ArchiveDestination.ARTIST,
                raw = ArchiveRoutes.artist(resolved.id),
                arguments = mapOf("artistId" to resolved.id, "provider" to ContentProvider.YOUTUBE.id),
            )
        }
        return iosArtistPage(graph, host, catalogue, ytRoute, menu, openRoute, back)
    }
    val request = ContentRequest(provider = ContentProvider.SPOTIFY_CATALOG, surface = ContentSurface.ARTIST, parent = spotifyRef)
    return ArchiveGraphPage.Detail(
        kind = DetailKind.Artist,
        header = ContentItem(spotifyRef, artistId, spotifySubtitle),
        page = PagedContentState(request = request, items = emptyList(), initialized = !loading || failed, loading = loading, failed = failed),
        actions = DetailActions(
            content = ContentActions(open = {}, more = null, loadMore = {}, retry = { attempt += 1 }),
            back = back,
            playAll = {},
            shuffle = {},
            toggleLibrary = null,
            more = null,
        ),
    )
}

@Composable
internal fun iosSpotifyAlbumPage(
    graph: IosSessionGraph,
    owners: IosCatalogOwners,
    route: ArchiveRoute,
    menu: IosItemMenuState,
    back: () -> Unit,
): ArchiveGraphPage {
    val albumId = route.arguments.getValue("albumId")
    var attempt by remember(albumId) { mutableStateOf(0) }
    var resolvedRef by remember(albumId) { mutableStateOf<ContentRef?>(null) }
    var fallbackHeader by remember(albumId) { mutableStateOf<ContentItem?>(null) }
    var fallbackItems by remember(albumId) { mutableStateOf<List<ContentOccurrence>>(emptyList()) }
    var loading by remember(albumId) { mutableStateOf(true) }
    var failed by remember(albumId) { mutableStateOf(false) }
    val spotifyRef = remember(albumId) { ContentRef(ContentProvider.SPOTIFY_CATALOG.id, ContentKind.ALBUM, albumId) }
    val spotifySubtitle = stringResource(Res.string.ios_library_source_spotify)
    val client = remember(graph) { IosSpotifyRestClient(graph) }
    val request = ContentRequest(provider = ContentProvider.SPOTIFY_CATALOG, surface = ContentSurface.ALBUM, parent = spotifyRef)
    LaunchedEffect(albumId, attempt) {
        loading = true
        failed = false
        try {
            val ytRef = iosResolveSpotifyCatalogRef(graph, spotifyRef, null)
            if (ytRef != null) {
                resolvedRef = ytRef
            } else {
                val detail = client.fetchAlbumDetail(albumId, spotifySubtitle)
                fallbackHeader = detail.first
                fallbackItems = detail.second
                IosSpotifySongCache.record(request, detail.second)
            }
            loading = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            graph.report("spotify album not loaded: ${failure::class.simpleName}")
            loading = false
            failed = true
        }
    }
    val resolved = resolvedRef
    if (resolved != null) {
        val ytRoute = remember(resolved) {
            ArchiveRoute(
                destination = ArchiveDestination.ALBUM,
                raw = ArchiveRoutes.album(resolved.id),
                arguments = mapOf("albumId" to resolved.id, "provider" to ContentProvider.YOUTUBE.id),
            )
        }
        hostCatalogDetailPage(owners.pages, ytRoute) { menu.open(it) }?.let { return it }
    }
    return ArchiveGraphPage.Detail(
        kind = DetailKind.Album,
        header = fallbackHeader,
        page = PagedContentState(request = request, items = fallbackItems, initialized = !loading || failed, loading = loading, failed = failed),
        actions = DetailActions(
            content = ContentActions(
                open = { occ ->
                    val idx = fallbackItems.indexOfFirst { it.occurrenceId == occ.occurrenceId }
                    if (idx >= 0) {
                        iosPlaySpotifyTrackList(graph, "album:$albumId", fallbackItems, startIndex = idx, shuffled = false)
                    }
                },
                more = null,
                loadMore = {},
                retry = { attempt += 1 },
            ),
            back = back,
            playAll = {
                if (fallbackItems.isNotEmpty()) {
                    iosPlaySpotifyTrackList(graph, "album:$albumId", fallbackItems, startIndex = 0, shuffled = false)
                }
            },
            shuffle = {
                if (fallbackItems.isNotEmpty()) {
                    iosPlaySpotifyTrackList(graph, "album:$albumId", fallbackItems, startIndex = 0, shuffled = true)
                }
            },
            toggleLibrary = null,
            more = null,
        ),
    )
}

internal fun iosPlaySpotifyOccurrence(
    graph: IosSessionGraph,
    collection: ContentRequest?,
    start: QueueStart,
): Boolean {
    if (collection?.provider != ContentProvider.SPOTIFY_CATALOG) return false
    val list = IosSpotifySongCache.itemsFor(collection)
    if (list.isEmpty()) return false
    val index = list.indexOfFirst { it.occurrenceId == start.occurrenceId }
        .takeIf { it >= 0 }
        ?: start.originalIndex?.takeIf { it in list.indices }
        ?: return false
    val key = collection.parent?.id ?: collection.surface.name.lowercase()
    iosPlaySpotifyTrackList(graph, key, list, startIndex = index, shuffled = false)
    return true
}

internal fun iosPlaySpotifyTrackList(
    graph: IosSessionGraph,
    collectionKey: String,
    tracks: List<ContentOccurrence>,
    startIndex: Int,
    shuffled: Boolean,
) {
    if (tracks.isEmpty()) return
    val ordered = if (shuffled) tracks.shuffled() else tracks
    val safeStart = if (shuffled) 0 else startIndex.coerceIn(0, ordered.lastIndex)
    val search = YouTubePlaylistImportSearch(graph.youtubeContent)
    graph.scope.launch {
        try {
            val firstResolved = withContext(Dispatchers.Default) {
                resolveOneSpotifySong(search, ordered[safeStart])
            } ?: return@launch graph.report("spotify song could not be matched on YouTube")
            val firstEntry = ContentOccurrence(
                occurrenceId = "spotify:$collectionKey:$safeStart:${firstResolved.ref.id}",
                item = firstResolved,
            ).queuedEntry()
            graph.queue.replace(listOf(firstEntry), firstEntry.occurrenceId, playWhenReady = true)
            if (ordered.size <= 1) return@launch
            val resolvedAll = withContext(Dispatchers.Default) {
                coroutineScope {
                    val out = arrayOfNulls<ContentItem>(ordered.size)
                    out[safeStart] = firstResolved
                    ordered.indices.filter { it != safeStart }.chunked(4).forEach { batch ->
                        batch.map { idx ->
                            async { idx to resolveOneSpotifySong(search, ordered[idx]) }
                        }.awaitAll().forEach { (idx, item) -> out[idx] = item }
                    }
                    out.toList()
                }
            }
            val entries = resolvedAll.mapIndexedNotNull { idx, item ->
                item?.let {
                    ContentOccurrence(
                        occurrenceId = "spotify:$collectionKey:$idx:${it.ref.id}",
                        item = it,
                    ).queuedEntry()
                }
            }
            if (entries.size > 1) {
                val startOcc = entries.firstOrNull { it.occurrenceId == firstEntry.occurrenceId }?.occurrenceId
                    ?: entries.first().occurrenceId
                graph.queue.replace(entries, startOcc, playWhenReady = true)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            graph.report("spotify queue play failed: ${failure::class.simpleName}")
        }
    }
}

private suspend fun resolveOneSpotifySong(
    search: YouTubePlaylistImportSearch,
    occurrence: ContentOccurrence,
): ContentItem? {
    val query = listOfNotNull(
        occurrence.item.subtitle.takeIf { it.isNotBlank() },
        occurrence.item.title.takeIf { it.isNotBlank() },
    ).joinToString(" ").trim()
    if (query.isBlank()) return null
    return runCatching { search.firstSong(query) }.getOrNull()
}

internal suspend fun iosResolveSpotifyCatalogRef(graph: IosSessionGraph, ref: ContentRef, titleHint: String?): ContentRef? =
    withContext(Dispatchers.Default) {
        val client = IosSpotifyRestClient(graph)
        val query = titleHint?.takeIf { it.isNotBlank() } ?: client.fetchEntityTitle(ref) ?: return@withContext null
        val filter = if (ref.kind == ContentKind.ARTIST) ContentSearchFilter.ARTISTS else ContentSearchFilter.ALBUMS
        val page = graph.youtubeContent.page(
            ContentRequest(provider = ContentProvider.YOUTUBE, surface = ContentSurface.SEARCH, text = query, filter = filter),
        )
        page.items.firstOrNull { it.item.ref.kind == ref.kind }?.item?.ref
    }

private object IosSpotifySongCache {
    private val lists = mutableMapOf<ContentRequest, List<ContentOccurrence>>()
    fun record(request: ContentRequest, items: List<ContentOccurrence>) {
        lists[request] = items
    }
    fun itemsFor(request: ContentRequest): List<ContentOccurrence> = lists[request].orEmpty()
}

private fun LibraryFilter.toSurface(): ContentSurface = when (this) {
    LibraryFilter.LIBRARY -> ContentSurface.LIBRARY_MIX
    LibraryFilter.PLAYLISTS -> ContentSurface.LIBRARY_PLAYLISTS
    LibraryFilter.SONGS -> ContentSurface.LIBRARY_SONGS
    LibraryFilter.ARTISTS -> ContentSurface.LIBRARY_ARTISTS
    LibraryFilter.ALBUMS -> ContentSurface.LIBRARY_ALBUMS
}

private class IosSpotifyRestClient(private val graph: IosSessionGraph) {
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun getJson(url: String): JsonObject = withContext(Dispatchers.Default) {
        val firstToken = iosValidSpotifyCatalogToken(graph)
        val firstResponse = graph.http.get(url) {
            header(HttpHeaders.Authorization, "Bearer $firstToken")
        }
        val response = if (firstResponse.status == HttpStatusCode.Unauthorized) {
            val freshToken = iosValidSpotifyCatalogToken(graph, forceRefresh = true)
            graph.http.get(url) {
                header(HttpHeaders.Authorization, "Bearer $freshToken")
            }
        } else {
            firstResponse
        }
        require(response.status.isSuccess()) { "Spotify HTTP ${response.status.value}" }
        json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    suspend fun fetchPlaylists(): List<ContentOccurrence> {
        val out = mutableListOf<ContentOccurrence>()
        var nextUrl: String? = "https://api.spotify.com/v1/me/playlists?limit=50"
        var pages = 0
        while (nextUrl != null && pages++ < 10) {
            val root = getJson(nextUrl)
            val items = root["items"] as? JsonArray ?: break
            items.forEach { el ->
                val obj = el as? JsonObject ?: return@forEach
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val owner = obj["owner"]?.jsonObject?.get("display_name")?.jsonPrimitive?.contentOrNull.orEmpty()
                val img = (obj["images"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                val idx = out.size
                out += ContentOccurrence(
                    "spotify:library:playlists:$idx:$id",
                    ContentItem(ContentRef(ContentProvider.SPOTIFY_CATALOG.id, ContentKind.PLAYLIST, id), name, owner, img),
                )
            }
            nextUrl = root["next"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
        }
        return out
    }

    suspend fun fetchLikedTracks(): List<ContentOccurrence> {
        val out = mutableListOf<ContentOccurrence>()
        var nextUrl: String? = "https://api.spotify.com/v1/me/tracks?limit=50"
        var pages = 0
        while (nextUrl != null && pages++ < 10) {
            val root = getJson(nextUrl)
            val items = root["items"] as? JsonArray ?: break
            items.forEach { el ->
                val track = (el as? JsonObject)?.get("track") as? JsonObject ?: return@forEach
                parseTrackOccurrence("spotify:library:songs:${out.size}", track)?.let { out += it }
            }
            nextUrl = root["next"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
        }
        return out
    }

    suspend fun fetchArtists(): List<ContentOccurrence> {
        val out = mutableListOf<ContentOccurrence>()
        var nextUrl: String? = "https://api.spotify.com/v1/me/following?type=artist&limit=50"
        var pages = 0
        while (nextUrl != null && pages++ < 10) {
            val artistsObj = getJson(nextUrl)["artists"]?.jsonObject ?: break
            val items = artistsObj["items"] as? JsonArray ?: break
            items.forEach { el ->
                val obj = el as? JsonObject ?: return@forEach
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val img = (obj["images"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                val idx = out.size
                out += ContentOccurrence(
                    "spotify:library:artists:$idx:$id",
                    ContentItem(ContentRef(ContentProvider.SPOTIFY_CATALOG.id, ContentKind.ARTIST, id), name, "", img),
                )
            }
            nextUrl = artistsObj["next"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
        }
        return out
    }

    suspend fun fetchAlbums(): List<ContentOccurrence> {
        val out = mutableListOf<ContentOccurrence>()
        var nextUrl: String? = "https://api.spotify.com/v1/me/albums?limit=50"
        var pages = 0
        while (nextUrl != null && pages++ < 10) {
            val root = getJson(nextUrl)
            val items = root["items"] as? JsonArray ?: break
            items.forEach { el ->
                val album = (el as? JsonObject)?.get("album") as? JsonObject ?: return@forEach
                val id = album["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val name = album["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val artists = (album["artists"] as? JsonArray)?.mapNotNull {
                    it.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                }?.joinToString(", ").orEmpty()
                val img = (album["images"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                val idx = out.size
                out += ContentOccurrence(
                    "spotify:library:albums:$idx:$id",
                    ContentItem(ContentRef(ContentProvider.SPOTIFY_CATALOG.id, ContentKind.ALBUM, id), name, artists, img),
                )
            }
            nextUrl = root["next"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
        }
        return out
    }

    suspend fun fetchPlaylistDetail(playlistId: String, defaultSubtitle: String): Pair<ContentItem, List<ContentOccurrence>> {
        val root = getJson("https://api.spotify.com/v1/playlists/$playlistId")
        val title = root["name"]?.jsonPrimitive?.contentOrNull ?: playlistId
        val owner = root["owner"]?.jsonObject?.get("display_name")?.jsonPrimitive?.contentOrNull ?: defaultSubtitle
        val img = (root["images"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
        val tracks = mutableListOf<ContentOccurrence>()
        val tracksObj = root["tracks"]?.jsonObject
        val firstItems = tracksObj?.get("items") as? JsonArray ?: JsonArray(emptyList())
        firstItems.forEach { el ->
            val track = (el as? JsonObject)?.get("track") as? JsonObject ?: return@forEach
            parseTrackOccurrence("spotify:playlist:$playlistId:${tracks.size}", track)?.let { tracks += it }
        }
        var nextUrl = tracksObj?.get("next")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
        var pages = 1
        while (nextUrl != null && pages++ < 10) {
            val pageObj = getJson(nextUrl)
            val pageItems = pageObj["items"] as? JsonArray ?: break
            pageItems.forEach { el ->
                val track = (el as? JsonObject)?.get("track") as? JsonObject ?: return@forEach
                parseTrackOccurrence("spotify:playlist:$playlistId:${tracks.size}", track)?.let { tracks += it }
            }
            nextUrl = pageObj["next"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
        }
        return ContentItem(
            ref = ContentRef(ContentProvider.SPOTIFY_CATALOG.id, ContentKind.PLAYLIST, playlistId),
            title = title,
            subtitle = owner,
            artworkUri = img,
        ) to tracks
    }

    suspend fun fetchAlbumDetail(albumId: String, defaultSubtitle: String): Pair<ContentItem, List<ContentOccurrence>> {
        val root = getJson("https://api.spotify.com/v1/albums/$albumId")
        val title = root["name"]?.jsonPrimitive?.contentOrNull ?: albumId
        val artists = (root["artists"] as? JsonArray)?.mapNotNull {
            it.jsonObject["name"]?.jsonPrimitive?.contentOrNull
        }?.joinToString(", ") ?: defaultSubtitle
        val img = (root["images"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
        val tracks = mutableListOf<ContentOccurrence>()
        val tracksObj = root["tracks"]?.jsonObject
        val firstItems = tracksObj?.get("items") as? JsonArray ?: JsonArray(emptyList())
        firstItems.forEach { el ->
            val track = el as? JsonObject ?: return@forEach
            parseTrackOccurrence("spotify:album:$albumId:${tracks.size}", track, img)?.let { tracks += it }
        }
        var nextUrl = tracksObj?.get("next")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
        var pages = 1
        while (nextUrl != null && pages++ < 10) {
            val pageObj = getJson(nextUrl)
            val pageItems = pageObj["items"] as? JsonArray ?: break
            pageItems.forEach { el ->
                val track = el as? JsonObject ?: return@forEach
                parseTrackOccurrence("spotify:album:$albumId:${tracks.size}", track, img)?.let { tracks += it }
            }
            nextUrl = pageObj["next"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
        }
        return ContentItem(
            ref = ContentRef(ContentProvider.SPOTIFY_CATALOG.id, ContentKind.ALBUM, albumId),
            title = title,
            subtitle = artists,
            artworkUri = img,
        ) to tracks
    }

    suspend fun fetchEntityTitle(ref: ContentRef): String? = runCatching {
        val path = if (ref.kind == ContentKind.ARTIST) "artists/${ref.id}" else "albums/${ref.id}"
        getJson("https://api.spotify.com/v1/$path")["name"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private fun parseTrackOccurrence(prefix: String, track: JsonObject, fallbackArtwork: String? = null): ContentOccurrence? {
        val id = track["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val name = track["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val artists = (track["artists"] as? JsonArray)?.mapNotNull {
            it.jsonObject["name"]?.jsonPrimitive?.contentOrNull
        }?.joinToString(", ").orEmpty()
        val img = (track["album"]?.jsonObject?.get("images") as? JsonArray)
            ?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
            ?: fallbackArtwork
        val durationMs = track["duration_ms"]?.jsonPrimitive?.intOrNull?.toLong()
        return ContentOccurrence(
            occurrenceId = "$prefix:$id",
            item = ContentItem(
                ref = ContentRef(ContentProvider.SPOTIFY_CATALOG.id, ContentKind.SONG, id),
                title = name,
                subtitle = artists,
                artworkUri = img,
                durationMs = durationMs,
            ),
        )
    }
}
