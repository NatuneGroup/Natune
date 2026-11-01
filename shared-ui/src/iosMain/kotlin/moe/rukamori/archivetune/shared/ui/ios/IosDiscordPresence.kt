@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.io.encoding.ExperimentalEncodingApi::class)

package moe.rukamori.archivetune.shared.ui.ios

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherSocket
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherSocketListener
import moe.rukamori.archivetune.shared.data.platform.ios.IosListenTogetherSocketFactory
import moe.rukamori.archivetune.shared.data.platform.ios.iosNowMillis
import moe.rukamori.archivetune.shared.data.provider.readBoundedText
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.platform.BrowserAuthPort
import moe.rukamori.archivetune.shared.domain.platform.BrowserAuthRequest
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.QueueState
import moe.rukamori.archivetune.shared.domain.settings.*
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

/**
 * The iOS Discord owner, the same contract the desktop one fulfills: PKCE OAuth in the ASWebAuthenticationSession
 * sheet, a minimal-identify Gateway session on the shared NSURLSessionWebSocketTask wrapper and presence updates
 * driven by playback changes only — no user token, no client secret, no background gateway retry loops.
 *
 * The browser port owns the OAuth timeout. Heartbeats are the protocol's own mechanism: a beat Discord never
 * acknowledges drops the link once, and only a playback change identifies again.
 */
