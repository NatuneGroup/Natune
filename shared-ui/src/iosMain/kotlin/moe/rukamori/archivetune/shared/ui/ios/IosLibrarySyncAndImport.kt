package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import moe.rukamori.archivetune.shared.data.persistence.RoomLibraryRepository
import moe.rukamori.archivetune.shared.data.playlistimport.PlaylistImportOwner
import moe.rukamori.archivetune.shared.data.playlistimport.YouTubePlaylistImportSearch
import moe.rukamori.archivetune.shared.data.provider.IosYouTubeBrowseTransport
import moe.rukamori.archivetune.shared.domain.library.AlbumSortType
import moe.rukamori.archivetune.shared.domain.library.ArtistSortType
import moe.rukamori.archivetune.shared.domain.library.LibrarySortState
import moe.rukamori.archivetune.shared.domain.platform.AccountAdmission
import moe.rukamori.archivetune.shared.domain.playlistimport.PlaylistImportFormat
import moe.rukamori.archivetune.shared.domain.playlistimport.PlaylistImportTarget
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistCompletion
import moe.rukamori.archivetune.shared.domain.queue.IncompleteFinitePlaylistException
import moe.rukamori.archivetune.shared.domain.repository.ContentAccountChanged
import moe.rukamori.archivetune.shared.domain.repository.ContentAccountRequired
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentQuery
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface
import moe.rukamori.archivetune.shared.domain.repository.LibraryMutation
import moe.rukamori.archivetune.shared.domain.settings.TidalAccessTokenKey
import moe.rukamori.archivetune.shared.domain.settings.TidalCountryCodeKey
import moe.rukamori.archivetune.shared.domain.settings.YtmSyncKey
import moe.rukamori.archivetune.shared.domain.settings.qobuzTokens
import moe.rukamori.archivetune.shared.domain.settings.youTubeSignedIn
import moe.rukamori.archivetune.shared.ui.IosHost
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoutes
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.ios_cross_service_import_close
import moe.rukamori.archivetune.shared.ui.resources.ios_cross_service_import_csv
import moe.rukamori.archivetune.shared.ui.resources.ios_cross_service_import_default_playlist
import moe.rukamori.archivetune.shared.ui.resources.ios_cross_service_import_done
import moe.rukamori.archivetune.shared.ui.resources.ios_cross_service_import_fetching
import moe.rukamori.archivetune.shared.ui.resources.ios_cross_service_import_hint
import moe.rukamori.archivetune.shared.ui.resources.ios_cross_service_import_open
import moe.rukamori.archivetune.shared.ui.resources.ios_cross_service_import_resolving
import moe.rukamori.archivetune.shared.ui.resources.ios_cross_service_import_start
import moe.rukamori.archivetune.shared.ui.resources.ios_cross_service_import_url_label
import moe.rukamori.archivetune.shared.ui.resources.m6_integration_cross_service_title
import moe.rukamori.archivetune.shared.ui.resources.m6_integration_import_failed
import org.jetbrains.compose.resources.stringResource
import platform.Foundation.NSUUID

/**
 * Pulls the signed-in YouTube Music library into the local Room library on iOS and syncs local playlists to/from
 * YouTube Music, matching DesktopYouTubeLibrarySync and Android's SyncUtils.
 */
internal class IosYouTubeLibrarySync(private val graph: IosSessionGraph) {
    private val mutex = Mutex()

    private fun refreshTransportSession() {
        (graph.youtubeContent.account?.transport as? IosYouTubeBrowseTransport)
            ?.refreshSession(graph.settings.snapshots.value)
    }

    private suspend fun admitted(): AccountAdmission? =
        try {
            graph.youtubeContent.account?.remoteAccount()
        } catch (_: ContentAccountRequired) {
            null
        }

    suspend fun performFullSync(authoritative: Boolean = false) {
        if (authoritative) mutex.lock() else if (!mutex.tryLock()) return
        try {
            refreshTransportSession()
            val admission = admitted() ?: return
            step("liked songs") { syncLikedSongs(admission, authoritative) }
            step("liked albums") { syncLikedAlbums(admission, authoritative) }
            step("artist subscriptions") { syncArtists(admission, authoritative) }
            step("saved playlists") { syncSavedPlaylists(admission) }
        } finally {
            mutex.unlock()
        }
    }

