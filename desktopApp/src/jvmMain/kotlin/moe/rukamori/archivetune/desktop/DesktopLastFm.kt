package moe.rukamori.archivetune.desktop

import io.ktor.client.HttpClient
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.data.lastfm.KtorLastFmService
import moe.rukamori.archivetune.shared.data.lastfm.LastFmScrobbleOwner
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmApiConfig
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmAppCredentials
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmDashboardReader
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmDashboardService
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmLoveMirror
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmLoveOutcome
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmPlay
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmScrobbleStore
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmScrobbleTiming
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmScrobbleTracker
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmSession
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmSessionPort
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmTrackMetadata
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.QueueState
import moe.rukamori.archivetune.shared.domain.settings.EnableLastFMScrobblingKey
import moe.rukamori.archivetune.shared.domain.settings.LastFMCustomEndpointKey
import moe.rukamori.archivetune.shared.domain.settings.LastFMSessionKey
import moe.rukamori.archivetune.shared.domain.settings.LastFMUseNowPlaying
import moe.rukamori.archivetune.shared.domain.settings.LastFMUsernameKey
import moe.rukamori.archivetune.shared.domain.settings.LastFmDefaults
import moe.rukamori.archivetune.shared.domain.settings.LastFmProvider
import moe.rukamori.archivetune.shared.domain.settings.LastFmSendLovesKey
import moe.rukamori.archivetune.shared.domain.settings.LastFmServiceConfig
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.ScrobbleDelayPercentKey
import moe.rukamori.archivetune.shared.domain.settings.ScrobbleDelaySecondsKey
import moe.rukamori.archivetune.shared.domain.settings.ScrobbleMinSongDurationKey
import moe.rukamori.archivetune.shared.domain.settings.clearLastFmSession
import moe.rukamori.archivetune.shared.domain.settings.commitLastFmSignIn
import moe.rukamori.archivetune.shared.domain.settings.lastFmCredentialsFor
import moe.rukamori.archivetune.shared.domain.settings.lastFmServiceConfig
import moe.rukamori.archivetune.shared.domain.settings.lastFmServiceConfigFromValues
import moe.rukamori.archivetune.shared.ui.settings.VaultSettingsStore

/** Where one web sign-in stands, for the settings page. */
internal sealed interface DesktopLastFmSignIn {
    data object Idle : DesktopLastFmSignIn

    data object Requesting : DesktopLastFmSignIn

    /** The approval page is open in the browser; [finish] exchanges the approved token. */
    data class Waiting(
        val provider: LastFmProvider,
        val token: String,
        val url: String,
        /** True after an exchange was refused, usually because the page was not approved yet. */
        val refused: Boolean = false,
    ) : DesktopLastFmSignIn

    data object Exchanging : DesktopLastFmSignIn

    data class Failed(val notConfigured: Boolean) : DesktopLastFmSignIn
}

