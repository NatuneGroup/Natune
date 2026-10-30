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
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.domain.songmetadata.canonicalSong
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
    val target = MutableStateFlow<DesktopCanvasTarget?>(null)
    var notice: String? by mutableStateOf(null)
        private set
    private var closed = false
    private val previousPolicy = CanvasRequestPolicy.check
    private val previousToken = SpotifyCanvasProvider.tokenProvider
    private val previousTrack = SpotifyCanvasProvider.trackUriResolver
    private val previousEndpoints = SpotifyCanvasProvider.extraResolverEndpointsProvider
    private val policy: (CanvasSource) -> Unit = { source ->
        check(!closed && visible.value && remoteAllowed(source)) { "Canvas request denied by current preferences/network" }
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
        parseDesktopCanvasEndpoints(graph.settings.snapshots.value?.get(CanvasResolverEndpointsKey))
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
                CanvasInput(row, preferences, overrides.canvases, online, showing)
            }.distinctUntilChanged { old, new ->
                old.row == new.row && old.canvases == new.canvases && old.online == new.online && old.showing == new.showing &&
                    canvasPreferences(old.preferences) == canvasPreferences(new.preferences)
            }.collectLatest { input ->
                target.value = null
                notice = null
                val row = input.row ?: return@collectLatest
                val preferences = input.preferences ?: return@collectLatest
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
                        return@collectLatest
                    }
                    if (!input.online || preferences[CanvasWifiOnlyKey, false] || preferences.lowDataMode()) {
                        notice = "content_canvas_network_unknown"
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
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    notice = "content_canvas_failed"
                    graph.report("canvas not resolved: ${failure.javaClass.simpleName}")
                }
            }
        }
    }

    private suspend fun resolve(id: String, title: String, artist: String): Pair<String, CanvasSource>? {
        val storefront = Locale.getDefault().country.lowercase(Locale.ROOT).takeIf { it.length == 2 } ?: "us"
        for (provider in CanvasSource.entries.filter { it != CanvasSource.ALL }) {
            if (!remoteAllowed(provider)) continue
            policy(provider)
            val artwork: CanvasArtwork? = when (provider) {
                CanvasSource.BETTER_LYRICS, CanvasSource.APPLE_MUSIC ->
                    ArchiveTuneCanvas.getBySongArtist(title, artist, storefront, source = provider)
                CanvasSource.TIDAL -> TidalCanvasProvider.getBySongArtist(title, artist, storefront)
                CanvasSource.SPOTIFY -> SpotifyCanvasProvider.getByVideoId(id, title, artist)
                CanvasSource.ALL -> null
            }
            policy(provider)
            val url = artwork?.preferredAnimationUrl ?: artwork?.preferredVerticalAnimationUrl
            if (url != null && desktopCanvasHttpsUrl(url)) return url to provider
        }
        return null
    }

    internal fun remoteAllowed(source: CanvasSource): Boolean {
        val provider = CanvasHealthProvider.entries.firstOrNull { it.name == source.name } ?: return false
        val preferences = graph.settings.snapshots.value ?: return false
        // JVM routes do not measure Wi-Fi or metering. Unknown stays conservative instead of claiming unmetered Wi-Fi.
        return preferences.canvasPlaybackAllowed(provider, CanvasConnectivity(network.online.value, wifi = false, metered = true))
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
        val row: ContentOccurrence?, val preferences: PreferenceSnapshot?, val canvases: Map<String, String>,
        val online: Boolean, val showing: Boolean,
    )
}

internal fun createDesktopCanvas(graph: DesktopSessionGraph, accounts: DesktopSpotifyAccounts) = DesktopCanvasOwner(graph, accounts)

private fun canvasPreferences(preferences: PreferenceSnapshot?): List<Any?> = preferences?.let {
    listOf(it[ArchiveTuneCanvasKey], it[SpotifyCanvasKey], it[CanvasSourceKey], it[CanvasWifiOnlyKey], it.lowDataMode(),
        it[DisableAnimationsKey], it[CanvasResolverEndpointsKey], it[SpotifySpDcKey])
}.orEmpty()

/** Only a repository-owned, size-bounded file is ever handed to the native file protocol. */
internal fun validatedDesktopCanvasFile(instance: Path, uri: String): Path {
    val root = instance.resolve(CustomArtworkDirectory.CANVASES.directoryName).toRealPath()
    val path = Paths.get(URI(uri)).toRealPath()
    require(path.startsWith(root) && path.parent == root && Files.isRegularFile(path))
    require(Files.size(path) in 1..CustomArtworkLimits.MAX_CANVAS_BYTES)
    return path
}

internal fun desktopCanvasHttpsUrl(value: String): Boolean = try {
    val uri = URI(value)
    uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
} catch (_: Exception) { false }

internal fun parseDesktopCanvasEndpoints(raw: String?): List<String> = raw.orEmpty().split('\n', ',')
    .map { it.trim().trimEnd('/') }.filter { value ->
        try {
            val uri = URI(value)
            uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
        } catch (_: Exception) { false }
    }.distinct().take(8)
