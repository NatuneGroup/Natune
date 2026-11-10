package moe.rukamori.archivetune.desktop

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.settings.DiscordDebugState
import moe.rukamori.archivetune.shared.ui.settings.SettingsController
import moe.rukamori.archivetune.desktop.integration.DesktopDeepLinks

private const val HELPER_TIMEOUT_MILLIS = 2_000L
private const val HELPER_KILL_MILLIS = 500L

/**
 * The XDG desktop entry that registers the Discord callback scheme. The Exec value is quoted with the
 * escaping the Desktop Entry specification applies before its quoting rule, so a path with spaces,
 * quotes, backslashes, dollars or percent signs still reaches the executable as one argument.
 */
internal fun discordDesktopEntry(executable: String, applicationId: String): String {
    require(executable.none { it.code < 0x20 }) { "the executable path cannot be represented in a desktop entry" }
    return "[Desktop Entry]\nType=Application\nName=ArchiveTune Discord\n" +
        "Exec=${desktopExecArgument(executable)} %u\n" +
        "MimeType=x-scheme-handler/discord-$applicationId;\nNoDisplay=true\n"
}

internal fun desktopExecArgument(value: String): String = buildString {
    append('"')
    for (character in value) {
        when (character) {
            // The file-level string unescape runs first, then Exec's quoted-argument rule.
            '\\' -> append("\\\\\\\\")
            '"', '`', '$' -> append("\\\\").append(character)
            '%' -> append("%%")
            else -> append(character)
        }
    }
    append('"')
}

