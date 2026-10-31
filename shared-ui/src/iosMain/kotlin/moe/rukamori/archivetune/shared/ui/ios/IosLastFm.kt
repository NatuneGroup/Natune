@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.ui.ios

import io.ktor.client.HttpClient
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
import moe.rukamori.archivetune.shared.data.platform.ios.iosBuildSetting
import moe.rukamori.archivetune.shared.data.platform.ios.iosNowMillis
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmApiConfig
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmAppCredentials
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
import platform.Foundation.*

/** Where one web sign-in stands, for the settings page. */
internal sealed interface IosLastFmSignIn {
    data object Idle : IosLastFmSignIn

    data object Requesting : IosLastFmSignIn

    /** The approval page is open in the browser; [finish] exchanges the approved token. */
    data class Waiting(
        val provider: LastFmProvider,
        val token: String,
        val url: String,
        /** True after an exchange was refused, usually because the page was not approved yet. */
        val refused: Boolean = false,
    ) : IosLastFmSignIn

    data object Exchanging : IosLastFmSignIn

    data class Failed(val notConfigured: Boolean) : IosLastFmSignIn
}

/**
 * The iOS scrobbling owner: the signed Last.fm, Libre.fm or custom service, the persistent pending-scrobble queue in
 * the app container, and the playback driver that applies the shared scrobble rule to the one player. The build
 * supplies the Last.fm key pair from its Info.plist settings. The iOS page has no field for another Last.fm pair, so
 * Last.fm sign-in reports not configured without it. Libre.fm and custom services do not depend on it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class IosLastFm(
    private val scope: CoroutineScope,
    private val http: HttpClient,
    private val settings: VaultSettingsStore,
    private val player: MusicPlayerController,
    private val queue: StateFlow<QueueState>,
    private val report: (String) -> Unit,
) {
    val defaultApiKey: String = iosBuildSetting("ArchiveTuneLastFmApiKey")
    val defaultSecret: String = iosBuildSetting("ArchiveTuneLastFmApiSecret")

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

    private val queueOwner = LastFmScrobbleOwner(
        scope = scope,
        service = service,
        sessions = sessions,
        store = IosLastFmScrobbleStore(iosPendingScrobblePath()),
        config = { apiConfig(serviceConfig()) },
        nowEpochSeconds = { iosNowMillis() / 1000L },
        onFailure = { failure -> report("Last.fm queue: ${failure::class.simpleName}") },
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

    private val signInState = MutableStateFlow<IosLastFmSignIn>(IosLastFmSignIn.Idle)
    val signIn: StateFlow<IosLastFmSignIn> = signInState.asStateFlow()

    // The tracker is not locked, so every call and every timer it arms runs on this one serial dispatcher.
    private val trackerScope = CoroutineScope(scope.coroutineContext + Dispatchers.Default.limitedParallelism(1))
    private val tracker = LastFmScrobbleTracker(
        scope = trackerScope,
        wallClockSeconds = { iosNowMillis() / 1000L },
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
        driver = null
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
            if (outcome is LastFmLoveOutcome.Failed) {
                report("Last.fm love was not sent: ${outcome.error::class.simpleName}")
            }
        }
    }

    /**
     * Starts a web sign-in for [provider]: a token is requested with that service's key pair and its approval page
     * opens in the system browser. A Last.fm sign-in without any key pair reports not configured and sends nothing.
     */
    fun beginSignIn(provider: LastFmProvider, openBrowser: (String) -> Unit) {
        val target = signInConfig(provider)
        if (target == null) {
            signInState.value = IosLastFmSignIn.Failed(notConfigured = true)
            return
        }
        signInState.value = IosLastFmSignIn.Requesting
        scope.launch {
            val client = KtorLastFmService(http) { target }
            val token = client.requestWebToken().getOrElse { failure ->
                if (failure is CancellationException) throw failure
                signInState.value = IosLastFmSignIn.Failed(notConfigured = false)
                return@launch
            }
            val url = client.authorizationUrl(token)
            signInState.value = IosLastFmSignIn.Waiting(provider, token, url)
            openBrowser(url)
        }
    }

    /** Exchanges the approved token and commits the account in one edit. A refused token keeps the account unchanged. */
    fun finishSignIn() {
        val waiting = signInState.value as? IosLastFmSignIn.Waiting ?: return
        val target = signInConfig(waiting.provider) ?: return
        signInState.value = IosLastFmSignIn.Exchanging
        scope.launch {
            val session = KtorLastFmService(http) { target }.exchangeWebToken(waiting.token).getOrElse { failure ->
                if (failure is CancellationException) throw failure
                signInState.value = waiting.copy(refused = true)
                report("Last.fm sign-in was not approved: ${failure::class.simpleName}")
                return@launch
            }
            try {
                var committed = false
                settings.edit {
                    if (waiting.provider == LastFmProvider.LASTFM) this[LastFMCustomEndpointKey] = ""
                    committed = commitLastFmSignIn(waiting.provider, session.username, session.sessionKey)
                }
                if (committed) {
                    signInState.value = IosLastFmSignIn.Idle
                    queueOwner.flushSoon()
                } else {
                    // The commit refuses without writing, so no account is stored and the approved session is dropped.
                    report("Last.fm sign-in was not saved: the custom endpoint is not usable")
                    signInState.value = IosLastFmSignIn.Failed(notConfigured = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report("Last.fm sign-in was not saved: ${failure::class.simpleName}")
                signInState.value = IosLastFmSignIn.Failed(notConfigured = false)
            }
        }
    }

    fun cancelSignIn() {
        signInState.value = IosLastFmSignIn.Idle
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
        val playing: Boolean,
        val durationMs: Long?,
        val play: LastFmPlay?,
        val enabled: Boolean,
        val nowPlaying: Boolean,
        val timing: LastFmScrobbleTiming,
    )
}

/** The play the tracker sees for one occurrence. The occurrence id keeps a repeated song apart from its earlier play. */
internal fun QueueState.playFor(occurrenceId: String): LastFmPlay? {
    val item = entries.firstOrNull { it.occurrenceId == occurrenceId }?.item ?: return null
    return LastFmPlay(
        id = occurrenceId,
        artistNames = item.artists.map { it.name },
        title = item.title,
        albumTitle = item.album,
        metadataDurationSeconds = ((item.durationMs ?: 0L) / 1000L).toInt(),
        // The tracker never tracks a podcast play, so the flag has to come from the content kind here.
        isPodcast = item.ref.kind == ContentKind.EPISODE || item.ref.kind == ContentKind.PODCAST,
    )
}

internal fun PreferenceSnapshot?.scrobbleTiming(): LastFmScrobbleTiming = LastFmScrobbleTiming(
    minSongDurationSeconds = this?.get(ScrobbleMinSongDurationKey) ?: LastFmDefaults.DEFAULT_SCROBBLE_MIN_SONG_DURATION,
    delayPercent = this?.get(ScrobbleDelayPercentKey) ?: LastFmDefaults.DEFAULT_SCROBBLE_DELAY_PERCENT,
    delaySeconds = this?.get(ScrobbleDelaySecondsKey) ?: LastFmDefaults.DEFAULT_SCROBBLE_DELAY_SECONDS,
)

/** The pending-scrobble document beside the portable database. A write replaces the file atomically. */
internal fun iosPendingScrobblePath(): String {
    val manager = NSFileManager.defaultManager
    val support = (manager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask).first() as NSURL).path
        ?: error("Application Support is unavailable")
    val root = "$support/library"
    manager.createDirectoryAtPath(root, true, null, null)
    return "$root/lastfm-pending-scrobbles.json"
}

private class IosLastFmScrobbleStore(private val path: String) : LastFmScrobbleStore {
    override suspend fun read(): String? = withContext(Dispatchers.Default) {
        val documents = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)
        if (documents == null || documents.isEmpty()) null else documents
    }

    override suspend fun write(document: String) = withContext(Dispatchers.Default) {
        val written = (document as NSString).writeToFile(
            path = path,
            atomically = true,
            encoding = NSUTF8StringEncoding,
            error = null,
        )
        if (!written) error("the pending scrobble document could not be written")
    }
}