internal class IosDiscordPresence(
    private val scope: CoroutineScope,
    private val http: HttpClient,
    private val settings: SettingsStore,
    private val browser: BrowserAuthPort,
    private val player: MusicPlayerController,
    private val queue: StateFlow<QueueState>,
    private val report: (String) -> Unit,
) {
    private val sockets = IosListenTogetherSocketFactory(
        NSURLSession.sessionWithConfiguration(NSURLSessionConfiguration.ephemeralSessionConfiguration),
        textFrames = true,
    )

    @Volatile private var gateway: ListenTogetherSocket? = null
    private var heartbeatJob: Job? = null

    @Volatile private var heartbeatSocket: ListenTogetherSocket? = null

    /** Reset before each beat and set again by the gateway's acknowledgement. False at the next beat is a dead link. */
    @Volatile private var heartbeatAcked = true

    private var presenceDriver: Job? = null

    @Volatile private var lastSequence: Int? = null

    // ------------------------------------------------------------------ OAuth

    /**
     * Full PKCE authorization: the ephemeral OS sheet loads Discord's authorize page and returns the callback, the
     * code is exchanged for the session facts the page commits. Null on cancel or refusal — never a fake.
     */
    suspend fun authorize(): DiscordSessionFacts? {
        val state = secureUrlSafe(24)
        val verifier = secureUrlSafe(64)
        val result = try {
            browser.authorize(
                BrowserAuthRequest(
                    provider = "discord",
                    scope = CredentialScope.PERSONAL,
                    authorizationUrl = discordAuthorizeUrl(state, discordPkceChallenge(verifier)),
                    redirectUri = DISCORD_REDIRECT_URI,
                    state = state,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        val code = discordQueryParam(result.callbackUri, "code") ?: return null
        return exchangeCode(code, verifier)
    }

    private suspend fun exchangeCode(code: String, verifier: String): DiscordSessionFacts? {
        val (status, body) = postForm(DISCORD_TOKEN_ENDPOINT, discordTokenExchangeBody(code, verifier))
            ?: return null
        if (status != 200 || body == null) return null
        val grant = parseDiscordTokenGrant(body) ?: return null
        return DiscordSessionFacts(
            accessToken = grant.accessToken,
            refreshToken = grant.refreshToken,
            expiresAtMillis = if (grant.expiresInSeconds > 0) {
                iosNowMillis() + grant.expiresInSeconds * 1000
            } else {
                0L
            },
            account = fetchAccountFacts(grant.accessToken),
        )
    }

    /** One real Discord API read of a bearer token: Verified, Rejected on 401/403, Unverified otherwise. */
    suspend fun verifyAccount(accessToken: String): DiscordAccountCheck {
        val (status, body) = getWithBearer(DISCORD_CURRENT_USER_ENDPOINT, accessToken) ?: return DiscordAccountCheck.Unverified
        return when {
            status == 200 -> body?.let(::parseDiscordAccountFacts)
                ?.let { DiscordAccountCheck.Verified(it) } ?: DiscordAccountCheck.Unverified
            status == 401 || status == 403 -> DiscordAccountCheck.Rejected
            else -> DiscordAccountCheck.Unverified
        }
    }

    private suspend fun fetchAccountFacts(accessToken: String): DiscordAccountFacts? {
        val (status, body) = getWithBearer(DISCORD_CURRENT_USER_ENDPOINT, accessToken) ?: return null
        if (status != 200 || body == null) return null
        return parseDiscordAccountFacts(body)
    }

    /** A fresh valid access token: refreshes through Discord's grant when expired and a refresh token exists. */
    suspend fun validAccessToken(preferences: PreferenceSnapshot): String? {
        val current = preferences[DiscordTokenKey, ""].takeIf { it.isNotBlank() } ?: return null
        val expiry = preferences.discordTokenExpiresAt()
        if (expiry == 0L || expiry - EXPIRY_SKEW_MS > iosNowMillis()) return current
        val refresh = preferences[DiscordRefreshTokenKey] ?: return null
        val (status, body) = postForm(DISCORD_TOKEN_ENDPOINT, discordTokenRefreshBody(refresh)) ?: return null
        if (status != 200 || body == null) return null
        val grant = parseDiscordTokenGrant(body) ?: return null
        try {
            settings.edit {
                commitDiscordSignIn(
                    DiscordSessionFacts(
                        accessToken = grant.accessToken,
                        refreshToken = grant.refreshToken ?: refresh,
                        expiresAtMillis = if (grant.expiresInSeconds > 0) {
                            iosNowMillis() + grant.expiresInSeconds * 1000
                        } else {
                            0L
                        },
                        account = null,
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        return grant.accessToken
    }

    // ------------------------------------------------------------------ gateway + presence

    /** Bounded single-shot presence push used by the settings page's refresh action. */
    suspend fun refreshPresence(): Boolean {
        val preferences = settings.snapshots.value ?: return false
        val token = validAccessToken(preferences) ?: return false
        val socket = ensureGateway(token) ?: return false
        sendPresence(socket, preferences)
        return true
    }

    /**
     * Playback-driven presence: the watcher acts only when the playing occurrence, its transport state or its
     * metadata changes. A null occurrence, a disabled feature or a pause without the show-when-paused choice closes
     * the gateway; nothing retries on a timer.
     */
    fun startPresenceDriver() {
        if (presenceDriver != null) return
        presenceDriver = scope.launch {
            combine(player.snapshot, queue) { snapshot, _ -> snapshot }
                .map { snapshot ->
                    snapshot?.let { current ->
                        val item = metadataOf(current.occurrenceId)
                        PresenceSource(
                            occurrenceId = current.occurrenceId,
                            playWhenReady = current.playWhenReady,
                            title = item?.title,
                            artists = item?.artists?.map { it.name }?.takeIf { it.isNotEmpty() }
                                ?: listOfNotNull(item?.subtitle?.takeIf { it.isNotBlank() }),
                            album = item?.album,
                            artworkUri = item?.artworkUri,
                        )
                    }
                }
                .distinctUntilChanged()
                .collectLatest { source ->
                    val preferences = settings.snapshots.value ?: return@collectLatest
                    if (source == null || !preferences.discordRichPresenceEnabled()) {
                        closeGateway()
                        return@collectLatest
                    }
                    val playing = source.playWhenReady ||
                        preferences[DiscordShowWhenPausedKey, DiscordSettingsDefaults.SHOW_WHEN_PAUSED]
                    if (!playing) {
                        closeGateway()
                        return@collectLatest
                    }
                    val token = validAccessToken(preferences) ?: return@collectLatest
                    ensureGateway(token)?.let { sendPresence(it, preferences, source) }
                }
        }
    }

    fun signOut() {
        closeGateway()
    }

    fun close() {
        presenceDriver?.cancel()
        presenceDriver = null
        closeGateway()
    }

    private fun closeGateway() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        heartbeatSocket = null
        gateway?.close(NORMAL_CLOSE_CODE, "")
        gateway = null
        lastSequence = null
    }

    /** IDENTIFY stays minimal: intents=0, no capabilities, gateway v10, no compression. */
    private suspend fun ensureGateway(token: String): ListenTogetherSocket? {
        gateway?.let { return it }
        val identified = CompletableDeferred<Boolean>()
        // Callbacks may arrive on the session queue before open() returns, so the socket reference they read lives
        // behind a holder; the first send still only happens after the handshake's pong proved it open.
        val socketRef = SocketHolder()
        val socket = try {
            sockets.open(DISCORD_GATEWAY_URL, object : ListenTogetherSocketListener {
                override fun onOpen() {
                    socketRef.socket?.send(buildDiscordIdentifyJson(token, IDENTIFY_OS).encodeToByteArray())
                }

                override fun onMessage(frame: ByteArray) {
                    val text = runCatching { frame.decodeToString() }.getOrNull() ?: return
                    val event = parseDiscordGatewayEvent(text) ?: return
                    when (event.op) {
                        10 -> {
                            val interval = event.heartbeatIntervalMs
                            if (interval != null && interval > 0) socketRef.socket?.let { startHeartbeat(it, interval) }
                            identified.complete(true)
                        }
                        1 -> socketRef.socket?.send(buildDiscordHeartbeatJson(lastSequence).encodeToByteArray())
                        11 -> if (heartbeatSocket === socketRef.socket) heartbeatAcked = true
                        0 -> lastSequence = event.sequence ?: lastSequence
                    }
                }

                override fun onClosing(code: Int, reason: String) = Unit

                override fun onClosed(code: Int, reason: String) {
                    dropSocket(socketRef.socket)
                    identified.complete(false)
                }

                override fun onFailure(cause: Throwable?) {
                    report("discord gateway failed: ${cause?.let { it::class.simpleName } ?: "transport"}")
                    dropSocket(socketRef.socket)
                    identified.complete(false)
                }
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        socketRef.socket = socket
        return if (withTimeoutOrNull(IDENTIFY_TIMEOUT_MS) { identified.await() } == true) {
            gateway = socket
            socket
        } else {
            socket.cancel()
            null
        }
    }

    private fun startHeartbeat(socket: ListenTogetherSocket, intervalMs: Long) {
        heartbeatJob?.cancel()
        heartbeatSocket = socket
        heartbeatAcked = true
        heartbeatJob = scope.launch {
            // Discord asks for a random first beat inside one interval, so clients do not all beat at the same instant.
            delay((intervalMs * Random.nextDouble()).toLong())
            while (true) {
                if (!heartbeatAcked) {
                    // The previous beat was never acknowledged, so the link is dead. Drop it once; the next
                    // playback update identifies again, and nothing here retries on a timer.
                    dropDeadGateway(socket)
                    return@launch
                }
                heartbeatAcked = false
                socket.send(buildDiscordHeartbeatJson(lastSequence).encodeToByteArray())
                delay(intervalMs)
            }
        }
    }

    private fun dropDeadGateway(socket: ListenTogetherSocket) {
        if (gateway === socket) {
            gateway = null
            lastSequence = null
        }
        socket.close(NORMAL_CLOSE_CODE, "heartbeat ack missed")
    }

    private fun dropSocket(socket: ListenTogetherSocket?) {
        if (heartbeatSocket === socket) {
            heartbeatJob?.cancel()
            heartbeatJob = null
            heartbeatSocket = null
        }
        if (gateway === socket) {
            gateway = null
            lastSequence = null
        }
    }

    /** Presence for the current playback facts, in the shared wire shape both hosts send. */
    private fun sendPresence(
        socket: ListenTogetherSocket,
        preferences: PreferenceSnapshot,
        source: PresenceSource = currentSource(),
    ) {
        val snapshot = player.snapshot.value
        val payload = buildDiscordPresenceJson(
            preferences = preferences,
            track = DiscordPresenceTrack(
                title = source.title,
                artists = source.artists,
                album = source.album,
                artworkUri = source.artworkUri,
            ),
            positionMs = snapshot?.positionMs,
            durationMs = snapshot?.durationMs,
            paused = snapshot?.playWhenReady == false,
            nowMillis = iosNowMillis(),
        )
        socket.send(payload.encodeToByteArray())
    }

    /** The refresh action's source: whatever the player holds now, resolved the same way the driver resolves it. */
    private fun currentSource(): PresenceSource {
        val snapshot = player.snapshot.value
        val item = snapshot?.let { metadataOf(it.occurrenceId) }
        return PresenceSource(
            occurrenceId = snapshot?.occurrenceId.orEmpty(),
            playWhenReady = snapshot?.playWhenReady == true,
            title = item?.title,
            artists = item?.artists?.map { it.name }?.takeIf { it.isNotEmpty() }
                ?: listOfNotNull(item?.subtitle?.takeIf { it.isNotBlank() }),
            album = item?.album,
            artworkUri = item?.artworkUri,
        )
    }

    private fun metadataOf(occurrenceId: String): ContentItem? =
        queue.value.entries.firstOrNull { it.occurrenceId == occurrenceId }?.item

    // ------------------------------------------------------------------ http + random

    /** One bounded form POST. The status and body come back; transport failure is null, never a raised error. */
    private suspend fun postForm(url: String, body: String): Pair<Int, String?>? = try {
        withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            val response = http.post(url) {
                header(HttpHeaders.Accept, "application/json")
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(body)
            }
            response.status.value to response.readBoundedText(MAX_RESPONSE_BYTES)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    /** One bounded bearer GET, the same read the settings page's saved-token check performs. */
    private suspend fun getWithBearer(url: String, accessToken: String): Pair<Int, String?>? = try {
        withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            val response = http.get(url) {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header(HttpHeaders.Accept, "application/json")
            }
            response.status.value to response.readBoundedText(MAX_RESPONSE_BYTES)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun secureUrlSafe(bytes: Int): String = Base64.UrlSafe.encode(secureBytes(bytes)).trimEnd('=')

    /** Bytes from the system's secure generator, so the verifier and state are never predictable. */
    private fun secureBytes(count: Int): ByteArray {
        val out = ByteArray(count)
        if (count == 0) return out
        val status = out.usePinned { SecRandomCopyBytes(kSecRandomDefault, count.toULong(), it.addressOf(0)) }
        check(status == errSecSuccess) { "Secure random bytes are unavailable" }
        return out
    }

    /** The gateway reference a socket's callbacks read, since they can outrun the open() return. */
    private class SocketHolder {
        @Volatile var socket: ListenTogetherSocket? = null
    }

    /** The playback facts a presence emission is keyed on; position deliberately takes no part. */
    private data class PresenceSource(
        val occurrenceId: String,
        val playWhenReady: Boolean,
        val title: String?,
        val artists: List<String>,
        val album: String?,
        val artworkUri: String?,
    )

    private companion object {
        const val IDENTIFY_OS = "ios"
        const val NORMAL_CLOSE_CODE = 1000
        const val REQUEST_TIMEOUT_MS = 12_000L
        const val IDENTIFY_TIMEOUT_MS = 10_000L
        const val EXPIRY_SKEW_MS = 60_000L
        const val MAX_RESPONSE_BYTES = 256 * 1024
    }
}
