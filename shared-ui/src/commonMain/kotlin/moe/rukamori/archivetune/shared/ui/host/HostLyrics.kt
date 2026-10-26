package moe.rukamori.archivetune.shared.ui.host

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.data.lyrics.BetterLyricsProvider
import moe.rukamori.archivetune.shared.data.lyrics.BiniLyricsProvider
import moe.rukamori.archivetune.shared.data.lyrics.GeniusLyricsProvider
import moe.rukamori.archivetune.shared.data.lyrics.KuGouLyricsProvider
import moe.rukamori.archivetune.shared.data.lyrics.LrcLibLyricsProvider
import moe.rukamori.archivetune.shared.data.lyrics.LrcRedLyricsProvider
import moe.rukamori.archivetune.shared.data.lyrics.MegalobizLyricsProvider
import moe.rukamori.archivetune.shared.data.lyrics.LyprioEndpoint
import moe.rukamori.archivetune.shared.data.lyrics.LyprioLyricsProvider
import moe.rukamori.archivetune.shared.data.lyrics.LyricsProvider
import moe.rukamori.archivetune.shared.data.lyrics.LyricsRequest
import moe.rukamori.archivetune.shared.data.lyrics.PaxsenixAppleMusicLyricsProvider
import moe.rukamori.archivetune.shared.data.lyrics.PaxsenixMusixmatchLyricsProvider
import moe.rukamori.archivetune.shared.data.lyrics.PaxsenixNeteaseLyricsProvider
import moe.rukamori.archivetune.shared.data.lyrics.PaxsenixSettings
import moe.rukamori.archivetune.shared.data.lyrics.PaxsenixSpotifyLyricsProvider
import moe.rukamori.archivetune.shared.data.lyrics.PaxsenixYouTubeLyricsProvider
import moe.rukamori.archivetune.shared.data.lyrics.SimpMusicLyricsProvider
import moe.rukamori.archivetune.shared.data.lyrics.UnisonLyricsProvider
import moe.rukamori.archivetune.shared.data.lyrics.YouLyPlusLyricsProvider
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsDetection
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsLine
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsParser
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.QueueState
import moe.rukamori.archivetune.shared.domain.settings.LyprioEndpointKey
import moe.rukamori.archivetune.shared.domain.settings.LyprioSigningKeyKey
import moe.rukamori.archivetune.shared.domain.settings.PaxsenixApiKeyKey
import moe.rukamori.archivetune.shared.domain.settings.PaxsenixEndpointKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.ui.session.SettingsLyricsRuntime
import moe.rukamori.archivetune.shared.ui.settings.VaultSettingsStore

/**
 * The lyrics of one queue occurrence. [lines] is empty while loading and when no provider had any.
 * [raw] is the exact document text the provider returned; it is the AI session's identity and input
 * and the host's share of the session key, so a refetch of different text invalidates every surface.
 */
data class HostLyricsDocument(
    val occurrenceId: String,
    val lines: List<LyricsLine>,
    val loading: Boolean,
    val raw: String = "",
)

/**
 * The host lyrics owner: the shared provider runtime over the settings owner, fed by the one queue. It follows the
 * occurrence the player reports, preloads the next ones in play order, and parses the winning document once. The
 * app-only providers (account Apple Music, TIDAL, Deezer, YouTube captions and the like) are Android-only.
 */
