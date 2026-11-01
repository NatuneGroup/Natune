package moe.rukamori.archivetune.desktop

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.awt.Desktop
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.SpotifyLibrespotAccessTokenExpiresAtKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifyLibrespotAccessTokenKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifyLibrespotCatalogSpDcKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifyLibrespotRefreshTokenKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifyLibrespotUserIdKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifyProductCheckedAtKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifyProductKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifySpDcKey
import moe.rukamori.archivetune.shared.domain.settings.clearSpotifyLibrespotSession
import moe.rukamori.archivetune.shared.domain.settings.recordSpotifyLibraryProduct
import moe.rukamori.archivetune.shared.domain.settings.setSpotifyLibrespotSession
import moe.rukamori.archivetune.shared.domain.source.SPOTIFY_OAUTH_ME_ENDPOINT
import moe.rukamori.archivetune.shared.domain.source.SPOTIFY_OAUTH_TOKEN_ENDPOINT
import moe.rukamori.archivetune.shared.domain.source.SpotifyOAuthAccountFacts
import moe.rukamori.archivetune.shared.domain.source.SpotifyOAuthTokenGrant
import moe.rukamori.archivetune.shared.domain.source.SpotifyPremiumPolicy
import moe.rukamori.archivetune.shared.domain.source.SpotifyProduct
import moe.rukamori.archivetune.shared.domain.source.SpotifySignInDecision
import moe.rukamori.archivetune.shared.domain.source.SpotifySignInRefusal
import moe.rukamori.archivetune.shared.domain.source.SpotifyStreamingPolicy
import moe.rukamori.archivetune.shared.domain.source.parseSpotifyOAuthMe
import moe.rukamori.archivetune.shared.domain.source.parseSpotifyOAuthTokenGrant
import moe.rukamori.archivetune.shared.domain.source.spotifyOAuthAuthorizeUrl
import moe.rukamori.archivetune.shared.domain.source.spotifyPkceChallenge
import moe.rukamori.archivetune.shared.domain.source.spotifyTokenExchangeBody
import moe.rukamori.archivetune.shared.domain.source.spotifyTokenRefreshBody
import moe.rukamori.archivetune.shared.ui.settings.SettingsController

/** The way a desktop Librespot authorization attempt ends, mapped by the account owner to its rows and hints. */
internal sealed class LibrespotSignInOutcome {
    data object SignedIn : LibrespotSignInOutcome()

    class Refused(val refusal: SpotifySignInRefusal) : LibrespotSignInOutcome()

    data object Cancelled : LibrespotSignInOutcome()

    data object ListenerBusy : LibrespotSignInOutcome()

    data object BrowserUnavailable : LibrespotSignInOutcome()
}

/**
 * The desktop half of the Librespot engine's catalog-bound OAuth session. Authorization opens the
 * system browser at Spotify's PKCE authorize URL and waits for the registered loopback redirect on
 * `127.0.0.1:43821/spotify/callback` — bound to loopback only, one attempt at a time, strict state
 * match, five-minute deadline, closed after each run. [accessToken] hands the engine the grant's
 * bearer and mirrors the Android guards exactly: the stored catalog-cookie fingerprint is checked
 * before any cached or minted token leaves, the refresh commit re-reads the cookie inside the same
 * edit, the Premium recheck never writes an old account's product, and a final binding read stands
 * between the last write and the returned token. All mutations run under [gate], the same
 * commit-boundary discipline the other account owners keep.
 */