/** Runs one registration helper without letting a blocked desktop tool hold OAuth open. */
private fun runRegistrationHelper(vararg command: String): Boolean {
    val process = runCatching {
        ProcessBuilder(*command)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
    }.getOrNull() ?: return false
    return try {
        if (process.waitFor(HELPER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            process.exitValue() == 0
        } else {
            process.destroyForcibly()
            process.waitFor(HELPER_KILL_MILLIS, TimeUnit.MILLISECONDS)
            false
        }
    } catch (_: InterruptedException) {
        process.destroyForcibly()
        Thread.currentThread().interrupt()
        false
    }
}

/**
 * The desktop Discord owner: PKCE OAuth against discord.com, a minimal-identify Gateway session and
 * playback-driven presence updates, exactly the contract the shared Discord settings pages expect
 * (no user token, no confidential client secret, IDENTIFY with intents=0, presence driven
 * by playback only — no background gateway retry loops). Every wire payload comes from the shared
 * protocol helpers, so both hosts emit the identical shapes.
 */
internal class DesktopDiscordPresence(
    private val graph: DesktopSessionGraph,
    private val links: DesktopDeepLinks,
    private val controller: SettingsController,
    private val report: (String) -> Unit,
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val secureRandom = SecureRandom()
    private val scope = graph.scope

    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var heartbeatSocket: WebSocket? = null
    // Reset before each beat and set again by the gateway's acknowledgement. Still false at the next beat means it went unanswered.
    @Volatile private var heartbeatAcked = true
    private var presenceDriver: Job? = null
    private var lastSequence: Int? = null

    private val debugFlow = MutableStateFlow(DiscordDebugState())

    /**
     * Live state for the developer card: [DiscordDebugState.running] is a live identified gateway, and the
     * timestamps are the track window the last presence update carried, the same facts Android's card reads.
     */
    val debug: StateFlow<DiscordDebugState> = debugFlow.asStateFlow()

    // ------------------------------------------------------------------ OAuth

    /**
     * Full PKCE authorization: registers the attempt on the desktop deep-link router, opens Discord's
     * authorize page in the system browser, awaits the custom-scheme callback, exchanges the code and
     * returns the session facts the page commits. Null on cancel, timeout or refusal — never a fake.
     */
    suspend fun authorize(): DiscordSessionFacts? = withContext(Dispatchers.IO) {
        registerDiscordScheme()
        val state = randomUrlSafe(24)
        val verifier = randomUrlSafe(64)
        val challenge = discordPkceChallenge(verifier)
        val callback = CompletableDeferred<String>()
        val handle = links.expectCallback(DISCORD_REDIRECT_URI, state) { raw -> callback.complete(raw) }
        try {
            if (!openDesktopUri(discordAuthorizeUrl(state, challenge), report)) return@withContext null
            val raw = withTimeoutOrNull(OAUTH_TIMEOUT_MS) { callback.await() } ?: return@withContext null
            val code = discordQueryParam(raw, "code") ?: return@withContext null
            exchangeCode(code, verifier)
        } finally {
            handle.close()
        }
    }

    private suspend fun exchangeCode(code: String, verifier: String): DiscordSessionFacts? =
        withContext(Dispatchers.IO) {
            val request = HttpRequest.newBuilder(URI.create(DISCORD_TOKEN_ENDPOINT))
                .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(discordTokenExchangeBody(code, verifier))).build()
            runCatching {
                val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() != 200) return@withContext null
                val grant = parseDiscordTokenGrant(response.body()) ?: return@withContext null
                DiscordSessionFacts(
                    accessToken = grant.accessToken,
                    refreshToken = grant.refreshToken,
                    expiresAtMillis = if (grant.expiresInSeconds > 0) {
                        System.currentTimeMillis() + grant.expiresInSeconds * 1000
                    } else {
                        0L
                    },
                    account = fetchAccountFacts(grant.accessToken),
                )
            }.getOrNull()
        }

    suspend fun verifyAccount(accessToken: String): DiscordAccountCheck = withContext(Dispatchers.IO) {
        runCatching {
            val request = HttpRequest.newBuilder(URI.create(DISCORD_CURRENT_USER_ENDPOINT))
                .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
                .header("Authorization", "Bearer $accessToken").GET().build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            when (response.statusCode()) {
                200 -> {
                    val facts = parseDiscordAccountFacts(response.body())
                    if (facts != null) DiscordAccountCheck.Verified(facts) else DiscordAccountCheck.Unverified
                }
                401, 403 -> DiscordAccountCheck.Rejected
                else -> DiscordAccountCheck.Unverified
            }
        }.getOrDefault(DiscordAccountCheck.Unverified)
    }

    private fun fetchAccountFacts(accessToken: String): DiscordAccountFacts? = runCatching {
        val request = HttpRequest.newBuilder(URI.create(DISCORD_CURRENT_USER_ENDPOINT))
            .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
            .header("Authorization", "Bearer $accessToken").GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) return null
        parseDiscordAccountFacts(response.body())
    }.getOrNull()

    /** A fresh valid access token: refreshes through Discord's grant when expired and a refresh token exists. */
    suspend fun validAccessToken(preferences: PreferenceSnapshot): String? = withContext(Dispatchers.IO) {
        val current = preferences[DiscordTokenKey, ""].takeIf { it.isNotBlank() } ?: return@withContext null
        val expiry = preferences.discordTokenExpiresAt()
        if (expiry == 0L || expiry - EXPIRY_SKEW_MS > System.currentTimeMillis()) return@withContext current
        val refresh = preferences[DiscordRefreshTokenKey] ?: return@withContext null
        val request = HttpRequest.newBuilder(URI.create(DISCORD_TOKEN_ENDPOINT))
            .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(discordTokenRefreshBody(refresh))).build()
        runCatching {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) return@withContext null
            val grant = parseDiscordTokenGrant(response.body()) ?: return@withContext null
            controller.edit {
                commitDiscordSignIn(
                    DiscordSessionFacts(
                        accessToken = grant.accessToken,
                        refreshToken = grant.refreshToken ?: refresh,
                        expiresAtMillis = if (grant.expiresInSeconds > 0) {
                            System.currentTimeMillis() + grant.expiresInSeconds * 1000
                        } else {
                            0L
                        },
                        account = null,
                    ),
                )
            }
            grant.accessToken
        }.getOrNull()
    }

    // ------------------------------------------------------------------ gateway + presence

    /** Bounded single-shot presence push used by the settings page's refresh action. */
    suspend fun refreshPresence(): Boolean {
        val preferences = controller.store.snapshots.value ?: return false
        val token = validAccessToken(preferences) ?: return false
        val socket = ensureGateway(token) ?: return false
        sendPresence(socket, preferences)
        return true
    }

    /** Playback-driven presence: starts the watcher once; it only acts on playback changes. */
    fun startPresenceDriver() {
        if (presenceDriver != null) return
        publishRunning()
        presenceDriver = scope.launch(Dispatchers.IO) {
            graph.metadata.collectLatest { metadata ->
                val preferences = controller.store.snapshots.value ?: return@collectLatest
                if (metadata == null || !preferences.discordRichPresenceEnabled()) {
                    closeGateway()
                    return@collectLatest
                }
                val playing = graph.player.snapshot.value?.playWhenReady == true ||
                    preferences[DiscordShowWhenPausedKey, DiscordSettingsDefaults.SHOW_WHEN_PAUSED]
                if (!playing) {
                    closeGateway()
                    return@collectLatest
                }
                val token = validAccessToken(preferences) ?: return@collectLatest
                ensureGateway(token)?.let { sendPresence(it, preferences) }
            }
        }
    }

    fun signOut() {
        closeGateway()
    }

    fun close() {
        presenceDriver?.cancel()
        closeGateway()
        publishRunning()
    }

    private fun closeGateway() {
        heartbeatJob?.cancel(); heartbeatJob = null; heartbeatSocket = null
        runCatching { webSocket?.sendClose(WebSocket.NORMAL_CLOSURE, "") }
        webSocket = null
        lastSequence = null
        publishRunning()
    }

    /** IDENTIFY stays minimal: intents=0, no capabilities, gateway v10, no compression. */
    private suspend fun ensureGateway(token: String): WebSocket? {
        webSocket?.let { return it }
        val identified = CompletableDeferred<Boolean>()
        val socket = http.newWebSocketBuilder()
            .buildAsync(URI.create(DISCORD_GATEWAY_URL), object : WebSocket.Listener {
                override fun onOpen(ws: WebSocket) {
                    ws.sendText(buildDiscordIdentifyJson(token, System.getProperty("os.name")), true)
                    ws.request(1)
                }

                override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
                    ws.request(1)
                    val event = parseDiscordGatewayEvent(data.toString())
                        ?: return CompletableFuture.completedFuture(null)
                    when (event.op) {
                        10 -> {
                            val interval = event.heartbeatIntervalMs
                            if (interval != null && interval > 0) startHeartbeat(ws, interval)
                            identified.complete(true)
                        }
                        1 -> ws.sendText(buildDiscordHeartbeatJson(lastSequence), true)
                        11 -> if (heartbeatSocket === ws) heartbeatAcked = true
                        0 -> lastSequence = event.sequence ?: lastSequence
                    }
                    return CompletableFuture.completedFuture(null)
                }

                override fun onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage<*> {
                    // Only this socket's heartbeat: a late close from a dropped socket must not stop a newer one.
                    if (heartbeatSocket === ws) { heartbeatJob?.cancel(); heartbeatJob = null; heartbeatSocket = null }
                    if (webSocket === ws) { webSocket = null; publishRunning() }
                    identified.complete(false)
                    return CompletableFuture.completedFuture(null)
                }

                override fun onError(ws: WebSocket, error: Throwable) {
                    if (heartbeatSocket === ws) { heartbeatJob?.cancel(); heartbeatJob = null; heartbeatSocket = null }
                    if (webSocket === ws) { webSocket = null; publishRunning() }
                    identified.complete(false)
                }
            }).join()
        return if (withTimeoutOrNull(10_000) { identified.await() } == true) {
            webSocket = socket
            publishRunning()
            socket
        } else {
            runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "") }
            null
        }
    }

    private fun startHeartbeat(socket: WebSocket, intervalMs: Long) {
        heartbeatJob?.cancel()
        heartbeatSocket = socket
        heartbeatAcked = true
        heartbeatJob = scope.launch(Dispatchers.IO) {
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
                socket.sendText(buildDiscordHeartbeatJson(lastSequence), true)
                delay(intervalMs)
            }
        }
    }

    private fun dropDeadGateway(socket: WebSocket) {
        if (webSocket === socket) {
            webSocket = null
            lastSequence = null
            publishRunning()
        }
        runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "heartbeat ack missed") }
    }

    /** Presence payload shaped like the Android client's: name/details/state/timestamps/assets/type. */
    private fun sendPresence(socket: WebSocket, preferences: PreferenceSnapshot) {
        val metadata = graph.metadata.value
        val snapshot = graph.player.snapshot.value
        val now = System.currentTimeMillis()
        val payload = buildDiscordPresenceJson(
            preferences = preferences,
            track = metadata?.let {
                DiscordPresenceTrack(
                    title = it.title,
                    artists = it.artists,
                    album = it.album,
                    artworkUri = it.artworkUri,
                )
            },
            positionMs = snapshot?.positionMs,
            durationMs = snapshot?.durationMs,
            paused = snapshot?.playWhenReady == false,
            nowMillis = now,
        )
        runCatching { socket.sendText(payload, true) }
        publishPresenceWindow(snapshot, now)
        publishRunning()
    }

    /**
     * The track window the just-sent update carried, like Android's last-RPC lines: an unpaused track with a
     * known duration has one, everything else clears the pair rather than keeping a stale window.
     */
    private fun publishPresenceWindow(snapshot: PlaybackSnapshot?, nowMs: Long) {
        val paused = snapshot?.playWhenReady == false
        val durationMs = snapshot?.durationMs?.takeIf { it > 0L }
        val positionMs = snapshot?.positionMs
        val start = if (paused || durationMs == null || positionMs == null) null
        else nowMs - positionMs.coerceAtLeast(0L)
        debugFlow.value = debugFlow.value.copy(lastStartMs = start, lastEndMs = start?.plus(durationMs ?: 0L))
    }

    /** [DiscordDebugState.running] is a live gateway under an active driver — optimistic like Android's started flag. */
    private fun publishRunning() {
        debugFlow.value = debugFlow.value.copy(running = presenceDriver?.isActive == true && webSocket != null)
    }

    // ------------------------------------------------------------------ helpers

    private fun randomUrlSafe(bytes: Int): String {
        val buffer = ByteArray(bytes)
        secureRandom.nextBytes(buffer)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
    }

    /**
     * Registers discord-<id> with the OS so callbacks reach the app. Per-user registration only —
     * Windows HKCU classes key, Linux .desktop + xdg-mime; macOS is declared in the packaged
     * Info.plist. Best-effort and bounded: a failure just means the OAuth wait times out.
     */
    private fun registerDiscordScheme() = runCatching {
        val os = System.getProperty("os.name").lowercase()
        when {
            "win" in os -> {
                val exe = ProcessHandle.current().info().command().orElse(null) ?: return@runCatching
                val key = "HKCU\\Software\\Classes\\discord-$DISCORD_APPLICATION_ID"
                listOf(
                    arrayOf("reg", "add", key, "/ve", "/d", "URL:ArchiveTune Discord", "/f"),
                    arrayOf("reg", "add", key, "/v", "URL Protocol", "/d", "", "/f"),
                    arrayOf("reg", "add", "$key\\shell\\open\\command", "/ve", "/d", "\"$exe\" \"%1\"", "/f"),
                ).forEach { command ->
                    if (!runRegistrationHelper(*command)) report("Discord scheme registration: reg add did not finish")
                }
            }
            "linux" in os || "unix" in os -> {
                val desktop = Path.of(
                    System.getProperty("user.home"), ".local", "share", "applications",
                    "archivetune-discord.desktop",
                )
                val exe = ProcessHandle.current().info().command().orElse(null) ?: return@runCatching
                Files.createDirectories(desktop.parent)
                Files.writeString(desktop, discordDesktopEntry(exe, DISCORD_APPLICATION_ID))
                val registered = runRegistrationHelper(
                    "xdg-mime", "default", "archivetune-discord.desktop",
                    "x-scheme-handler/discord-$DISCORD_APPLICATION_ID",
                )
                if (!registered) report("Discord scheme registration: xdg-mime did not finish")
            }
        }
    }

    private companion object {
        private const val REQUEST_TIMEOUT_MS = 12_000L
        private const val OAUTH_TIMEOUT_MS = 300_000L
        private const val EXPIRY_SKEW_MS = 60_000L
    }
}