class HostLyrics(
    private val scope: CoroutineScope,
    http: HttpClient,
    settings: VaultSettingsStore,
    private val player: MusicPlayerController,
    private val queue: StateFlow<QueueState>,
    private val networkAvailable: () -> Boolean,
    private val report: (String) -> Unit,
) {
    private val runtime = SettingsLyricsRuntime(
        scope = scope,
        store = settings,
        availableProviderNames = MutableStateFlow(SHARED_PROVIDER_NAMES),
        providerRevision = MutableStateFlow(0L),
        providerFactory = { preferences -> sharedLyricsProviders(http, preferences) },
        networkAvailable = networkAvailable,
        reportFailure = { name, failure -> report("lyrics provider $name failed: ${failure::class.simpleName}") },
    )

    private val state = MutableStateFlow<HostLyricsDocument?>(null)
    val current: StateFlow<HostLyricsDocument?> = state.asStateFlow()
    private var driver: Job? = null

    fun start() {
        driver = scope.launch {
            combine(player.snapshot, queue) { snapshot, queueState ->
                val id = snapshot?.occurrenceId ?: queueState.currentOccurrenceId
                id to queueState
            }.distinctUntilChanged { old, new -> old.first == new.first && old.second.playOrder == new.second.playOrder }
                .collectLatest { (id, queueState) -> follow(id, queueState) }
        }
    }

    fun close() {
        driver?.cancel()
    }

    private suspend fun follow(occurrenceId: String?, queueState: QueueState) {
        val entries = queueState.entries.associateBy { it.occurrenceId }
        val item = occurrenceId?.let { entries[it]?.item }
        val request = item?.lyricsRequest()
        val upcoming = occurrenceId?.let { id ->
            queueState.playOrder.dropWhile { it != id }.drop(1).take(PRELOAD_LIMIT)
                .mapNotNull { entries[it]?.item?.lyricsRequest() }
        }.orEmpty()
        runtime.updateWindow(request, upcoming)
        if (occurrenceId == null || request == null) {
            state.value = null
            return
        }
        if (state.value?.occurrenceId != occurrenceId) state.value = HostLyricsDocument(occurrenceId, emptyList(), loading = true)
        val (raw, lines) = try {
            val result = runtime.lyrics(request)
            if (result.providerName.isEmpty()) "" to emptyList() else result.lyrics to parse(result.lyrics)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            report("lyrics lookup failed: ${failure::class.simpleName}")
            "" to emptyList()
        }
        state.value = HostLyricsDocument(occurrenceId, lines, loading = false, raw = raw)
    }

    private fun parse(text: String): List<LyricsLine> =
        if (LyricsDetection.isTtml(text)) LyricsParser.parseTtml(text) else LyricsParser.parse(text)

    private companion object {
        /** The runtime caps preloading at ten upcoming songs, whatever the setting says. */
        const val PRELOAD_LIMIT = 10
    }
}

private fun ContentItem.lyricsRequest(): LyricsRequest? {
    val artist = artists.joinToString(", ") { it.name }.ifBlank { subtitle }
    if (title.isBlank() || artist.isBlank()) return null
    return LyricsRequest(
        mediaId = ref.id,
        title = title,
        artist = artist,
        album = album,
        durationSec = ((durationMs ?: 0L) / 1000L).toInt(),
    )
}

/** The Paxsenix endpoint and key the user set, or the public endpoint. The providers and the settings probes share it. */
fun paxsenixSettingsOf(preferences: PreferenceSnapshot): PaxsenixSettings = PaxsenixSettings(
    baseUrl = preferences[PaxsenixEndpointKey]?.takeIf { it.isNotBlank() } ?: PaxsenixSettings.DEFAULT_BASE_URL,
    bearerValue = preferences[PaxsenixApiKeyKey]?.takeIf { it.isNotBlank() },
)

/**
 * Shared providers in canonical order. Paxsenix uses the user's endpoint and key when they are set. Lyprio exists only
 * once the user has entered a valid server URL and signing key; its toggle is applied by the runtime like the others.
 */
fun sharedLyricsProviders(http: HttpClient, preferences: PreferenceSnapshot): List<LyricsProvider> {
    val paxsenix = paxsenixSettingsOf(preferences)
    val lyprio = LyprioEndpoint.parse(preferences[LyprioEndpointKey].orEmpty(), preferences[LyprioSigningKeyKey].orEmpty())
    return listOfNotNull(
        lyprio?.let { LyprioLyricsProvider(http, it) },
        // Same relative order as the Android baseProviders list; account-bound providers
        // (Apple Music, Tidal, Deezer, YouTube) stay host-owned and are not shared.
        LrcRedLyricsProvider(http),
        BiniLyricsProvider(http),
        BetterLyricsProvider(http),
        BetterLyricsProvider.portato(http),
        YouLyPlusLyricsProvider(http),
        LrcLibLyricsProvider(http),
        KuGouLyricsProvider(http),
        MegalobizLyricsProvider(http),
        SimpMusicLyricsProvider(http),
        UnisonLyricsProvider(http),
        PaxsenixAppleMusicLyricsProvider(http, settings = paxsenix),
        PaxsenixNeteaseLyricsProvider(http, paxsenix),
        PaxsenixSpotifyLyricsProvider(http, paxsenix),
        PaxsenixMusixmatchLyricsProvider(http, paxsenix),
        PaxsenixYouTubeLyricsProvider(http, paxsenix),
        GeniusLyricsProvider(http),
    )
}

private val SHARED_PROVIDER_NAMES = setOf(
    "Lyprio", "lrc.red", "BiniLyrics", "BetterLyrics", "BetterLyrics Portato", "YouLyPlus", "LrcLib",
    "Kugou", "Megalobiz", "SimpMusic", "Unison",
    "Paxsenix: Apple Music", "Paxsenix: NetEase", "Paxsenix: Spotify", "Paxsenix: Musixmatch", "Paxsenix: YouTube",
    "Genius",
)
