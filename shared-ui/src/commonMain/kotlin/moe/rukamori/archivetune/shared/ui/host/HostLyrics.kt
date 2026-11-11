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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.shared.data.lyrics.ttmlDocumentReader
import moe.rukamori.archivetune.shared.domain.lyrics.TtmlDocument
import moe.rukamori.archivetune.shared.data.lyrics.BetterLyricsProvider
import moe.rukamori.archivetune.shared.data.lyrics.BiniLyricsProvider
import moe.rukamori.archivetune.shared.data.lyrics.GeniusLyricsProvider
import moe.rukamori.archivetune.shared.data.lyrics.KuGouLyricsProvider
import moe.rukamori.archivetune.shared.data.lyrics.LrcLibLyricsProvider
import moe.rukamori.archivetune.shared.data.lyrics.LrcRedLyricsProvider
import moe.rukamori.archivetune.shared.data.lyrics.MegalobizLyricsProvider
import moe.rukamori.archivetune.shared.data.lyrics.LyprioLyricsProvider
import moe.rukamori.archivetune.shared.data.lyrics.resolveLyprioEndpoint
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
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsCredits
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsDetection
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsLine
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsParser
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.QueueState
import moe.rukamori.archivetune.shared.domain.settings.PaxsenixApiKeyKey
import moe.rukamori.archivetune.shared.domain.settings.PaxsenixEndpointKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.ui.session.SettingsLyricsRuntime
import moe.rukamori.archivetune.shared.ui.settings.VaultSettingsStore

/**
 * Where a lookup stands, so a surface can tell "still reading" from "nothing was found" from "the read failed". Before
 * this the document was a list and a flag, and an empty list meant all three at once: the pane showed nothing at all
 * for a track no provider knows.
 */
enum class HostLyricsStatus { LOADING, READY, NOT_FOUND, FAILED }

/** Optional host persistence; edited documents survive an ordinary provider refresh. */
data class HostSavedLyrics(val raw: String, val edited: Boolean = false)

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
    /** The document's writer/auto-created credits; null for non-TTML lyrics. */
    val credits: LyricsCredits? = null,
    val status: HostLyricsStatus = if (loading) HostLyricsStatus.LOADING else HostLyricsStatus.READY,
) {
    /** Whether a surface has lines to draw; the one question the player slots asked before this existed. */
    val hasLines: Boolean get() = lines.isNotEmpty()
}

/**
 * The occurrence a lyrics surface is showing: the player's, or the queue's before playback starts or after it failed.
 * The owner follows the same pair in [HostLyrics.follow], so a surface and the lookup always name one track.
 */
fun activeLyricsOccurrence(playbackOccurrenceId: String?, queueOccurrenceId: String?): String? =
    playbackOccurrenceId ?: queueOccurrenceId

/**
 * The published document, and only while it belongs to [activeOccurrenceId].
 *
 * A track change reaches the player before it reaches the lyrics owner, which keeps the previous track's finished
 * document until it has read the new occurrence. Handing that document to a surface draws the old track's lines under
 * the new track's title; resolving it against the occurrence on screen leaves the surface reading instead.
 */
fun HostLyricsDocument?.belongingTo(activeOccurrenceId: String?): HostLyricsDocument? =
    this?.takeIf { activeOccurrenceId != null && occurrenceId == activeOccurrenceId }

/**
 * The host lyrics owner: the shared provider runtime over the settings owner, fed by the one queue. It follows the
 * occurrence the player reports, preloads the next ones in play order, and parses the winning document once. The
 * app-only providers (account Apple Music, TIDAL, Deezer, YouTube captions and the like) are Android-only.
 *
 * [lyprioBuildBaseUrl]/[lyprioBuildHmacKey] are the host's build-time Lyprio server pair — the only
 * endpoint the provider can have, since the server is run by the Metrolist developers and is not
 * user-configurable. When both are present and parse, the bundled endpoint serves the lookup.
 * The pair never enters preferences, so it stays out of backups and diagnostics.
 */
interface HostLyricsStorage {
    suspend fun read(item: ContentItem, force: Boolean): String?
    suspend fun write(item: ContentItem, raw: String, edited: Boolean, guard: () -> Boolean = { true }): Boolean
}