internal class DesktopSpotifyLibrespot(
    private val graph: DesktopSessionGraph,
    private val controller: SettingsController,
    private val catalogUserId: suspend (PreferenceSnapshot) -> String?,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val gate = Mutex()
    private val secureRandom = SecureRandom()

    // The catalog sp_dc whose account the grant was last verified against, and the verdict — checked
    // once per catalog session, like the dedicated account's collision check on every platform.
    @Volatile
    private var comparedCatalogSpDc: String? = null

    @Volatile
    private var catalogMatchesLibrespot = false

    val isConfigured: Boolean
        get() = DesktopCredentials.value("ARCHIVETUNE_SPOTIFY_OAUTH_CLIENT_ID").isNotBlank()

    /**
     * Runs browser authorize, loopback callback, code exchange and the two account rules — the
     * authorized account must be the catalog account and it must be Premium. Nothing is stored
     * unless every step succeeds, so a refused sign-in leaves no half-session.
     */
    suspend fun authorize(): LibrespotSignInOutcome = withContext(Dispatchers.IO) {
        gate.withLock {
            val clientId =
                DesktopCredentials.value("ARCHIVETUNE_SPOTIFY_OAUTH_CLIENT_ID").takeIf { it.isNotBlank() }
                    ?: return@withLock LibrespotSignInOutcome.Refused(SpotifySignInRefusal.INVALID_SESSION)
            val state = randomUrlSafeString(32)
            val verifier = randomUrlSafeString(64)
            val authorizeUrl =
                spotifyOAuthAuthorizeUrl(
                    clientId = clientId,
                    redirectUri = REDIRECT_URI,
                    state = state,
                    challenge = spotifyPkceChallenge(verifier),
                )
            val server =
                try {
                    ServerSocket().apply { bind(InetSocketAddress(LOOPBACK_HOST, CALLBACK_PORT), 1) }
                } catch (_: BindException) {
                    return@withLock LibrespotSignInOutcome.ListenerBusy
                }
            try {
                if (!openInBrowser(authorizeUrl)) return@withLock LibrespotSignInOutcome.BrowserUnavailable
                when (val callback = awaitCallback(server, state)) {
                    is Callback.Code -> {
                        val grant =
                            exchangeCode(clientId, callback.value, verifier)
                                ?: return@withLock LibrespotSignInOutcome.Refused(SpotifySignInRefusal.INVALID_SESSION)
                        // The authorization-code grant always carries a refresh token; a missing one
                        // means the response is not the session this flow was started for.
                        val refreshToken =
                            grant.refreshToken?.takeIf { it.isNotBlank() }
                                ?: return@withLock LibrespotSignInOutcome.Refused(SpotifySignInRefusal.INVALID_SESSION)
                        // The OAuth bearer reads the public profile directly — it is not a
                        // web-player token, so the GraphQL profile path cannot answer it.
                        val me =
                            fetchMe(grant.accessToken)
                                ?: return@withLock LibrespotSignInOutcome.Refused(SpotifySignInRefusal.PROFILE_UNAVAILABLE)
                        val prefs = snapshot()
                        val catalogSpDc = prefs[SpotifySpDcKey].orEmpty()
                        val catalogSignedIn = catalogSpDc.isNotBlank()
                        val catalogId = if (catalogSignedIn) catalogUserId(prefs) else null
                        when (
                            val decision =
                                SpotifyStreamingPolicy.decideLibrespotSignIn(
                                    catalogSignedIn = catalogSignedIn,
                                    librespotUserId = me.userId,
                                    product = me.product,
                                    catalogUserId = { catalogId },
                                )
                        ) {
                            is SpotifySignInDecision.Refuse -> LibrespotSignInOutcome.Refused(decision.reason)
                            SpotifySignInDecision.Accept -> {
                                val now = nowMs()
                                var catalogChanged = false
                                controller.edit {
                                    // The catalog session the decision compared against must not
                                    // have been replaced in the meantime; a drift aborts the whole
                                    // edit so nothing half-stores a grant bound to a vanished
                                    // account.
                                    if (this[SpotifySpDcKey].orEmpty() != catalogSpDc) {
                                        catalogChanged = true
                                    } else {
                                        setSpotifyLibrespotSession(
                                            refreshToken = refreshToken,
                                            accessToken = grant.accessToken,
                                            accessTokenExpiresAtMs = now + grant.expiresInSeconds * 1000L,
                                            userId = me.userId,
                                            accountName = me.displayName.orEmpty(),
                                            catalogSpDc = catalogSpDc,
                                        )
                                        recordSpotifyLibraryProduct(me.product, now)
                                    }
                                }
                                if (catalogChanged) {
                                    LibrespotSignInOutcome.Refused(SpotifySignInRefusal.CATALOG_UNVERIFIABLE)
                                } else {
                                    LibrespotSignInOutcome.SignedIn
                                }
                            }
                        }
                    }
                    Callback.Refused -> LibrespotSignInOutcome.Refused(SpotifySignInRefusal.INVALID_SESSION)
                    Callback.None -> LibrespotSignInOutcome.Cancelled
                }
            } finally {
                runCatching { server.close() }
            }
        }
    }

    /**
     * A valid access token for the librespot session — the credential the native engine binds with.
     * Null when no grant exists, the catalog session behind it was replaced, or the refresh was
     * rejected; the caller then treats the session as signed out.
     */
    suspend fun accessToken(): String? = withContext(Dispatchers.IO) {
        gate.withLock {
            val prefs = snapshot()
            val catalogSpDc = prefs[SpotifySpDcKey].orEmpty()
            // The stored fingerprint is the catalog sp_dc the grant was minted under. The grant
            // dies with that cookie — a replaced catalog session invalidates it here, before any
            // cached or refreshed token can leave. An unbound grant (catalog signed out at
            // authorization) falls back to the account-id check.
            val boundCatalogSpDc = prefs[SpotifyLibrespotCatalogSpDcKey].orEmpty()
            if (boundCatalogSpDc.isNotBlank() && boundCatalogSpDc != catalogSpDc) {
                controller.edit { clearSpotifyLibrespotSession() }
                return@withLock null
            }
            if (boundCatalogSpDc.isBlank() && catalogSpDc.isNotBlank()) {
                val librespotUserId = prefs[SpotifyLibrespotUserIdKey].orEmpty()
                if (librespotUserId.isNotBlank()) {
                    if (comparedCatalogSpDc != catalogSpDc) {
                        val catalogId = catalogUserId(prefs) ?: return@withLock null
                        catalogMatchesLibrespot = catalogId == librespotUserId
                        comparedCatalogSpDc = catalogSpDc
                    }
                    if (!catalogMatchesLibrespot) {
                        controller.edit { clearSpotifyLibrespotSession() }
                        return@withLock null
                    }
                }
            }

            val stored = prefs[SpotifyLibrespotAccessTokenKey].orEmpty()
            val expiresAt = prefs[SpotifyLibrespotAccessTokenExpiresAtKey] ?: 0L
            if (stored.isNotBlank() && expiresAt - TOKEN_EXPIRY_MARGIN_MS > nowMs()) {
                return@withLock stored
            }
            val refresh = prefs[SpotifyLibrespotRefreshTokenKey].orEmpty()
            if (refresh.isBlank()) return@withLock null

            val grant = refreshAccessToken(refresh) ?: return@withLock null
            val now = nowMs()
            // The catalog cookie is re-read inside the commit edit: a session swapped mid-refresh
            // must not leave a token minted under the old binding, so the grant goes with it.
            var catalogChanged = false
            controller.edit {
                if (this[SpotifySpDcKey].orEmpty() != catalogSpDc) {
                    clearSpotifyLibrespotSession()
                    catalogChanged = true
                } else {
                    this[SpotifyLibrespotAccessTokenKey] = grant.accessToken
                    this[SpotifyLibrespotAccessTokenExpiresAtKey] = now + grant.expiresInSeconds * 1000L
                    grant.refreshToken
                        ?.takeIf { rotated -> rotated.isNotBlank() }
                        ?.let { rotated -> this[SpotifyLibrespotRefreshTokenKey] = rotated }
                    if (boundCatalogSpDc.isBlank() && catalogSpDc.isNotBlank()) {
                        this[SpotifyLibrespotCatalogSpDcKey] = catalogSpDc
                    }
                }
            }
            if (catalogChanged) return@withLock null
            refreshLibraryProductIfDue(grant.accessToken, catalogSpDc)
            // One last binding read before the token leaves: a catalog session swapped during the
            // product recheck must not hand out a token minted under the old binding.
            if (snapshot()[SpotifySpDcKey].orEmpty() != catalogSpDc) {
                controller.edit { clearSpotifyLibrespotSession() }
                return@withLock null
            }
            grant.accessToken
        }
    }

    /** Drops the stored grant; the shared clear also turns streaming off when Librespot is the stored engine. */
    suspend fun signOut(): Unit = gate.withLock {
        comparedCatalogSpDc = null
        catalogMatchesLibrespot = false
        controller.edit { clearSpotifyLibrespotSession() }
        Unit
    }

    /**
     * The refresh-mint plan re-check, the same bounded read the Android slice runs: only when the
     * library product observation is stale does a minted token re-read the profile's product. The
     * catalog cookie is re-checked inside the edit, so a session swapped during the network read
     * never writes the old account's product onto the new one.
     */
    private suspend fun refreshLibraryProductIfDue(
        accessToken: String,
        catalogSpDc: String,
    ) {
        val prefs = snapshot()
        val now = nowMs()
        if (
            !SpotifyPremiumPolicy.recheckDue(
                SpotifyProduct.of(prefs[SpotifyProductKey]),
                prefs[SpotifyProductCheckedAtKey] ?: 0L,
                now,
                tokenJustMinted = true,
            )
        ) {
            return
        }
        val product = fetchMe(accessToken)?.product ?: return
        controller.edit {
            if (this[SpotifySpDcKey].orEmpty() != catalogSpDc) {
                clearSpotifyLibrespotSession()
            } else {
                recordSpotifyLibraryProduct(product, now)
            }
        }
    }

    private fun snapshot(): PreferenceSnapshot = graph.settings.snapshots.value ?: PreferenceSnapshot.EMPTY

    private suspend fun openInBrowser(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (!Desktop.isDesktopSupported()) return@withContext false
            val desktop = Desktop.getDesktop()
            if (!desktop.isSupported(Desktop.Action.BROWSE)) return@withContext false
            desktop.browse(URI(url))
            true
        }.getOrDefault(false)
    }

    /**
     * Waits for Spotify's redirect with the matching [state] on the loopback listener. Each request
     * gets a bounded, cancellable read — total head bytes and the request's own wall-clock deadline
     * are both capped, the client socket dies with a cancelled attempt — and requests for other
     * paths or a malformed query are answered and ignored. The loop gives up after the deadline and
     * cancellation interrupts it between accepts.
     */
    private suspend fun awaitCallback(
        server: ServerSocket,
        state: String,
    ): Callback =
        withContext(Dispatchers.IO) {
            server.soTimeout = ACCEPT_POLL_MS
            val deadline = nowMs() + CALLBACK_TIMEOUT_MS
            while (nowMs() < deadline) {
                currentCoroutineContext().ensureActive()
                val socket =
                    try {
                        server.accept()
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                // use() closes the client socket on every exit, including a cancelled read.
                val outcome = socket.use { answer(it, state) }
                if (outcome != null) return@withContext outcome
            }
            Callback.None
        }

    /** Null when this request was not the callback; otherwise the code, a refusal, or a declined sign-in. */
    private suspend fun answer(
        socket: Socket,
        state: String,
    ): Callback? {
        val head =
            try {
                readRequestHead(socket)
            } catch (_: IOException) {
                return null
            } ?: return null
        val target = head.substringBefore('\n').trimEnd('\r').split(' ').getOrNull(1) ?: return null
        val path = target.substringBefore('?')
        if (path != CALLBACK_PATH) {
            respond(socket, 404, null)
            return null
        }
        // A malformed escape is that request's failure, not the attempt's: answer it and keep
        // waiting for the real callback.
        val fields =
            try {
                queryFields(target.substringAfter('?', ""))
            } catch (_: IllegalArgumentException) {
                respond(socket, 400, null)
                return null
            }
        if (fields["state"] != state) {
            respond(socket, 404, null)
            return null
        }
        val code = fields["code"]?.takeIf { it.isNotBlank() }
        respond(
            socket,
            200,
            DesktopStrings[if (code != null) "spotify_librespot_auth_done" else "spotify_librespot_auth_failed"],
        )
        return if (code != null) Callback.Code(code) else Callback.Refused
    }

    /**
     * Reads one request head — request line plus headers — capped at [MAX_REQUEST_BYTES] total and
     * [REQUEST_DEADLINE_MS] wall time, checking cancellation between reads. Null on an over-limit,
     * stalled or headless connection; the caller's socket close cleans it up.
     */
    private suspend fun readRequestHead(socket: Socket): String? {
        val input = socket.getInputStream()
        val bytes = ByteArrayOutputStream()
        val deadline = nowMs() + REQUEST_DEADLINE_MS
        var last4 = 0
        while (bytes.size() < MAX_REQUEST_BYTES) {
            currentCoroutineContext().ensureActive()
            val remaining = (deadline - nowMs()).coerceAtMost(READ_TIMEOUT_MS)
            if (remaining <= 0) return null
            socket.soTimeout = remaining.coerceAtLeast(1L).toInt()
            val next =
                try {
                    input.read()
                } catch (_: SocketTimeoutException) {
                    return null
                }
            if (next < 0) return null
            bytes.write(next)
            last4 = (last4 shl 8) or next
            if (last4 == HEAD_END) return bytes.toString(Charsets.US_ASCII)
        }
        return null
    }

    /** Percent-decodes a=b&c=d pairs; a malformed escape throws IllegalArgumentException. */
    private fun queryFields(query: String): Map<String, String> =
        query.split('&').filter { it.isNotEmpty() }.associate { part ->
            URLDecoder.decode(part.substringBefore('='), Charsets.UTF_8) to
                URLDecoder.decode(part.substringAfter('=', ""), Charsets.UTF_8)
        }

    private fun respond(
        socket: Socket,
        status: Int,
        message: String?,
    ) {
        val body =
            if (message == null) {
                ""
            } else {
                val escaped = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                "<!doctype html><html><head><meta charset=\"utf-8\"><title>" + DesktopStrings["app_name"] +
                    "</title></head><body style=\"font-family:sans-serif;margin:3em\"><p>$escaped</p></body></html>"
            }
        val payload = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            else -> "Not Found"
        }
        val head =
            "HTTP/1.1 $status $reason\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${payload.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        try {
            socket.getOutputStream().apply {
                write(head.toByteArray(Charsets.US_ASCII))
                write(payload)
                flush()
            }
        } catch (_: IOException) {
            // The browser closed the tab; the code it already delivered still counts.
        }
    }

    private suspend fun exchangeCode(
        clientId: String,
        code: String,
        verifier: String,
    ): SpotifyOAuthTokenGrant? =
        postForm(
            spotifyTokenExchangeBody(
                clientId = clientId,
                redirectUri = REDIRECT_URI,
                code = code,
                verifier = verifier,
            ),
        )?.let(::parseSpotifyOAuthTokenGrant)

    private suspend fun refreshAccessToken(refreshToken: String): SpotifyOAuthTokenGrant? =
        postForm(
            spotifyTokenRefreshBody(
                clientId = DesktopCredentials.value("ARCHIVETUNE_SPOTIFY_OAUTH_CLIENT_ID"),
                refreshToken = refreshToken,
            ),
        )?.let(::parseSpotifyOAuthTokenGrant)

    /** The OAuth token's own profile read: /v1/me under the user-read-private scope it carries. */
    private suspend fun fetchMe(accessToken: String): SpotifyOAuthAccountFacts? =
        try {
            graph.http
                .get(SPOTIFY_OAUTH_ME_ENDPOINT) {
                    header("Authorization", "Bearer $accessToken")
                    header("Accept", "application/json")
                }.takeIf { it.status.isSuccess() }
                ?.bodyAsText()
                ?.let(::parseSpotifyOAuthMe)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    private suspend fun postForm(body: String): String? =
        try {
            graph.http
                .post(SPOTIFY_OAUTH_TOKEN_ENDPOINT) {
                    header("Accept", "application/json")
                    contentType(ContentType.parse("application/x-www-form-urlencoded"))
                    setBody(body)
                }.takeIf { it.status.isSuccess() }
                ?.bodyAsText()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    private fun randomUrlSafeString(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private sealed class Callback {
        class Code(val value: String) : Callback()

        data object Refused : Callback()

        data object None : Callback()
    }

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val CALLBACK_PORT = 43821
        const val CALLBACK_PATH = "/spotify/callback"
        const val REDIRECT_URI = "http://$LOOPBACK_HOST:$CALLBACK_PORT$CALLBACK_PATH"
        const val TOKEN_EXPIRY_MARGIN_MS = 60_000L
        const val ACCEPT_POLL_MS = 500
        const val READ_TIMEOUT_MS = 5_000L
        const val REQUEST_DEADLINE_MS = 10_000L
        const val CALLBACK_TIMEOUT_MS = 5 * 60_000L
        const val MAX_REQUEST_BYTES = 8 * 1024
        const val HEAD_END = 0x0D0A0D0A
    }
}