    suspend fun syncPlaylist(playlistId: String, title: String, videoIds: List<String>) {
        mutex.lock()
        try {
            refreshTransportSession()
            val admission = admitted() ?: throw ContentAccountRequired()
            val transport = graph.youtubeContent.account?.transport as? IosYouTubeBrowseTransport
                ?: throw IllegalStateException("YouTube transport unavailable")
            val linked = graph.library.observeLocalPlaylists().first()
                .firstOrNull {
                    it.occurrence.item.ref.kind == ContentKind.PLAYLIST &&
                        it.occurrence.item.ref.id == playlistId
                }?.browseId
            val browseId = linked ?: transport.createPlaylist(title)
            fence(admission)
            if (linked == null) {
                if (videoIds.isNotEmpty()) {
                    transport.addSongsToPlaylist(browseId, videoIds)
                }
                fence(admission)
                graph.library.setPlaylistRemote(playlistId, browseId, videoIds.size)
            } else {
                val remote = readRemotePlaylist(browseId)
                fence(admission)
                if (remote.isNotEmpty()) {
                    graph.library.saveItems(remote.map { it.item })
                    graph.library.mutate(LibraryMutation.ReplacePlaylistEntries(playlistId, remote))
                    graph.library.setPlaylistRemote(playlistId, browseId, remote.size)
                }
            }
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Pushes newly imported [addedVideoIds] for [playlistId] without pulling remote entries over local imports.
     * Unlinked playlists are created remotely; already-linked playlists receive the imported delta.
     */
    suspend fun pushImportedPlaylist(playlistId: String, title: String, addedVideoIds: List<String>) {
        if (addedVideoIds.isEmpty()) return
        mutex.lock()
        try {
            refreshTransportSession()
            val admission = admitted() ?: return
            val transport = graph.youtubeContent.account?.transport as? IosYouTubeBrowseTransport ?: return
            val existing = graph.library.observeLocalPlaylists().first()
                .firstOrNull {
                    it.occurrence.item.ref.kind == ContentKind.PLAYLIST &&
                        it.occurrence.item.ref.id == playlistId
                }
            val linked = existing?.browseId
            if (linked == null) {
                val localOccurrences = readLocalPlaylistOccurrences(playlistId)
                val initialVideoIds = localOccurrences.mapNotNull { occ ->
                    occ.item.ref.takeIf { it.provider == ContentProvider.YOUTUBE.id && it.kind == ContentKind.SONG }?.id
                }.ifEmpty { addedVideoIds }
                val browseId = transport.createPlaylist(title)
                fence(admission)
                if (initialVideoIds.isNotEmpty()) {
                    transport.addSongsToPlaylist(browseId, initialVideoIds)
                }
                fence(admission)
                graph.library.setPlaylistRemote(
                    playlistId,
                    browseId,
                    localOccurrences.size.takeIf { it > 0 } ?: initialVideoIds.size,
                )
            } else {
                transport.addSongsToPlaylist(linked, addedVideoIds)
                fence(admission)
                graph.library.setPlaylistRemote(playlistId, linked, existing.songCount)
            }
        } finally {
            mutex.unlock()
        }
    }

    internal suspend fun readLocalPlaylistOccurrences(playlistId: String): List<ContentOccurrence> {
        val query = ContentQuery(
            RoomLibraryRepository.PLAYLIST_ITEMS,
            parent = ContentRef(ContentProvider.LOCAL.id, ContentKind.PLAYLIST, playlistId),
        )
        val items = mutableListOf<ContentOccurrence>()
        val consumed = mutableSetOf<String>()
        var token: String? = null
        var requests = 0
        while (true) {
            if (++requests > FinitePlaylistCompletion.MAX_REQUESTS) throw IncompleteFinitePlaylistException()
            if (token != null && !consumed.add(token)) throw IncompleteFinitePlaylistException()
            val page = graph.library.page(query, token)
            val next = page.continuation
            FinitePlaylistCompletion.validateNext(
                token = next,
                consumed = consumed,
                songCount = page.items.size,
                initial = token == null,
            )
            items += page.items
            if (next == null) return items
            token = next
        }
    }

    internal suspend fun readRemotePlaylist(browseId: String): List<ContentOccurrence> {
        val request = ContentRequest(
            ContentProvider.YOUTUBE,
            ContentSurface.PLAYLIST,
            parent = ContentRef(ContentProvider.YOUTUBE.id, ContentKind.PLAYLIST, browseId),
        )
        val items = mutableListOf<ContentOccurrence>()
        val consumed = mutableSetOf<String>()
        var continuation: String? = null
        var rawNext: String? = null
        var requests = 0
        do {
            if (++requests > FinitePlaylistCompletion.MAX_REQUESTS) throw IncompleteFinitePlaylistException()
            val token = continuation
            val raw = rawNext
            if (raw != null && !consumed.add(raw)) throw IncompleteFinitePlaylistException()
            val page = graph.youtubeContent.page(request, token)
            rawNext = page.continuation?.let(::rawContinuationToken)
            FinitePlaylistCompletion.validateNext(rawNext, consumed, page.items.size, initial = requests == 1)
            items += page.items
            continuation = page.continuation
        } while (continuation != null)
        if (items.isEmpty()) throw IncompleteFinitePlaylistException("Remote playlist returned no items")
        return items
    }

    private suspend fun step(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            graph.report("YouTube $name were not synced: ${failure::class.simpleName}")
        }
    }

    private suspend fun syncLikedSongs(admission: AccountAdmission, authoritative: Boolean) {
        val remote = readRemote(ContentSurface.LIBRARY_LIKED, admission).filter { it.ref.kind == ContentKind.SONG }
        if (remote.isEmpty() && !authoritative) return
        val local = readLocalLiked()
        fence(admission)
        val remoteIds = remote.mapTo(hashSetOf()) { it.ref.id }
        if (authoritative) {
            local.filter { it.ref.provider == ContentProvider.YOUTUBE.id && it.ref.kind == ContentKind.SONG && it.ref.id !in remoteIds }
                .forEach { graph.library.mutate(LibraryMutation.Like(it.ref, liked = false)) }
        }
        val likedHere = local.mapTo(hashSetOf()) { it.ref }
        graph.library.likeAll(remote.filterNot { it.ref in likedHere }.reversed())
    }

    private suspend fun syncLikedAlbums(admission: AccountAdmission, authoritative: Boolean) {
        val remote = readRemote(ContentSurface.LIBRARY_ALBUMS, admission).filter { it.ref.kind == ContentKind.ALBUM }
        if (remote.isEmpty() && !authoritative) return
        val local = graph.library.observeLikedAlbums(
            LibrarySortState(AlbumSortType.NAME, false),
            naturalOrder(),
            flowOf(emptySet()),
        ).first().orEmpty().map { it.album }
        fence(admission)
        val remoteIds = remote.mapTo(hashSetOf()) { it.ref.id }
        local.filter { it.ref.provider == ContentProvider.YOUTUBE.id && it.ref.id !in remoteIds }
            .forEach { graph.library.mutate(LibraryMutation.LikeAlbum(it, liked = false)) }
        val likedHere = local.mapTo(hashSetOf()) { it.ref }
        remote.filterNot { it.ref in likedHere }.reversed()
            .forEach { graph.library.mutate(LibraryMutation.LikeAlbum(it, liked = true)) }
    }

    private suspend fun syncArtists(admission: AccountAdmission, authoritative: Boolean) {
        val remote = readRemote(ContentSurface.LIBRARY_ARTISTS, admission).filter { it.ref.kind == ContentKind.ARTIST }
        if (remote.isEmpty() && !authoritative) return
        val local = graph.library.observeLikedArtists(
            LibrarySortState(ArtistSortType.NAME, false),
            naturalOrder(),
            flowOf(emptySet()),
        ).first().orEmpty().map { it.artist }
        fence(admission)
        val remoteIds = remote.mapTo(hashSetOf()) { it.ref.id }
        local.filter { it.ref.provider == ContentProvider.YOUTUBE.id && it.ref.id !in remoteIds }
            .forEach { graph.library.mutate(LibraryMutation.SubscribeArtist(it, subscribed = false)) }
        val subscribedHere = local.mapTo(hashSetOf()) { it.ref }
        remote.filterNot { it.ref in subscribedHere }
            .forEach { graph.library.mutate(LibraryMutation.SubscribeArtist(it, subscribed = true)) }
    }

    private suspend fun syncSavedPlaylists(admission: AccountAdmission) {
        val remote = readRemote(ContentSurface.LIBRARY_PLAYLISTS, admission)
            .filter { it.ref.kind == ContentKind.PLAYLIST && it.ref.id != "LM" && it.ref.id != "SE" }
        if (remote.isEmpty()) return
        val local = graph.library.observeLocalPlaylists().first()
        fence(admission)
        val linkedByBrowseId = local.mapNotNull { entry -> entry.browseId?.let { it to entry.occurrence.item.ref.id } }.toMap()
        val existingIds = local.mapTo(hashSetOf()) { it.occurrence.item.ref.id }
        for (playlist in remote) {
            val browseId = playlist.ref.id
            val localId = linkedByBrowseId[browseId] ?: ("LPyt_$browseId")
            val remoteEntries = try {
                readRemotePlaylist(browseId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("YouTube playlist $browseId was not synced: ${failure::class.simpleName}")
                continue
            }
            fence(admission)
            if (remoteEntries.isEmpty()) continue
            if (localId !in existingIds) {
                graph.library.createImportedPlaylist(localId, playlist.title, remoteEntries, emptySet())
                existingIds += localId
            } else {
                graph.library.saveItems(remoteEntries.map { it.item })
                graph.library.mutate(LibraryMutation.ReplacePlaylistEntries(localId, remoteEntries))
            }
            graph.library.setPlaylistRemote(localId, browseId, remoteEntries.size)
        }
    }

    private suspend fun readRemote(surface: ContentSurface, admission: AccountAdmission): List<ContentItem> {
        val request = ContentRequest(ContentProvider.YOUTUBE, surface)
        val items = mutableListOf<ContentItem>()
        var continuation: String? = null
        var pages = 0
        do {
            if (++pages > MAX_LISTING_PAGES) throw IllegalStateException("Listing exceeded its page bound")
            val page = graph.youtubeContent.page(request, continuation)
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
            val page = graph.library.page(query, continuation)
            items += page.items.map { it.item }
            continuation = page.continuation
        } while (continuation != null)
        return items
    }

    private suspend fun fence(admission: AccountAdmission) {
        if (admitted() != admission) throw ContentAccountChanged()
    }

    private companion object {
        const val MAX_LISTING_PAGES = 400
    }
}

private fun rawContinuationToken(wrapped: String): String =
    wrapped.split('|', limit = 3).let { if (it.size == 3) it[2] else wrapped }

/** Cross-service playlist importer for iOS supporting YouTube Music, Spotify, Apple Music, Amazon Music, Tidal, Qobuz and Deezer. */
internal class IosCrossServiceImport(private val graph: IosSessionGraph) {
    data class ForeignTrack(val title: String, val artist: String?, val album: String? = null, val durationMs: Long? = null)
    data class ResolvedImport(val source: ImportSource, val sourcePlaylistId: String, val title: String, val tracks: List<ForeignTrack>)
    data class ImportResult(val playlistId: String?, val total: Int, val imported: Int)
    data class Credentials(val tidalAccessToken: String? = null, val tidalCountryCode: String = "US", val qobuzAppId: String? = null, val qobuzAuthToken: String? = null)

    enum class ImportSource {
        YOUTUBE_MUSIC, SPOTIFY, APPLE_MUSIC, AMAZON_MUSIC, TIDAL, QOBUZ, DEEZER, UNKNOWN
    }

    sealed interface ImportProgress {
        data class Fetching(val url: String) : ImportProgress
        data class Resolving(val processed: Int, val total: Int) : ImportProgress
        data class Done(val result: ImportResult) : ImportProgress
        data object Failed : ImportProgress
    }

    private val search = YouTubePlaylistImportSearch(graph.youtubeContent)

    fun detectSource(url: String): ImportSource = when {
        url.contains("music.youtube.com") || url.contains("youtube.com/playlist") -> ImportSource.YOUTUBE_MUSIC
        url.contains("spotify.com") || url.startsWith("spotify:") -> ImportSource.SPOTIFY
        url.contains("music.apple.com") -> ImportSource.APPLE_MUSIC
        url.contains("music.amazon.com") -> ImportSource.AMAZON_MUSIC
        url.contains("tidal.com") -> ImportSource.TIDAL
        url.contains("qobuz.com") -> ImportSource.QOBUZ
        url.contains("deezer.com") -> ImportSource.DEEZER
        else -> ImportSource.UNKNOWN
    }

    suspend fun import(
        url: String,
        defaultPlaylistName: String,
        onProgress: (ImportProgress) -> Unit,
    ): ImportResult = withContext(Dispatchers.Default) {
        onProgress(ImportProgress.Fetching(url))
        val source = detectSource(url)
        val credentials = loadCredentials()
        val (playlistName, sourceId, songs, totalCount) = if (source == ImportSource.YOUTUBE_MUSIC) {
            val playlistId = extractQuery(url, "list") ?: error("Missing 'list' query parameter in YouTube URL")
            val entries = graph.youtubeLibrarySync.readRemotePlaylist(playlistId).map { it.item }
            Quadruple(defaultPlaylistName, playlistId, entries, entries.size)
        } else {
            val resolved = fetchPlaylist(url, source, credentials, defaultPlaylistName)
            val matched = resolveToYouTubeMusic(resolved.tracks) { done, total ->
                onProgress(ImportProgress.Resolving(done, total))
            }
            Quadruple(resolved.title.ifBlank { defaultPlaylistName }, resolved.sourcePlaylistId, matched, resolved.tracks.size)
        }
        val playlistId = deterministicPlaylistId("import:${source.name}:$sourceId")
        val exists = runCatching { graph.library.item(ContentRef("local", ContentKind.PLAYLIST, playlistId)) }.isSuccess
        if (songs.isNotEmpty()) {
            val occurrences = songs.map { song ->
                ContentOccurrence("import:" + NSUUID().UUIDString.replace("-", "").take(24), song)
            }
            if (exists) {
                graph.library.mutate(LibraryMutation.RenamePlaylist(playlistId, playlistName))
                graph.library.appendImported(playlistId, occurrences, emptySet())
            } else {
                graph.library.createImportedPlaylist(playlistId, playlistName, occurrences, emptySet())
            }
            syncRemotePlaylistIfEnabled(playlistId, playlistName, songs.map { it.ref.id })
        }
        val targetPlaylistId = playlistId.takeIf { songs.isNotEmpty() }
        ImportResult(targetPlaylistId, totalCount, songs.size).also { onProgress(ImportProgress.Done(it)) }
    }

    suspend fun importCsv(
        host: IosHost,
        defaultPlaylistName: String,
        onProgress: (ImportProgress) -> Unit,
    ): ImportResult? {
        val handle = host.files.pickDocument(host.viewController) ?: return null
        return withContext(Dispatchers.Default) {
            val bytes = host.files.read(handle, 4L * 1024L * 1024L)
            val raw = bytes.decodeToString()
            val playlistId = deterministicPlaylistId("csv:${NSUUID().UUIDString}")
            val owner = PlaylistImportOwner(
                library = graph.library,
                search = search,
                newPlaylistId = { playlistId },
            )
            val parsed = owner.parse(PlaylistImportFormat.CSV, raw)
            val result = owner.importInto(parsed, PlaylistImportTarget.Created(defaultPlaylistName)) { progress ->
                onProgress(ImportProgress.Resolving(progress.processed, progress.total))
            }
            val createdId = playlistId.takeIf { result.imported > 0 }
            ImportResult(createdId, result.total, result.imported).also { onProgress(ImportProgress.Done(it)) }
        }
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private suspend fun loadCredentials(): Credentials {
        val prefs = graph.settings.snapshots.value
        val personalTidal = prefs?.get(TidalAccessTokenKey).orEmpty().ifBlank { null }
        val poolTidal = graph.playbackPool.tidalAccounts().firstOrNull()
        val personalQobuz = prefs?.qobuzTokens()?.firstOrNull { it.isComplete() }
        val poolQobuz = if (personalQobuz == null) graph.playbackPool.qobuzAccounts().firstOrNull() else null
        return Credentials(
            tidalAccessToken = personalTidal ?: poolTidal?.token,
            tidalCountryCode = prefs?.get(TidalCountryCodeKey).orEmpty().ifBlank { poolTidal?.countryCode } ?: "US",
            qobuzAppId = personalQobuz?.appId ?: poolQobuz?.appId,
            qobuzAuthToken = personalQobuz?.token ?: poolQobuz?.token,
        )
    }

    private suspend fun syncRemotePlaylistIfEnabled(playlistId: String, title: String, songIds: List<String>) {
        val prefs = graph.settings.snapshots.value
        if (prefs?.youTubeSignedIn() != true || (prefs[YtmSyncKey] ?: true) != true || songIds.isEmpty()) return
        runCatching { graph.youtubeLibrarySync.pushImportedPlaylist(playlistId, title, songIds) }
    }

    private suspend fun fetchPlaylist(
        url: String,
        source: ImportSource,
        credentials: Credentials,
        defaultTitle: String,
    ): ResolvedImport = when (source) {
        ImportSource.SPOTIFY -> fetchSpotifyPlaylist(url, defaultTitle)
        ImportSource.APPLE_MUSIC -> fetchAppleMusicPlaylist(url, defaultTitle)
        ImportSource.AMAZON_MUSIC -> fetchAmazonMusicPlaylist(url, defaultTitle)
        ImportSource.TIDAL -> fetchTidalPlaylist(url, credentials, defaultTitle)
        ImportSource.QOBUZ -> fetchQobuzPlaylist(url, credentials, defaultTitle)
        ImportSource.DEEZER -> fetchDeezerPlaylist(url, defaultTitle)
        else -> error("Unrecognized playlist URL")
    }

    private suspend fun fetchSpotifyPlaylist(url: String, defaultTitle: String): ResolvedImport {
        val id = Regex("spotify:playlist:([A-Za-z0-9]+)").find(url)?.groupValues?.get(1)
            ?: Regex("playlist[/:]([A-Za-z0-9]{16,})").find(url)?.groupValues?.get(1)
            ?: error("Couldn't extract Spotify playlist id from URL")
        val token = resolveSpotifyToken()
        if (token != null) {
            try {
                val apiResult = fetchSpotifyPlaylistViaApi(id, token, defaultTitle)
                if (apiResult.tracks.isNotEmpty()) return apiResult
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (incomplete: IncompleteFinitePlaylistException) {
                throw incomplete
            } catch (_: Exception) {
                // Fall back to public embed below when API auth fails.
            }
        }
        val html = fetchText("https://open.spotify.com/embed/playlist/$id")
        val entity = jsonObject(extractScriptJson(html, "__NEXT_DATA__")?.let(json::parseToJsonElement))
            ?.obj("props")?.obj("pageProps")?.obj("state")?.obj("data")?.obj("entity")
            ?: error("Spotify playlist is private or unavailable")
        val tracks = entity.array("trackList").orEmpty().mapNotNull {
            val obj = it as? JsonObject ?: return@mapNotNull null
            ForeignTrack(obj.string("title") ?: return@mapNotNull null, obj.string("subtitle"), durationMs = obj.long("duration").takeIf { d -> d > 0 })
        }
        return ResolvedImport(ImportSource.SPOTIFY, id, entity.string("name") ?: defaultTitle, tracks)
    }

    private suspend fun resolveSpotifyToken(): String? =
        runCatching { iosValidSpotifyCatalogToken(graph) }.getOrNull()

    private suspend fun fetchSpotifyPlaylistViaApi(id: String, token: String, defaultTitle: String): ResolvedImport {
        val headers = mapOf("Authorization" to "Bearer $token")
        val meta = runCatching { jsonObject(json.parseToJsonElement(fetchText("https://api.spotify.com/v1/playlists/$id", headers))) }.getOrNull()
        val tracks = mutableListOf<ForeignTrack>()
        var offset = 0
        var pages = 0
        while (pages < FinitePlaylistCompletion.MAX_REQUESTS) {
            pages++
            val page = jsonObject(json.parseToJsonElement(fetchText("https://api.spotify.com/v1/playlists/$id/tracks?limit=100&offset=$offset", headers)))
                ?: throw IncompleteFinitePlaylistException()
            val items = page.array("items") ?: throw IncompleteFinitePlaylistException()
            val total = page.long("total")
            if (items.isEmpty()) {
                if (offset < total) throw IncompleteFinitePlaylistException()
                break
            }
            items.forEach { el ->
                val track = (el as? JsonObject)?.obj("track") ?: return@forEach
                val name = track.string("name") ?: return@forEach
                val artist = track.array("artists")?.firstOrNull()?.let { (it as? JsonObject)?.string("name") }
                tracks += ForeignTrack(name, artist, track.obj("album")?.string("name"), track.long("duration_ms").takeIf { it > 0 })
            }
            offset += items.size
            val nextUrl = page.string("next")?.takeIf { it != "null" }
            if (nextUrl == null && offset >= total) break
            if (nextUrl == null && offset < total) throw IncompleteFinitePlaylistException()
        }
        if (pages >= FinitePlaylistCompletion.MAX_REQUESTS) {
            val checkPage = jsonObject(json.parseToJsonElement(fetchText("https://api.spotify.com/v1/playlists/$id/tracks?limit=1&offset=$offset", headers)))
            if ((checkPage?.long("total") ?: 0L) > offset) throw IncompleteFinitePlaylistException()
        }
        return ResolvedImport(ImportSource.SPOTIFY, id, meta?.string("name") ?: defaultTitle, tracks)
    }

    private suspend fun fetchAppleMusicPlaylist(url: String, defaultTitle: String): ResolvedImport {
        val html = fetchText(url)
        val tracks = mutableListOf<ForeignTrack>()
        extractScriptJson(html, "serialized-server-data")?.let { raw ->
            runCatching { collectAppleTracks(json.parseToJsonElement(raw), tracks) }
        }
        if (tracks.isEmpty()) {
            Regex("\\{\"name\":\"([^\"]{2,200})\",\"artistName\":\"([^\"]{2,200})\"").findAll(html).forEach {
                tracks += ForeignTrack(unescapeJson(it.groupValues[1]), unescapeJson(it.groupValues[2]))
            }
        }
        val title = Regex("<meta[^>]+property=\"og:title\"[^>]+content=\"([^\"]+)\"").find(html)?.groupValues?.get(1)?.let(::unescapeJson)
            ?: defaultTitle
        val id = Regex("playlist/[^/]+/pl\\.([a-zA-Z0-9.\\-]+)").find(url)?.groupValues?.get(1)?.let { "pl.$it" } ?: url
        return ResolvedImport(ImportSource.APPLE_MUSIC, id, title, tracks)
    }

    private fun collectAppleTracks(node: JsonElement?, into: MutableList<ForeignTrack>) {
        when (node) {
            is JsonObject -> {
                val title = node.string("title") ?: node.string("name")
                val artist = node.string("artistName")
                val duration = node.long("durationInMillis").takeIf { it > 0 }
                val kind = node.string("kind") ?: node.string("type")
                val isSongLike = duration != null || kind == "song" || kind == "songs" || node.containsKey("discNumber") || node.containsKey("trackNumber")
                if (title != null && artist != null && isSongLike) {
                    into += ForeignTrack(title, artist, durationMs = duration)
                }
                node.values.forEach { collectAppleTracks(it, into) }
            }
            is JsonArray -> node.forEach { collectAppleTracks(it, into) }
            else -> Unit
        }
    }

    private suspend fun fetchAmazonMusicPlaylist(url: String, defaultTitle: String): ResolvedImport {
        val html = fetchText(url)
        val tracks = Regex("\\{\"title\":\"([^\"]{2,120})\",\"artist\":\"([^\"]+)\"").findAll(html).map {
            ForeignTrack(unescapeJson(it.groupValues[1]), unescapeJson(it.groupValues[2]))
        }.toList().ifEmpty {
            Regex("\\{\"trackName\":\"([^\"]{2,120})\",\"artistName\":\"([^\"]+)\"").findAll(html).map {
                ForeignTrack(unescapeJson(it.groupValues[1]), unescapeJson(it.groupValues[2]))
            }.toList()
        }
        val title = Regex("<meta[^>]+property=\"og:title\"[^>]+content=\"([^\"]+)\"").find(html)?.groupValues?.get(1)?.let(::unescapeJson)
            ?: defaultTitle
        return ResolvedImport(ImportSource.AMAZON_MUSIC, Regex("playlists/([A-Z0-9]+)").find(url)?.groupValues?.get(1) ?: url, title, tracks)
    }

    private suspend fun fetchTidalPlaylist(url: String, credentials: Credentials, defaultTitle: String): ResolvedImport {
        val id = Regex("playlist/([a-f0-9\\-]{20,40})").find(url)?.groupValues?.get(1) ?: error("Couldn't extract Tidal playlist id")
        val token = credentials.tidalAccessToken?.takeIf(String::isNotBlank) ?: error("Tidal import needs a signed-in Tidal account")
        val country = credentials.tidalCountryCode.ifBlank { "US" }
        val headers = mapOf("Authorization" to "Bearer $token")
        val meta = runCatching { jsonObject(json.parseToJsonElement(fetchText("https://api.tidal.com/v1/playlists/$id?countryCode=$country", headers))) }.getOrNull()
        val tracks = mutableListOf<ForeignTrack>(); var offset = 0; var pages = 0
        while (pages < FinitePlaylistCompletion.MAX_REQUESTS) {
            pages++
            val root = jsonObject(json.parseToJsonElement(fetchText("https://api.tidal.com/v1/playlists/$id/items?countryCode=$country&limit=100&offset=$offset", headers)))
                ?: throw IncompleteFinitePlaylistException()
            val items = root.array("items") ?: throw IncompleteFinitePlaylistException()
            val total = root.long("totalNumberOfItems")
            if (items.isEmpty()) {
                if (offset < total) throw IncompleteFinitePlaylistException()
                break
            }
            items.forEach { el ->
                val wrapper = el as? JsonObject ?: return@forEach; val item = wrapper.obj("item") ?: wrapper
                if (wrapper.string("type")?.let { it.isNotBlank() && it != "track" } == true) return@forEach
                tracks += ForeignTrack(item.string("title") ?: return@forEach, item.obj("artist")?.string("name"))
            }
            offset += items.size
            if (offset >= total) break
            if (pages >= FinitePlaylistCompletion.MAX_REQUESTS) throw IncompleteFinitePlaylistException()
        }
        return ResolvedImport(ImportSource.TIDAL, id, meta?.string("title") ?: defaultTitle, tracks)
    }

    private suspend fun fetchQobuzPlaylist(url: String, credentials: Credentials, defaultTitle: String): ResolvedImport {
        val id = Regex("playlists?/(?:[^/?#]+/)*?(\\d{3,})").find(url)?.groupValues?.get(1) ?: error("Couldn't extract Qobuz playlist id")
        val appId = credentials.qobuzAppId?.takeIf(String::isNotBlank); val token = credentials.qobuzAuthToken?.takeIf(String::isNotBlank)
        if (appId == null || token == null) error("Qobuz import needs a Qobuz token")
        val headers = mapOf("X-App-Id" to appId, "X-User-Auth-Token" to token)
        val tracks = mutableListOf<ForeignTrack>(); var title = defaultTitle; var offset = 0; var pages = 0
        while (pages < FinitePlaylistCompletion.MAX_REQUESTS) {
            pages++
            val root = jsonObject(json.parseToJsonElement(fetchText("https://www.qobuz.com/api.json/0.2/playlist/get?playlist_id=$id&extra=tracks&limit=500&offset=$offset&app_id=$appId", headers)))
                ?: throw IncompleteFinitePlaylistException()
            if (offset == 0) title = root.string("name") ?: title
            val trackObj = root.obj("tracks") ?: throw IncompleteFinitePlaylistException()
            val items = trackObj.array("items") ?: throw IncompleteFinitePlaylistException()
            val total = trackObj.long("total")
            if (items.isEmpty()) {
                if (offset < total) throw IncompleteFinitePlaylistException()
                break
            }
            items.forEach { el ->
                val item = el as? JsonObject ?: return@forEach
                tracks += ForeignTrack(item.string("title") ?: return@forEach, item.obj("performer")?.string("name"))
            }
            offset += items.size
            if (offset >= total) break
            if (pages >= FinitePlaylistCompletion.MAX_REQUESTS) throw IncompleteFinitePlaylistException()
        }
        return ResolvedImport(ImportSource.QOBUZ, id, title, tracks)
    }

    private suspend fun fetchDeezerPlaylist(url: String, defaultTitle: String): ResolvedImport {
        val id = Regex("playlist/(\\d+)").find(url)?.groupValues?.get(1) ?: error("Couldn't extract Deezer playlist id")
        val root = jsonObject(json.parseToJsonElement(fetchText("https://api.deezer.com/playlist/$id"))) ?: error("Deezer playlist is unavailable")
        val tracksObj = root.obj("tracks") ?: error("Deezer playlist tracks unavailable")
        val tracks = mutableListOf<ForeignTrack>()
        fun appendPage(data: JsonArray?) {
            data.orEmpty().forEach {
                val obj = it as? JsonObject ?: return@forEach
                val trackTitle = obj.string("title") ?: return@forEach
                tracks += ForeignTrack(trackTitle, obj.obj("artist")?.string("name"))
            }
        }
        appendPage(tracksObj.array("data"))
        var nextUrl = tracksObj.string("next")?.takeIf { it != "null" }
        var pages = 1
        while (nextUrl != null && pages < FinitePlaylistCompletion.MAX_REQUESTS) {
            pages++
            val pageObj = jsonObject(json.parseToJsonElement(fetchText(nextUrl))) ?: throw IncompleteFinitePlaylistException()
            val pageData = pageObj.array("data") ?: throw IncompleteFinitePlaylistException()
            if (pageData.isEmpty()) throw IncompleteFinitePlaylistException()
            appendPage(pageData)
            nextUrl = pageObj.string("next")?.takeIf { it != "null" }
        }
        if (nextUrl != null) throw IncompleteFinitePlaylistException()
        return ResolvedImport(ImportSource.DEEZER, id, root.string("title") ?: defaultTitle, tracks)
    }

    private suspend fun resolveToYouTubeMusic(tracks: List<ForeignTrack>, onProgress: (Int, Int) -> Unit): List<ContentItem> = coroutineScope {
        val results = ArrayList<ContentItem>(tracks.size); var completed = 0
        tracks.chunked(6).forEach { batch ->
            batch.map { track ->
                async {
                    val query = listOfNotNull(track.artist?.takeIf(String::isNotBlank), track.title).joinToString(" ").ifBlank { return@async null }
                    runCatching { search.firstSong(query) }.getOrNull()
                }
            }.awaitAll().filterNotNullTo(results)
            completed += batch.size
            onProgress(completed, tracks.size)
        }
        results
    }

    private suspend fun fetchText(url: String, headers: Map<String, String> = emptyMap()): String {
        val response = graph.http.get(url) {
            header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15")
            header("Accept", "text/html,application/json,application/xhtml+xml")
            headers.forEach { (name, value) -> header(name, value) }
        }
        if (response.status.value !in 200..299) error("HTTP ${response.status.value} fetching playlist")
        return response.bodyAsText()
    }

    private fun deterministicPlaylistId(key: String): String {
        var h1 = -0x340d631b7bdddcdbL
        var h2 = 0x100000001b3L
        for (ch in key) {
            h1 = (h1 xor ch.code.toLong()) * 0x100000001b3L
            h2 = (h2 xor (ch.code.toLong() * 31L)) * -0x340d631b7bdddcdbL
        }
        return "LPimp" + h1.toULong().toString(16).padStart(8, '0').take(8) + h2.toULong().toString(16).padStart(8, '0').take(8)
    }

    private fun extractQuery(url: String, key: String): String? =
        Regex("[?&]$key=([^&#]+)").find(url)?.groupValues?.get(1)

    private fun extractScriptJson(html: String, scriptId: String): String? =
        Regex("<script[^>]*id=\"$scriptId\"[^>]*>([\\s\\S]*?)</script>").find(html)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotEmpty)

    private fun jsonObject(element: JsonElement?) = element as? JsonObject
    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.array(key: String) = this[key] as? JsonArray
    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.longOrNull ?: 0L
    private fun unescapeJson(s: String): String =
        s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", " ").replace("\\/", "/").replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"")

    private companion object {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

@Composable
internal fun IosCrossServiceImportDialog(
    graph: IosSessionGraph,
    host: IosHost,
    onOpenRoute: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val importer = remember(graph) { IosCrossServiceImport(graph) }
    val hintText = stringResource(Res.string.ios_cross_service_import_hint)
    val fetchingText = stringResource(Res.string.ios_cross_service_import_fetching)
    val failedFallback = stringResource(Res.string.m6_integration_import_failed)
    val defaultPlaylistName = stringResource(Res.string.ios_cross_service_import_default_playlist)
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(hintText) }
    var resolvingPair by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var done by remember { mutableStateOf<IosCrossServiceImport.ImportResult?>(null) }
    val scope = rememberCoroutineScope()

    val displayStatus = when {
        done != null -> stringResource(Res.string.ios_cross_service_import_done, done!!.imported, done!!.total)
        resolvingPair != null -> stringResource(Res.string.ios_cross_service_import_resolving, resolvingPair!!.first, resolvingPair!!.second)
        else -> status
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(Res.string.m6_integration_cross_service_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(Res.string.ios_cross_service_import_url_label)) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(displayStatus, style = MaterialTheme.typography.bodyMedium)
                if (busy) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator()
                        LinearProgressIndicator(modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    done = null
                    resolvingPair = null
                    status = fetchingText
                    scope.launch {
                        try {
                            importer.import(url.trim(), defaultPlaylistName) { progress ->
                                when (progress) {
                                    is IosCrossServiceImport.ImportProgress.Fetching -> {
                                        resolvingPair = null
                                        status = fetchingText
                                    }
                                    is IosCrossServiceImport.ImportProgress.Resolving -> {
                                        resolvingPair = progress.processed to progress.total
                                    }
                                    is IosCrossServiceImport.ImportProgress.Done -> {
                                        resolvingPair = null
                                        done = progress.result
                                    }
                                    IosCrossServiceImport.ImportProgress.Failed -> {
                                        resolvingPair = null
                                        status = failedFallback
                                    }
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            graph.report("cross-service import failed: ${failure::class.simpleName}")
                            resolvingPair = null
                            status = failedFallback
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(stringResource(Res.string.ios_cross_service_import_start)) }
        },
        dismissButton = {
            Row {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        done = null
                        resolvingPair = null
                        status = fetchingText
                        scope.launch {
                            try {
                                val res = importer.importCsv(host, defaultPlaylistName) { progress ->
                                    when (progress) {
                                        is IosCrossServiceImport.ImportProgress.Fetching -> {
                                            resolvingPair = null
                                            status = fetchingText
                                        }
                                        is IosCrossServiceImport.ImportProgress.Resolving -> {
                                            resolvingPair = progress.processed to progress.total
                                        }
                                        is IosCrossServiceImport.ImportProgress.Done -> {
                                            resolvingPair = null
                                            done = progress.result
                                        }
                                        IosCrossServiceImport.ImportProgress.Failed -> {
                                            resolvingPair = null
                                            status = failedFallback
                                        }
                                    }
                                }
                                if (res == null) {
                                    resolvingPair = null
                                    status = hintText
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                graph.report("csv import failed: ${failure::class.simpleName}")
                                resolvingPair = null
                                status = failedFallback
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) {
                    Text(stringResource(Res.string.ios_cross_service_import_csv))
                }
                done?.playlistId?.let { targetPlaylistId ->
                    TextButton(
                        onClick = {
                            onDismiss()
                            onOpenRoute(
                                ArchiveRoutes.content(
                                    ContentRef(ContentProvider.LOCAL.id, ContentKind.PLAYLIST, targetPlaylistId),
                                ),
                            )
                        },
                    ) {
                        Text(stringResource(Res.string.ios_cross_service_import_open))
                    }
                }
                TextButton(onClick = onDismiss, enabled = !busy) {
                    Text(stringResource(Res.string.ios_cross_service_import_close))
                }
            }
        },
    )
}
