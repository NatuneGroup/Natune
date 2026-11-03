package moe.rukamori.archivetune.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.canvas.ArchiveTuneCanvas
import moe.rukamori.archivetune.canvas.CanvasRequestPolicy
import moe.rukamori.archivetune.canvas.CanvasSource
import moe.rukamori.archivetune.canvas.SpotifyCanvasProvider
import moe.rukamori.archivetune.canvas.TidalCanvasProvider
import moe.rukamori.archivetune.canvas.models.CanvasArtwork
import moe.rukamori.archivetune.desktop.integration.DesktopNetworkAvailability
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkDirectory
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkKeys
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkLimits
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkResult
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.domain.songmetadata.canonicalSong
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerQueueCanvasRefetchResult
import moe.rukamori.archivetune.spotify.Spotify

internal data class DesktopCanvasTarget(
    val occurrenceId: String,
    val uri: String,
    val image: Boolean,
    val provider: CanvasSource? = null,
)

/** One window-owned resolver. It never reads the Spotify streaming account or opens another audio player. */
internal class DesktopCanvasOwner(
    private val graph: DesktopSessionGraph,
    private val accounts: DesktopSpotifyAccounts,
) {
    private val lifetime = SupervisorJob(graph.scope.coroutineContext[Job])
    internal val scope = CoroutineScope(graph.scope.coroutineContext + lifetime)
    private val network = DesktopNetworkAvailability(scope)
    private val visible = MutableStateFlow(false)
    /** True while an explicit save or refetch runs, so the menu's own provider reads pass the policy check. */
    private val canvasBusy = MutableStateFlow(false)
    /** True while a refetch runs; the player menu mirrors it into its busy row. */
    internal val refetching = MutableStateFlow(false)
    private val prefetched = DesktopCanvasPrefetchCache(RESOLVED_CAP)
    val target = MutableStateFlow<DesktopCanvasTarget?>(null)
    var notice: String? by mutableStateOf(null)
        private set
    private var closed = false
    private val previousPolicy = CanvasRequestPolicy.check
    private val previousToken = SpotifyCanvasProvider.tokenProvider
    private val previousTrack = SpotifyCanvasProvider.trackUriResolver
    private val previousEndpoints = SpotifyCanvasProvider.extraResolverEndpointsProvider
    private val policy: (CanvasSource) -> Unit = { source ->
        check(!closed && (visible.value || canvasBusy.value) && remoteAllowed(source)) {
            "Canvas request denied by current preferences/network"
        }
    }
    private val token: suspend () -> String? = {
        policy(CanvasSource.SPOTIFY)
        val identity = graph.settings.snapshots.value?.get(SpotifySpDcKey)
        if (accounts.catalogConnected() && accounts.restoreCatalogSession() &&
            identity == graph.settings.snapshots.value?.get(SpotifySpDcKey)) Spotify.accessToken else null
    }
    private val track: suspend (String, String?, String?) -> String? = { _, title, artist ->
        policy(CanvasSource.SPOTIFY)
        if (title.isNullOrBlank() || artist.isNullOrBlank() || !accounts.restoreCatalogSession()) null else {
            val found = accounts.catalogCall { Spotify.search("$artist $title", types = listOf("track"), limit = 5).getOrThrow() }
                .tracks?.items.orEmpty().firstOrNull { song ->
                    comparableMixName(song.name, title) && song.artists.any { comparableMixName(it.name, artist) }
                }
            found?.id?.takeIf(String::isNotBlank)?.let { "spotify:track:$it" }
        }
    }
    private val endpoints: suspend () -> List<String> = {
        parseCanvasResolverEndpoints(graph.settings.snapshots.value?.get(CanvasResolverEndpointsKey))
    }

    init {
        CanvasRequestPolicy.check = policy
        SpotifyCanvasProvider.tokenProvider = token
        SpotifyCanvasProvider.trackUriResolver = track
        SpotifyCanvasProvider.extraResolverEndpointsProvider = endpoints
        scope.launch {
            graph.customArtwork.ensureLoaded()
            combine(
                graph.queue.state, graph.settings.snapshots, graph.customArtwork.overrides, network.online, visible,
            ) { queue, preferences, overrides, online, showing ->
                val row = queue.entries.firstOrNull { it.occurrenceId == queue.currentOccurrenceId }
                val order = queue.playOrder
                val index = order.indexOf(queue.currentOccurrenceId)
                val upcoming =
                    if (index < 0) {
                        emptyList()
                    } else {
                        order.subList(index + 1, minOf(order.size, index + 1 + PREFETCH_AHEAD))
                            .mapNotNull { id -> queue.entries.firstOrNull { it.occurrenceId == id } }
                    }
                CanvasInput(row, upcoming, preferences, overrides.canvases, online, showing)
            }.distinctUntilChanged { old, new ->
                old.row == new.row && old.upcoming == new.upcoming && old.canvases == new.canvases &&
                    old.online == new.online && old.showing == new.showing &&
                    canvasPreferences(old.preferences) == canvasPreferences(new.preferences)
            }.collectLatest { input ->
                target.value = null
                notice = null
                val row = input.row ?: return@collectLatest
                val preferences = input.preferences ?: return@collectLatest
                val probe = canvasPreferences(preferences)
                prefetched.retainProbe(probe)
                if (!input.showing || !preferences.canvasPlaybackEnabled() || row.item.ref.kind != ContentKind.SONG) {
                    return@collectLatest
                }
                try {
                    val local = graph.customArtwork.canvasUri(row.item.ref.id)
                    if (local != null) {
                        val path = withContext(Dispatchers.IO) { validatedDesktopCanvasFile(graph.instanceDirectory, local) }
                        val extension = path.fileName.toString().substringAfterLast('.').lowercase()
                        target.value = DesktopCanvasTarget(
                            row.occurrenceId, path.toUri().toString(),
                            CustomArtworkKeys.isImageCanvasExtension(extension) && extension != "gif",
                        )
                        prime(input, preferences, probe)
                        return@collectLatest
                    }
                    if (!input.online || preferences[CanvasWifiOnlyKey, false] || preferences.lowDataMode()) {
                        notice = "content_canvas_network_unknown"
                        return@collectLatest
                    }
                    // A canvas already resolved for this turn plays at once; the miss path resolves and
                    // then primes the rows that follow, so the next transition shows art instead of
                    // re-resolving after the skip.
                    prefetched.take(row.occurrenceId, probe, ::remoteAllowed)?.let { primed ->
                        target.value = primed
                        prime(input, preferences, probe)
                        return@collectLatest
                    }
                    val canonical = row.item.canonicalSong()
                    val artist = canonical.artists.firstOrNull()?.name ?: canonical.subtitle.takeIf(String::isNotBlank)
                        ?: return@collectLatest
                    val title = canonical.title
                    val result = withTimeoutOrNull(45_000L) { resolve(row.item.ref.id, title, artist) }
                    target.value = result?.let { (uri, provider) ->
                        DesktopCanvasTarget(row.occurrenceId, uri, image = false, provider = provider)
                    }
                    if (result == null) notice = "content_canvas_not_found"
                    prime(input, preferences, probe)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    notice = "content_canvas_failed"
                    graph.report("canvas not resolved: ${failure.javaClass.simpleName}")
                }
            }
        }
    }

    /**
     * Resolves the canvases of the next [PREFETCH_AHEAD] queue rows into [prefetched], so the row that
     * becomes current finds its artwork without another provider round-trip. Rows with a local canvas
     * are skipped (the local path wins anyway), and a cancelled prefetch leaves no entry.
     *
     * The lookups are children of the calling resolve generation: collectLatest cancels and joins them
     * as soon as the queue, preferences, network or visibility move on, so a row that is no longer
     * upcoming stops resolving and one generation never runs two lookups for the same occurrence.
     */
    private suspend fun prime(input: CanvasInput, preferences: PreferenceSnapshot, probe: List<Any?>) {
        if (!input.online || preferences[CanvasWifiOnlyKey, false] || preferences.lowDataMode()) return
        val started = HashSet<String>()
        coroutineScope {
            for (candidate in input.upcoming) {
                val id = candidate.occurrenceId
                if (candidate.item.ref.kind != ContentKind.SONG || id == input.row?.occurrenceId) continue
                if (!started.add(id) || prefetched.contains(id)) continue
                launch { primeOne(candidate, probe) }
            }
        }
    }

    private suspend fun primeOne(candidate: ContentOccurrence, probe: List<Any?>) {
        try {
            if (graph.customArtwork.canvasUri(candidate.item.ref.id) != null) return
            val canonical = candidate.item.canonicalSong()
            val artist = canonical.artists.firstOrNull()?.name ?: canonical.subtitle.takeIf(String::isNotBlank) ?: return
            val found = withTimeoutOrNull(45_000L) { resolve(candidate.item.ref.id, canonical.title, artist) } ?: return
            prefetched.put(
                DesktopCanvasTarget(candidate.occurrenceId, found.first, image = false, provider = found.second),
                probe,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A failed lookahead is silent; the row resolves normally when its turn comes.
        }
    }

    private suspend fun resolve(
        id: String, title: String, artist: String, requireVertical: Boolean = false,
    ): Pair<String, CanvasSource>? {
        val storefront = Locale.getDefault().country.lowercase(Locale.ROOT).takeIf { it.length == 2 } ?: "us"
        for (provider in CanvasSource.entries.filter { it != CanvasSource.ALL }) {
            if (!remoteAllowed(provider)) continue
            policy(provider)
            val artwork = providerArtwork(provider, id, title, artist, storefront)
            policy(provider)
            val url = if (requireVertical) {
                artwork?.preferredVerticalAnimationUrl ?: artwork?.preferredAnimationUrl
            } else {
                artwork?.preferredAnimationUrl ?: artwork?.preferredVerticalAnimationUrl
            }
            if (url != null && desktopCanvasHttpsUrl(url)) return url to provider
        }
        return null
    }

    /** One provider's artwork record for the song, as the playback resolver calls it. */
    private suspend fun providerArtwork(
        provider: CanvasSource, id: String, title: String, artist: String, storefront: String,
    ): CanvasArtwork? = when (provider) {
        CanvasSource.BETTER_LYRICS, CanvasSource.APPLE_MUSIC ->
            ArchiveTuneCanvas.getBySongArtist(title, artist, storefront, source = provider)
        CanvasSource.TIDAL -> TidalCanvasProvider.getBySongArtist(title, artist, storefront)
        CanvasSource.SPOTIFY -> SpotifyCanvasProvider.getByVideoId(id, title, artist)
        CanvasSource.ALL -> null
    }

    /** The save picker's resolved urls per song: a superseded fetch only ever writes its own song's entry. */
    private val saveUrls = LinkedHashMap<String, Map<String, String>>()
    private var fetchJob: Job? = null

    /**
     * Every provider animation a save may pick, as the baseline's Save Canvas dialog listed its sources. Provider
     * reads run under the same allowance playback uses; HLS urls are not kept, as a save is a file copy and the
     * baseline refused playlists it could not store.
     */
    internal suspend fun fetchSaveSources(item: ContentItem): List<Pair<CanvasSource, String>> {
        check(!closed)
        // A new fetch supersedes the one still running for another song; the late writer can no longer
        // overwrite the url list a different pick reads.
        val current = currentCoroutineContext()[Job]
        if (fetchJob !== current) fetchJob?.cancel()
        fetchJob = current
        canvasBusy.value = true
        try {
            val canonical = item.canonicalSong()
            val artist = canonical.artists.firstOrNull()?.name ?: canonical.subtitle.takeIf(String::isNotBlank)
                ?: return emptyList()
            val title = canonical.title
            val storefront = Locale.getDefault().country.lowercase(Locale.ROOT).takeIf { it.length == 2 } ?: "us"
            val found = LinkedHashMap<CanvasSource, String>()
            for (provider in CanvasSource.entries.filter { it != CanvasSource.ALL }) {
                if (!remoteAllowed(provider)) continue
                val artwork = try {
                    providerArtwork(provider, item.ref.id, title, artist, storefront)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    continue
                }
                val url = artwork?.preferredAnimationUrl ?: artwork?.preferredVerticalAnimationUrl
                if (url != null && desktopCanvasHttpsUrl(url) && !url.lowercase(Locale.ROOT).contains(".m3u8")) {
                    found[provider] = url
                }
            }
            // Results stay bound to the song they were fetched for: a superseded fetch that still lands can
            // only write its own entry, never the one the open picker saves from.
            saveUrls[item.ref.id] = found.mapKeys { it.key.name }
            if (saveUrls.size > 16) saveUrls.remove(saveUrls.keys.first())
            return found.toList()
        } finally {
            canvasBusy.value = false
        }
    }

    /** Stores the picked source's file as the song's custom canvas; false leaves the canvas as it was. */
    internal suspend fun saveCanvas(songId: String, sourceKey: String): Boolean {
        // The picker's allowance still holds: a source switched off between the fetch and the pick saves nothing.
        val provider = CanvasSource.entries.firstOrNull { it.name == sourceKey && remoteAllowed(it) } ?: return false
        val url = saveUrls[songId]?.get(provider.name) ?: return false
        val bytes = try {
            downloadDesktopCanvas(graph, url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return false
        return graph.customArtwork.setCanvas(songId, DesktopBytesArtworkSource(bytes, canvasUrlExtension(url))) ==
            CustomArtworkResult.SAVED
    }

    /**
     * The menu's Refetch canvas row: the playback resolve runs again for the queued song and a hit replaces the shown
     * target. A concurrent refetch answers ALREADY_RUNNING rather than racing the resolve.
     */
    internal suspend fun refetch(item: ContentItem, requireVertical: Boolean): PlayerQueueCanvasRefetchResult {
        if (closed) return PlayerQueueCanvasRefetchResult.FAILURE
        if (!refetching.compareAndSet(false, true)) return PlayerQueueCanvasRefetchResult.ALREADY_RUNNING
        canvasBusy.value = true
        try {
            // The canvas only ever paints under the playing row, so the refetch binds the current occurrence at
            // resolve start: a queue advance or a duplicate elsewhere never takes its place.
            val queue = graph.queue.state.value
            val occurrenceId = queue.entries
                .firstOrNull { it.occurrenceId == queue.currentOccurrenceId && it.item.ref.id == item.ref.id }
                ?.occurrenceId ?: return PlayerQueueCanvasRefetchResult.FAILURE
            val canonical = item.canonicalSong()
            val artist = canonical.artists.firstOrNull()?.name ?: canonical.subtitle.takeIf(String::isNotBlank)
                ?: return PlayerQueueCanvasRefetchResult.FAILURE
            val result = try {
                withTimeoutOrNull(45_000L) { resolve(item.ref.id, canonical.title, artist, requireVertical) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            return if (result != null) {
                val (uri, provider) = result
                // A stale resolve must not paint over the track the queue advanced to.
                if (graph.queue.state.value.currentOccurrenceId == occurrenceId) {
                    target.value = DesktopCanvasTarget(occurrenceId, uri, image = false, provider = provider)
                    PlayerQueueCanvasRefetchResult.SUCCESS
                } else {
                    PlayerQueueCanvasRefetchResult.FAILURE
                }
            } else {
                notice = "content_canvas_failed"
                PlayerQueueCanvasRefetchResult.FAILURE
            }
        } finally {
            canvasBusy.value = false
            refetching.value = false
        }
    }

    internal fun remoteAllowed(source: CanvasSource): Boolean {
        val provider = canvasHealthProviderOf(source.name) ?: return false
        val preferences = graph.settings.snapshots.value ?: return false
        // JVM routes do not measure Wi-Fi or metering. Unknown stays conservative instead of claiming unmetered Wi-Fi.
        return preferences.canvasRemoteAllowed(provider, CanvasConnectivity(network.online.value, wifi = false, metered = true))
    }

    fun setVisible(showing: Boolean) { visible.value = showing }
    internal fun failed(expected: DesktopCanvasTarget) {
        if (target.value != expected) return
        target.value = null
        notice = "content_canvas_failed"
    }

    fun close() {
        closed = true
        target.value = null
        lifetime.cancel()
        if (CanvasRequestPolicy.check === policy) CanvasRequestPolicy.check = previousPolicy
        if (SpotifyCanvasProvider.tokenProvider === token) SpotifyCanvasProvider.tokenProvider = previousToken
        if (SpotifyCanvasProvider.trackUriResolver === track) SpotifyCanvasProvider.trackUriResolver = previousTrack
        if (SpotifyCanvasProvider.extraResolverEndpointsProvider === endpoints) {
            SpotifyCanvasProvider.extraResolverEndpointsProvider = previousEndpoints
        }
    }

    private data class CanvasInput(
        val row: ContentOccurrence?, val upcoming: List<ContentOccurrence>,
        val preferences: PreferenceSnapshot?, val canvases: Map<String, String>,
        val online: Boolean, val showing: Boolean,
    )

    private companion object {
        const val PREFETCH_AHEAD = 2
        const val RESOLVED_CAP = 8
    }
}

internal fun createDesktopCanvas(graph: DesktopSessionGraph, accounts: DesktopSpotifyAccounts) = DesktopCanvasOwner(graph, accounts)

private fun canvasPreferences(preferences: PreferenceSnapshot?): List<Any?> = canvasPreferenceProbe(preferences)

/** Only a repository-owned, size-bounded file is ever handed to the native file protocol. */
internal fun validatedDesktopCanvasFile(instance: Path, uri: String): Path {
    val root = instance.resolve(CustomArtworkDirectory.CANVASES.directoryName).toRealPath()
    val path = Paths.get(URI(uri)).toRealPath()
    require(path.startsWith(root) && path.parent == root && Files.isRegularFile(path))
    require(Files.size(path) in 1..CustomArtworkLimits.MAX_CANVAS_BYTES)
    return path
}

internal fun desktopCanvasHttpsUrl(value: String): Boolean = canvasLoopUrlAllowed(value)

internal fun parseDesktopCanvasEndpoints(raw: String?): List<String> = parseCanvasResolverEndpoints(raw)