/**
 * The desktop scrobbling owner: the signed Last.fm, Libre.fm or custom service, the persistent pending-scrobble queue,
 * and the playback driver that applies Last.fm's scrobble rule to the one player. The build supplies the Last.fm key
 * pair (ARCHIVETUNE_LASTFM_API_KEY/SECRET); without it Last.fm sign-in reports not configured, as on Android, and the
 * user's own key override still works.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DesktopLastFm(
    private val scope: CoroutineScope,
    private val http: HttpClient,
    private val settings: VaultSettingsStore,
    private val player: MusicPlayerController,
    private val queue: StateFlow<QueueState>,
    instanceDirectory: Path,
    private val report: (String) -> Unit,
) {
    val defaultApiKey: String = DesktopCredentials.value("ARCHIVETUNE_LASTFM_API_KEY")
    val defaultSecret: String = DesktopCredentials.value("ARCHIVETUNE_LASTFM_API_SECRET")

    private val service = KtorLastFmService(http) { apiConfig(serviceConfig()) }

    private val sessions = object : LastFmSessionPort {
        override suspend fun currentSession(): LastFmSession? {
            val snapshot = settings.snapshots.value ?: return null
            val key = snapshot[LastFMSessionKey]?.takeIf { it.isNotBlank() } ?: return null
            return LastFmSession(snapshot[LastFMUsernameKey].orEmpty(), key)
        }

        override suspend fun clearSessionIfCurrent(sessionKeyUsed: String) {
            settings.edit { if (this[LastFMSessionKey] == sessionKeyUsed) clearLastFmSession() }
        }
    }

    /** The dashboard reads through the same signed service and stored session as scrobbling. */
    val dashboardService: LastFmDashboardService get() = service
    val dashboardReader = LastFmDashboardReader(service, sessions) { apiConfig(serviceConfig()) }

    private val queueOwner = LastFmScrobbleOwner(
        scope = scope,
        service = service,
        sessions = sessions,
        store = FileScrobbleStore(instanceDirectory.resolve(SCROBBLE_FILE)),
        config = { apiConfig(serviceConfig()) },
        nowEpochSeconds = { System.currentTimeMillis() / 1000L },
        onFailure = { failure -> report("Last.fm queue: ${failure.javaClass.simpleName}") },
    )

    private val loves = LastFmLoveMirror(
        service = service,
        sessions = sessions,
        config = { apiConfig(serviceConfig()) },
        sendLovesEnabled = { settings.snapshots.value?.get(LastFmSendLovesKey) == true },
        onRequestFailed = queueOwner::onRequestFailed,
    )

    /** Plays waiting to be sent, across every account. */
    val pendingCount: StateFlow<Int> = queueOwner.pendingCount

    private val signInState = MutableStateFlow<DesktopLastFmSignIn>(DesktopLastFmSignIn.Idle)
    val signIn: StateFlow<DesktopLastFmSignIn> = signInState.asStateFlow()

    // The tracker is not locked, so every call to it and every timer it arms runs on this one serial dispatcher.
    private val trackerContext = Dispatchers.Default.limitedParallelism(1)
    private val trackerScope = CoroutineScope(scope.coroutineContext + trackerContext)
    private val tracker = LastFmScrobbleTracker(
        scope = trackerScope,
        wallClockSeconds = { System.currentTimeMillis() / 1000L },
        onNowPlaying = ::sendNowPlaying,
        onScrobblePoint = { track, startedAt ->
            if (settings.snapshots.value?.get(EnableLastFMScrobblingKey) == true) queueOwner.enqueue(track, startedAt)
        },
    )
    private var driver: Job? = null

    /** Loads the stored queue, sends what is due, and starts following playback. Called once by the session graph. */
    fun start() {
        queueOwner.flushSoon()
        driver = trackerScope.launch {
            combine(player.snapshot, queue, settings.snapshots) { snapshot, state, preferences ->
                DriverInput(
                    occurrenceId = snapshot?.occurrenceId,
                    playing = snapshot?.isAudioAdvancing ?: false,
                    durationMs = snapshot?.durationMs,
                    play = snapshot?.occurrenceId?.let { id -> state.playFor(id) },
                    enabled = preferences?.get(EnableLastFMScrobblingKey) == true,
                    nowPlaying = preferences?.get(LastFMUseNowPlaying) == true,
                    timing = preferences.scrobbleTiming(),
                )
            }.distinctUntilChanged().collect(::drive)
        }
    }

    fun close() {
        driver?.cancel()
        trackerScope.launch { tracker.destroy() }
    }

    suspend fun flushPending() = queueOwner.flush()

    /** Mirrors one written like to Last.fm when sending loves is on. Nothing is queued: a failed love is only logged. */
    fun mirrorLove(item: ContentItem, loved: Boolean) {
        scope.launch {
            val outcome = loves.mirror(
                artistNames = item.artists.map { it.name },
                title = item.title,
                albumTitle = item.album,
                isPodcast = item.ref.kind == ContentKind.EPISODE || item.ref.kind == ContentKind.PODCAST,
                loved = loved,
            )
            if (outcome is LastFmLoveOutcome.Failed) report("Last.fm love was not sent: ${outcome.error.javaClass.simpleName}")
        }
    }

    /**
     * Starts a web sign-in for [provider]: a token is requested with that service's key pair and its approval page opens
     * in the browser. A Last.fm sign-in without any key pair reports not configured and sends nothing.
     */
    fun beginSignIn(provider: LastFmProvider, openBrowser: (String) -> Unit) {
        val target = signInConfig(provider)
        if (target == null) {
            signInState.value = DesktopLastFmSignIn.Failed(notConfigured = true)
            return
        }
        signInState.value = DesktopLastFmSignIn.Requesting
        scope.launch {
            val client = KtorLastFmService(http) { target }
            val token = client.requestWebToken().getOrElse { failure ->
                if (failure is CancellationException) throw failure
                signInState.value = DesktopLastFmSignIn.Failed(notConfigured = false)
                return@launch
            }
            val url = client.authorizationUrl(token)
            signInState.value = DesktopLastFmSignIn.Waiting(provider, token, url)
            openBrowser(url)
        }
    }

    /** Exchanges the approved token and commits the account in one edit. A refused token keeps the account unchanged. */
    fun finishSignIn() {
        val waiting = signInState.value as? DesktopLastFmSignIn.Waiting ?: return
        val target = signInConfig(waiting.provider) ?: return
        signInState.value = DesktopLastFmSignIn.Exchanging
        scope.launch {
            val session = KtorLastFmService(http) { target }.exchangeWebToken(waiting.token).getOrElse { failure ->
                if (failure is CancellationException) throw failure
                signInState.value = waiting.copy(refused = true)
                report("Last.fm sign-in was not approved: ${failure.javaClass.simpleName}")
                return@launch
            }
            try {
                settings.edit {
                    if (waiting.provider == LastFmProvider.LASTFM) this[LastFMCustomEndpointKey] = ""
                    commitLastFmSignIn(waiting.provider, session.username, session.sessionKey)
                }
                signInState.value = DesktopLastFmSignIn.Idle
                queueOwner.flushSoon()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report("Last.fm sign-in was not saved: ${failure.javaClass.simpleName}")
                signInState.value = DesktopLastFmSignIn.Failed(notConfigured = false)
            }
        }
    }

    fun cancelSignIn() {
        signInState.value = DesktopLastFmSignIn.Idle
    }

    private fun serviceConfig(): LastFmServiceConfig =
        (settings.snapshots.value ?: PreferenceSnapshot.EMPTY).lastFmServiceConfig(defaultApiKey, defaultSecret)

    /** The configuration a sign-in to [provider] signs with, or null when that service has no usable key pair. */
    private fun signInConfig(provider: LastFmProvider): LastFmApiConfig? {
        val snapshot = settings.snapshots.value ?: PreferenceSnapshot.EMPTY
        val own = snapshot.lastFmCredentialsFor(provider)
        val config = lastFmServiceConfigFromValues(
            provider = provider,
            customEndpoint = if (provider == LastFmProvider.CUSTOM) snapshot[LastFMCustomEndpointKey].orEmpty() else "",
            apiKeyOverride = own.apiKey,
            secretOverride = own.secret,
            defaultApiKey = defaultApiKey,
            defaultSecret = defaultSecret,
        )
        return if (config.initialized) apiConfig(config) else null
    }

    private fun apiConfig(config: LastFmServiceConfig): LastFmApiConfig =
        LastFmApiConfig(config.endpoint, LastFmAppCredentials(config.apiKey, config.secret))

    private fun sendNowPlaying(track: LastFmTrackMetadata) {
        val preferences = settings.snapshots.value ?: return
        if (preferences[EnableLastFMScrobblingKey] != true || preferences[LastFMUseNowPlaying] != true) return
        scope.launch {
            val session = sessions.currentSession() ?: return@launch
            service.updateNowPlaying(track, session.sessionKey).onFailure { failure ->
                if (failure is CancellationException) throw failure
                queueOwner.onRequestFailed(failure, session.sessionKey)
            }
        }
    }

    private fun drive(input: DriverInput) {
        tracker.timing = input.timing
        tracker.useNowPlaying = input.nowPlaying
        val play = input.play
        if (!input.enabled || play == null) {
            tracker.onSongStop()
            return
        }
        tracker.onPlayerStateChanged(input.playing, play, input.durationMs)
    }

    private data class DriverInput(
        val occurrenceId: String?,
        val playing: Boolean,
        val durationMs: Long?,
        val play: LastFmPlay?,
        val enabled: Boolean,
        val nowPlaying: Boolean,
        val timing: LastFmScrobbleTiming,
    )

    private companion object {
        const val SCROBBLE_FILE = "lastfm-pending-scrobbles.json"
    }
}

