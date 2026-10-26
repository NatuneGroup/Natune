package moe.rukamori.archivetune.desktop

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.settings.SettingsController
import moe.rukamori.archivetune.desktop.integration.DesktopDeepLinks
import moe.rukamori.archivetune.desktop.integration.DesktopMediaMetadata

/**
 * The desktop Discord owner: PKCE OAuth against discord.com, a minimal-identify Gateway session and
 * playback-driven presence updates, exactly the contract the shared Discord settings pages expect
 * (AGENTS.md: no user token, no confidential client secret, IDENTIFY with intents=0, presence driven
 * by playback only — no background gateway retry loops).
 */
internal class DesktopDiscordPresence(
    private val graph: DesktopSessionGraph,
    private val links: DesktopDeepLinks,
    private val controller: SettingsController,
    private val report: (String) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val secureRandom = SecureRandom()
    private val scope = graph.scope

    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var presenceDriver: Job? = null
    private var lastSequence: Int? = null

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
        val challenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8)))
        val callback = CompletableDeferred<String>()
        val handle = links.expectCallback(REDIRECT_URI, state) { raw -> callback.complete(raw) }
        try {
            val authorizeUrl = "$AUTHORIZE_ENDPOINT?client_id=$APPLICATION_ID" +
                "&response_type=code&redirect_uri=${REDIRECT_URI.uriEncode()}" +
                "&scope=${OAUTH_SCOPES.uriEncode()}&state=$state" +
                "&code_challenge=$challenge&code_challenge_method=S256"
            if (!openDesktopUri(authorizeUrl, report)) return@withContext null
            val raw = withTimeoutOrNull(OAUTH_TIMEOUT_MS) { callback.await() } ?: return@withContext null
            val code = queryParam(raw, "code") ?: return@withContext null
            exchangeCode(code, verifier)
        } finally {
            handle.close()
        }
    }

    private suspend fun exchangeCode(code: String, verifier: String): DiscordSessionFacts? =
        withContext(Dispatchers.IO) {
            val body = "client_id=$APPLICATION_ID&grant_type=authorization_code" +
                "&code=${code.uriEncode()}&redirect_uri=${REDIRECT_URI.uriEncode()}" +
                "&code_verifier=${verifier.uriEncode()}"
            val request = HttpRequest.newBuilder(URI.create(TOKEN_ENDPOINT))
                .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build()
            runCatching {
                val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() != 200) return@withContext null
                val root = json.parseToJsonElement(response.body()).jsonObject
                val access = root["access_token"]?.jsonPrimitive?.content ?: return@withContext null
                val refresh = root["refresh_token"]?.jsonPrimitive?.content
                val expiresIn = root["expires_in"]?.jsonPrimitive?.longOrNull ?: 0L
                DiscordSessionFacts(
                    accessToken = access,
                    refreshToken = refresh,
                    expiresAtMillis = if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000 else 0L,
                    account = fetchAccountFacts(access),
                )
            }.getOrNull()
        }

    suspend fun verifyAccount(accessToken: String): DiscordAccountCheck = withContext(Dispatchers.IO) {
        runCatching {
            val request = HttpRequest.newBuilder(URI.create(CURRENT_USER_ENDPOINT))
                .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
                .header("Authorization", "Bearer $accessToken").GET().build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            when (response.statusCode()) {
                200 -> {
                    val facts = parseAccountFacts(response.body())
                    if (facts != null) DiscordAccountCheck.Verified(facts) else DiscordAccountCheck.Unverified
                }
                401, 403 -> DiscordAccountCheck.Rejected
                else -> DiscordAccountCheck.Unverified
            }
        }.getOrDefault(DiscordAccountCheck.Unverified)
    }

    private fun fetchAccountFacts(accessToken: String): DiscordAccountFacts? = runCatching {
        val request = HttpRequest.newBuilder(URI.create(CURRENT_USER_ENDPOINT))
            .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
            .header("Authorization", "Bearer $accessToken").GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) return null
        parseAccountFacts(response.body())
    }.getOrNull()

    private fun parseAccountFacts(body: String): DiscordAccountFacts? = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val username = root["username"]?.jsonPrimitive?.content ?: return null
        val display = root["global_name"]?.jsonPrimitive?.content ?: username
        val id = root["id"]?.jsonPrimitive?.content
        val avatar = root["avatar"]?.jsonPrimitive?.content
        val avatarUrl = if (id != null && avatar != null) "https://cdn.discordapp.com/avatars/$id/$avatar.png" else null
        DiscordAccountFacts(username = username, displayName = display, avatarUrl = avatarUrl)
    }.getOrNull()

    /** A fresh valid access token: refreshes through Discord's grant when expired and a refresh token exists. */
    suspend fun validAccessToken(preferences: PreferenceSnapshot): String? = withContext(Dispatchers.IO) {
        val current = preferences[DiscordTokenKey, ""].takeIf { it.isNotBlank() } ?: return@withContext null
        val expiry = preferences.discordTokenExpiresAt()
        if (expiry == 0L || expiry - EXPIRY_SKEW_MS > System.currentTimeMillis()) return@withContext current
        val refresh = preferences[DiscordRefreshTokenKey] ?: return@withContext null
        val body = "client_id=$APPLICATION_ID&grant_type=refresh_token&refresh_token=${refresh.uriEncode()}"
        val request = HttpRequest.newBuilder(URI.create(TOKEN_ENDPOINT))
            .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        runCatching {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) return@withContext null
            val root = json.parseToJsonElement(response.body()).jsonObject
            val access = root["access_token"]?.jsonPrimitive?.content ?: return@withContext null
            val newRefresh = root["refresh_token"]?.jsonPrimitive?.content
            val expiresIn = root["expires_in"]?.jsonPrimitive?.longOrNull ?: 0L
            controller.edit {
                commitDiscordSignIn(
                    DiscordSessionFacts(
                        accessToken = access,
                        refreshToken = newRefresh ?: refresh,
                        expiresAtMillis = if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000 else 0L,
                        account = null,
                    ),
                )
            }
            access
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
    }

    private fun closeGateway() {
        heartbeatJob?.cancel(); heartbeatJob = null
        runCatching { webSocket?.sendClose(WebSocket.NORMAL_CLOSURE, "") }
        webSocket = null
        lastSequence = null
    }

    /** IDENTIFY stays minimal: intents=0, no capabilities, gateway v10, no compression. */
    private suspend fun ensureGateway(token: String): WebSocket? {
        webSocket?.let { return it }
        val identified = CompletableDeferred<Boolean>()
        val socket = http.newWebSocketBuilder()
            .buildAsync(URI.create(GATEWAY_URL), object : WebSocket.Listener {
                override fun onOpen(ws: WebSocket) {
                    val identify = buildJsonObject {
                        put("op", 2)
                        put("d", buildJsonObject {
                            put("token", "Bearer $token")
                            put("intents", 0)
                            put("properties", buildJsonObject {
                                put("os", System.getProperty("os.name"))
                                put("browser", "ArchiveTune")
                                put("device", "ArchiveTune")
                            })
                        })
                    }
                    ws.sendText(identify.toString(), true)
                    ws.request(1)
                }

                override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
                    ws.request(1)
                    val event = runCatching { json.parseToJsonElement(data.toString()).jsonObject }.getOrNull()
                        ?: return CompletableFuture.completedFuture(null)
                    when (event["op"]?.jsonPrimitive?.intOrNull) {
                        10 -> {
                            val interval = event["d"]?.jsonObject?.get("heartbeat_interval")?.jsonPrimitive?.longOrNull
                            if (interval != null && interval > 0) startHeartbeat(ws, interval)
                            identified.complete(true)
                        }
                        1 -> ws.sendText(buildJsonObject { put("op", 1); put("d", lastSequence) }.toString(), true)
                        11 -> Unit
                        0 -> lastSequence = event["s"]?.jsonPrimitive?.intOrNull ?: lastSequence
                    }
                    return CompletableFuture.completedFuture(null)
                }

                override fun onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage<*> {
                    heartbeatJob?.cancel(); heartbeatJob = null
                    if (webSocket === ws) webSocket = null
                    identified.complete(false)
                    return CompletableFuture.completedFuture(null)
                }

                override fun onError(ws: WebSocket, error: Throwable) {
                    heartbeatJob?.cancel(); heartbeatJob = null
                    if (webSocket === ws) webSocket = null
                    identified.complete(false)
                }
            }).join()
        return if (withTimeoutOrNull(10_000) { identified.await() } == true) {
            webSocket = socket
            socket
        } else {
            runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "") }
            null
        }
    }

    private fun startHeartbeat(socket: WebSocket, intervalMs: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            delay((intervalMs * 0.7).toLong())
            while (true) {
                socket.sendText(buildJsonObject { put("op", 1); put("d", lastSequence) }.toString(), true)
                delay(intervalMs)
            }
        }
    }

    /** Presence payload shaped like the Android client's: name/details/state/timestamps/assets/type. */
    private fun sendPresence(socket: WebSocket, preferences: PreferenceSnapshot) {
        val metadata = graph.metadata.value
        val snapshot = graph.player.snapshot.value
        val paused = snapshot?.playWhenReady == false
        val status = preferences[DiscordPresenceStatusKey, DiscordSettingsDefaults.PRESENCE_STATUS]
        val activityType = when (preferences[DiscordActivityTypeKey, DiscordSettingsDefaults.ACTIVITY_TYPE]) {
            "PLAYING" -> 0
            "STREAMING" -> 1
            "LISTENING" -> 2
            "WATCHING" -> 3
            "COMPETING" -> 5
            else -> 2
        }
        val name = when (preferences.discordActivitySource(DiscordActivityNameKey, DiscordSettingsDefaults.ACTIVITY_NAME_SOURCE)) {
            DiscordActivitySource.APP -> "ArchiveTune"
            else -> metadata?.title ?: "ArchiveTune"
        }
        val details = when (preferences.discordActivitySource(DiscordActivityDetailsKey, DiscordSettingsDefaults.ACTIVITY_DETAILS_SOURCE)) {
            DiscordActivitySource.ARTIST -> metadata?.artists?.joinToString(", ")?.takeIf(String::isNotEmpty)
            DiscordActivitySource.ALBUM -> metadata?.album
            DiscordActivitySource.SONG -> metadata?.title
            DiscordActivitySource.APP -> "ArchiveTune"
        }
        val state = when (preferences.discordActivitySource(DiscordActivityStateKey, DiscordSettingsDefaults.ACTIVITY_STATE_SOURCE)) {
            DiscordActivitySource.ARTIST -> metadata?.artists?.joinToString(", ")?.takeIf(String::isNotEmpty)
            DiscordActivitySource.ALBUM -> metadata?.album
            DiscordActivitySource.SONG -> metadata?.title
            DiscordActivitySource.APP -> "ArchiveTune"
        }
        val activity = buildJsonObject {
            put("name", name)
            put("type", activityType)
            details?.let { put("details", it) }
            state?.let { put("state", it) }
            if (metadata != null && snapshot != null && !paused &&
                (snapshot.durationMs ?: 0) > 0 && (snapshot.positionMs ?: -1) >= 0
            ) {
                val now = System.currentTimeMillis()
                put("timestamps", buildJsonObject {
                    put("start", now - (snapshot.positionMs ?: 0))
                    put("end", now + ((snapshot.durationMs ?: 0) - (snapshot.positionMs ?: 0)))
                })
            }
            val assets = buildJsonObject {
                resolveImage(
                    preferences[DiscordLargeImageTypeKey, DiscordSettingsDefaults.LARGE_IMAGE],
                    preferences[DiscordLargeImageCustomUrlKey, ""], metadata,
                )?.let { put("large_image", it) }
                resolveImage(
                    preferences[DiscordSmallImageTypeKey, DiscordSettingsDefaults.SMALL_IMAGE],
                    preferences[DiscordSmallImageCustomUrlKey, ""], metadata,
                )?.let { put("small_image", it) }
            }
            if (assets.isNotEmpty()) put("assets", assets)
        }
        val payload = buildJsonObject {
            put("op", 3)
            put("d", buildJsonObject {
                put("status", status)
                put("afk", false)
                put("since", JsonNull)
                put("activities", kotlinx.serialization.json.buildJsonArray { add(activity) })
            })
        }
        runCatching { socket.sendText(payload.toString(), true) }
    }

    private fun resolveImage(type: String, custom: String, metadata: DesktopMediaMetadata?): String? = when (type) {
        "custom" -> custom.takeIf { it.isNotBlank() }
        "thumbnail", "artist" -> metadata?.artworkUri
        "appicon" -> "archivetune"
        else -> null
    }

    // ------------------------------------------------------------------ helpers

    private fun randomUrlSafe(bytes: Int): String {
        val buffer = ByteArray(bytes)
        secureRandom.nextBytes(buffer)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
    }

    private fun String.uriEncode(): String =
        java.net.URLEncoder.encode(this, Charsets.UTF_8).replace("+", "%20")

    private fun queryParam(uri: String, name: String): String? =
        URI(uri).rawQuery?.split("&")?.firstNotNullOfOrNull { part ->
            val pieces = part.split("=", limit = 2)
            if (pieces.size == 2 && pieces[0] == name) {
                java.net.URLDecoder.decode(pieces[1], Charsets.UTF_8)
            } else null
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
                val key = "HKCU\\Software\\Classes\\discord-$APPLICATION_ID"
                listOf(
                    arrayOf("reg", "add", key, "/ve", "/d", "URL:ArchiveTune Discord", "/f"),
                    arrayOf("reg", "add", key, "/v", "URL Protocol", "/d", "", "/f"),
                    arrayOf("reg", "add", "$key\\shell\\open\\command", "/ve", "/d", "\"$exe\" \"%1\"", "/f"),
                ).forEach { ProcessBuilder(*it).redirectErrorStream(true).start().waitFor() }
            }
            "linux" in os || "unix" in os -> {
                val desktop = Path.of(
                    System.getProperty("user.home"), ".local", "share", "applications",
                    "archivetune-discord.desktop",
                )
                val exe = ProcessHandle.current().info().command().orElse(null) ?: return@runCatching
                Files.createDirectories(desktop.parent)
                Files.writeString(
                    desktop,
                    "[Desktop Entry]\nType=Application\nName=ArchiveTune Discord\n" +
                        "Exec=$exe %u\nMimeType=x-scheme-handler/discord-$APPLICATION_ID;\nNoDisplay=true\n",
                )
                ProcessBuilder(
                    "xdg-mime", "default", "archivetune-discord.desktop",
                    "x-scheme-handler/discord-$APPLICATION_ID",
                ).redirectErrorStream(true).start().waitFor()
            }
        }
    }

    companion object {
        // Public Discord application id for ArchiveTune OAuth (same default as the Android build).
        private const val APPLICATION_ID = "1165706613961789445"
        private const val REDIRECT_URI = "discord-$APPLICATION_ID://authorize/callback"
        private const val OAUTH_SCOPES = "openid identify sdk.social_layer_presence"
        private const val AUTHORIZE_ENDPOINT = "https://discord.com/oauth2/authorize"
        private const val TOKEN_ENDPOINT = "https://discord.com/api/oauth2/token"
        private const val CURRENT_USER_ENDPOINT = "https://discord.com/api/v10/users/@me"
        private const val GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json"
        private const val REQUEST_TIMEOUT_MS = 12_000L
        private const val OAUTH_TIMEOUT_MS = 300_000L
        private const val EXPIRY_SKEW_MS = 60_000L
    }
}