class HostLyrics(
    private val scope: CoroutineScope,
    http: HttpClient,
    private val settings: VaultSettingsStore,
    private val player: MusicPlayerController,
    private val queue: StateFlow<QueueState>,
    private val networkAvailable: () -> Boolean,
    private val report: (String) -> Unit,
    lyprioBuildBaseUrl: String = "",
    lyprioBuildHmacKey: String = "",
    private val storage: HostLyricsStorage? = null,
    additionalProviderNames: List<String> = emptyList(),
    additionalProviders: (PreferenceSnapshot) -> List<LyricsProvider> = { emptyList() },
    private val readSaved: suspend (ContentItem) -> HostSavedLyrics? = { null },
    private val saveFetched: suspend (ContentItem, String) -> Unit = { _, _ -> },
    extraProviders: (PreferenceSnapshot) -> List<LyricsProvider> = { emptyList() },
    extraProviderNames: Set<String> = emptySet(),
) {
    private val runtime = SettingsLyricsRuntime(
        scope = scope,
        store = settings,
        availableProviderNames = MutableStateFlow((SHARED_PROVIDER_NAMES + extraProviderNames + additionalProviderNames).toSet()),
        providerRevision = MutableStateFlow(0L),
        providerFactory = { preferences -> extraProviders(preferences) +
            sharedLyricsProviders(http, preferences, lyprioBuildBaseUrl, lyprioBuildHmacKey) + additionalProviders(preferences) },
        networkAvailable = networkAvailable,
        // The provider's own message is what says whether the read failed or simply found nothing; the class name
        // alone made every ordinary miss look like a broken provider.
        reportFailure = { name, failure -> report("lyrics provider $name failed: ${failure.describe()}") },
    )

    private val state = MutableStateFlow<HostLyricsDocument?>(null)
    val current: StateFlow<HostLyricsDocument?> = state.asStateFlow()
    private val documentGate = Mutex()
    var revision: Long = 0L
        private set
    private val refresh = MutableStateFlow(0L)
    private var lastAttempt = 0L
    private var driver: Job? = null

    fun start() {
        driver = scope.launch {
            combine(player.snapshot, queue) { snapshot, queueState ->
                val id = snapshot?.occurrenceId ?: queueState.currentOccurrenceId
                id to queueState
            }.combine(refresh) { (id, queueState), attempt -> Triple(id, queueState, attempt) }
                .distinctUntilChanged { old, new ->
                    old.first == new.first && old.second.playOrder == new.second.playOrder && old.third == new.third
                }
                .collectLatest { (id, queueState, attempt) ->
                    // Only a retry asks the providers again; every other emission keeps the cached answer.
                    val force = attempt != lastAttempt
                    lastAttempt = attempt
                    follow(id, queueState, force)
                }
        }
    }

    /** Asks the providers again for the occurrence on screen, for the pane's retry control. */
    fun retry() {
        refresh.value += 1
    }

    /** A stale edit cannot replace a newer document or changed playback occurrence. */
    suspend fun replaceRaw(occurrenceId: String, expected: String, expectedRevision: Long,
        raw: String, guard: () -> Boolean = { true }): Boolean = documentGate.withLock {
        val before = state.value ?: return@withLock false
        val item = queue.value.entries.firstOrNull { it.occurrenceId == occurrenceId }?.item ?: return@withLock false
        if (before.occurrenceId != occurrenceId || before.raw != expected || revision != expectedRevision || !guard()) return@withLock false
        val parsed = parse(raw)
        val lines = parsed.lines
        require(raw.isNotBlank() && lines.isNotEmpty()) { "No usable lyrics" }
        if (storage?.write(item, raw, edited = true, guard = guard) == false) return@withLock false
        if (!guard() || state.value != before) return@withLock false
        revision++
        state.value = HostLyricsDocument(occurrenceId, lines, false, raw,
            if (LyricsDetection.isTtml(raw)) parsed.credits else null)
        true
    }

    fun close() {
        driver?.cancel()
    }

    private suspend fun follow(occurrenceId: String?, queueState: QueueState, force: Boolean = false) {
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
        if (state.value?.occurrenceId != occurrenceId) {
            state.value = HostLyricsDocument(occurrenceId, emptyList(), loading = true)
        } else if (force) {
            state.value = state.value?.copy(loading = true, status = HostLyricsStatus.LOADING)
        }
        val readRevision = revision
        var status = HostLyricsStatus.NOT_FOUND
        var fetched = false
        val (raw, parsed) = try {
            val saved = item?.let { readSaved(it) }?.takeIf { !force || it.edited }
            val cached = if (saved?.edited == true) saved.raw else storage?.read(requireNotNull(item), force) ?: saved?.raw
            if (cached != null) {
                status = HostLyricsStatus.READY
                cached to parse(cached)
            } else {
                val result = runtime.lyrics(request, forceRefresh = force)
                status = if (result.providerName.isEmpty()) HostLyricsStatus.NOT_FOUND else HostLyricsStatus.READY
                if (result.providerName.isEmpty()) "" to TtmlDocument(emptyList()) else {
                    fetched = true
                    result.lyrics to parse(result.lyrics)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            status = HostLyricsStatus.FAILED
            report("lyrics lookup failed: ${failure.describe()}")
            "" to TtmlDocument(emptyList())
        }
        if (status == HostLyricsStatus.NOT_FOUND) {
            // One line naming the track, so a miss for a track no provider knows is not mistaken for a broken lookup.
            report("lyrics lookup found nothing for \"${request.title} — ${request.artist}\"")
        }
        val lines = parsed.lines
        val credits = if (LyricsDetection.isTtml(raw)) parsed.credits else null
        documentGate.withLock {
            if (revision != readRevision || state.value?.occurrenceId != occurrenceId) return@withLock
            if (status == HostLyricsStatus.READY && lines.isNotEmpty() && fetched && item != null) {
                storage?.write(item, raw, edited = false)
                try { saveFetched(item, raw) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { report("lyrics persistence failed: ${failure::class.simpleName}") }
            }
            state.value = HostLyricsDocument(occurrenceId, lines, loading = false, raw = raw, credits = credits,
                status = if (status == HostLyricsStatus.READY && lines.isEmpty()) HostLyricsStatus.NOT_FOUND else status)
        }
    }

    private suspend fun parse(text: String): TtmlDocument =
        if (LyricsDetection.isTtml(text)) ttmlDocumentReader(settings.snapshots.value).readTtml(text)
        else TtmlDocument(LyricsParser.parse(text))

    private companion object {
        /** The runtime caps preloading at ten upcoming songs, whatever the setting says. */
        const val PRELOAD_LIMIT = 10
    }
}

/**
 * One diagnostic line that keeps the failure's own reason. The class name alone made an ordinary provider miss
 * ("Lyrics unavailable") indistinguishable from a broken lookup, which is what turned a track no provider knows into
 * fourteen looking-alike "failed: IllegalStateException" lines. The reason is one line and bounded; the lyrics
 * endpoints are public constants, so nothing from preferences or the Lyprio build pair can appear here.
 */
private fun Throwable.describe(): String {
    val reason = message?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    val name = this::class.simpleName.orEmpty().ifEmpty { "failure" }
    return if (reason == null) name else "$name: ${reason.take(MAX_REPORTED_REASON_CHARS)}"
}

/** Long enough for a provider's message, short enough for one log line. */
private const val MAX_REPORTED_REASON_CHARS = 160

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
 * Shared providers in canonical order. Paxsenix uses the user's endpoint and key when they are set. Lyprio
 * resolves through [resolveLyprioEndpoint] from the host's build-time pair
 * ([lyprioBuildBaseUrl]/[lyprioBuildHmacKey]) — its only endpoint, like Metrolist; a build without a
 * valid pair simply has no Lyprio provider. Its toggle is applied by the runtime like the others.
 */
fun sharedLyricsProviders(
    http: HttpClient,
    preferences: PreferenceSnapshot,
    lyprioBuildBaseUrl: String = "",
    lyprioBuildHmacKey: String = "",
): List<LyricsProvider> {
    val paxsenix = paxsenixSettingsOf(preferences)
    val lyprio = resolveLyprioEndpoint(lyprioBuildBaseUrl, lyprioBuildHmacKey)
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