/** The play the tracker sees for one occurrence. The occurrence id keeps a repeated song apart from its earlier play. */
private fun QueueState.playFor(occurrenceId: String): LastFmPlay? {
    val item = entries.firstOrNull { it.occurrenceId == occurrenceId }?.item ?: return null
    return LastFmPlay(
        id = occurrenceId,
        artistNames = item.artists.map { it.name },
        title = item.title,
        albumTitle = item.album,
        metadataDurationSeconds = ((item.durationMs ?: 0L) / 1000L).toInt(),
    )
}

private fun PreferenceSnapshot?.scrobbleTiming(): LastFmScrobbleTiming = LastFmScrobbleTiming(
    minSongDurationSeconds = this?.get(ScrobbleMinSongDurationKey) ?: LastFmDefaults.DEFAULT_SCROBBLE_MIN_SONG_DURATION,
    delayPercent = this?.get(ScrobbleDelayPercentKey) ?: LastFmDefaults.DEFAULT_SCROBBLE_DELAY_PERCENT,
    delaySeconds = this?.get(ScrobbleDelaySecondsKey) ?: LastFmDefaults.DEFAULT_SCROBBLE_DELAY_SECONDS,
)

/**
 * The pending-scrobble document in the instance directory. A write goes to a sibling file first and then replaces the
 * document in one move, so a crash leaves the previous document or the new one, never a partial file.
 */
private class FileScrobbleStore(private val file: Path) : LastFmScrobbleStore {
    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        if (Files.isRegularFile(file)) Files.readString(file) else null
    }

    override suspend fun write(document: String) = withContext(Dispatchers.IO) {
        val staging = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(staging, document)
        try {
            Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING)
        }
        Unit
    }
}
