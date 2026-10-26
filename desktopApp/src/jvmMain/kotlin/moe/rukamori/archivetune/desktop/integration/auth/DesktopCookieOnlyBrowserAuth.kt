package moe.rukamori.archivetune.desktop.integration.auth

import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.domain.platform.AuthorizedHttpsOrigin
import moe.rukamori.archivetune.shared.domain.platform.CookieOnlyBrowserAuthPort
import moe.rukamori.archivetune.shared.domain.platform.CookieOnlyLoginFailure
import moe.rukamori.archivetune.shared.domain.platform.CookieOnlyLoginOutcome
import moe.rukamori.archivetune.shared.domain.platform.CookieOnlyLoginRequest
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.domain.platform.PlatformSecret
import moe.rukamori.archivetune.shared.domain.platform.PrivateCookie
import moe.rukamori.archivetune.shared.domain.platform.PrivateReusableContext

/*
 * Bounds are the native contract: the native side never sends more rows, and anything larger is treated as a
 * failed read rather than trimmed, so a truncated cookie set can never look like a complete login.
 */
private const val LOGIN_TIMEOUT_MILLIS = 300_000L
private const val COOKIE_TIMEOUT_MILLIS = 10_000L
private const val COOKIE_FIELDS = 8
private const val MAX_COOKIE_ROWS = 256
private const val MAX_COOKIE_BYTES = 1_048_576L

/* Only plain ASCII hosts are accepted. Anything else could map to a different origin on the native side. */
private val PLAIN_HOST = Regex("[a-z0-9.-]+")

/**
 * Cookie-only sign-in in the native browser window. The window is isolated: its storage is ephemeral, the native side
 * refuses every navigation and response outside the authorized origins, and cookies are read only for those origins.
 * Cookies leave native code only inside the [PrivateReusableContext] returned to the caller.
 *
 * A sign-in completes only when the user presses Finish and every authorized origin's cookies were read. Closing the
 * window earlier is a cancellation, so a half-finished sign-in never produces a context and never commits anything.
 *
 * This adapter needs WebKitGTK 4.1 on Linux, the WebView2 runtime on Windows, or WKWebView on macOS. When the native
 * library or a display is missing it reports [CookieOnlyLoginFailure.BROWSER_UNAVAILABLE]. It never falls back to an
 * external browser and never reports success.
 */
class DesktopCookieOnlyBrowserAuth(
    private val loginTimeoutMillis: Long = LOGIN_TIMEOUT_MILLIS,
) : CookieOnlyBrowserAuthPort {
    /* One live attempt per provider and scope. Guarded by its own monitor, and every critical section is short. */
    private val live = HashMap<AttemptKey, CookieOnlyAttempt>()

    /**
     * Cancelling the calling coroutine closes only this attempt. The native window opens in a non-cancellable section,
     * so its handle is never lost, and the window is closed in `finally` on every path.
     */
    override suspend fun login(request: CookieOnlyLoginRequest): CookieOnlyLoginOutcome {
        val origins = request.authorizedOrigins.mapNotNull { it.canonicalOrNull() }
        if (origins.size != request.authorizedOrigins.size) return refused()
        val start = startUriFor(request.startUrl, origins.toSet()) ?: return refused()

        val key = AttemptKey(request.provider, request.scope)
        val attempt = CookieOnlyAttempt(request.generation)
        if (!claim(key, attempt)) return CookieOnlyLoginOutcome.Cancelled

        var handle = 0L
        try {
            handle = withContext(Dispatchers.IO + NonCancellable) {
                openWindow(start.toASCIIString(), origins, attempt)
            }
            if (handle == 0L) return CookieOnlyLoginOutcome.Failed(CookieOnlyLoginFailure.BROWSER_UNAVAILABLE)
            val event = withTimeoutOrNull(loginTimeoutMillis) { attempt.events.await() }
                ?: return CookieOnlyLoginOutcome.Failed(CookieOnlyLoginFailure.TIMED_OUT)
            return when (event) {
                AttemptEvent.Finished -> readContext(handle, request, origins, attempt)
                is AttemptEvent.Closed ->
                    if (event.failed) CookieOnlyLoginOutcome.Failed(CookieOnlyLoginFailure.BROWSER_UNAVAILABLE)
                    else CookieOnlyLoginOutcome.Cancelled
                AttemptEvent.Replaced -> CookieOnlyLoginOutcome.Cancelled
            }
        } finally {
            release(key, attempt)
            if (handle != 0L) NativeCookieBrowser.close(handle)
        }
    }

    /*
     * Nothing persists between attempts: every window has its own ephemeral storage. Clearing a profile therefore only
     * ends an attempt that is still open for the same provider and scope.
     */
    override suspend fun clearProfile(provider: String, scope: CredentialScope) {
        synchronized(live) { live[AttemptKey(provider, scope)] }?.supersede()
    }

    /*
     * A newer generation replaces the live attempt. An older generation arriving late is refused and must not end the
     * newer window, because the newest request wins.
     */
    private fun claim(key: AttemptKey, attempt: CookieOnlyAttempt): Boolean {
        synchronized(live) {
            val current = live[key]
            if (current != null && current.generation > attempt.generation) return false
            current?.supersede()
            live[key] = attempt
            return true
        }
    }

    private fun release(key: AttemptKey, attempt: CookieOnlyAttempt) {
        synchronized(live) {
            if (live[key] === attempt) live.remove(key)
        }
    }
}

private data class AttemptKey(val provider: String, val scope: CredentialScope)

/** How a live attempt ended from the window's side. The first event wins; later ones change nothing. */
private sealed interface AttemptEvent {
    /** The user pressed Finish, so the cookies of every authorized origin may now be read. */
    data object Finished : AttemptEvent

    /** The window closed. [failed] means the native runtime broke rather than the user closing it. */
    data class Closed(val failed: Boolean) : AttemptEvent

    /** A newer attempt for the same provider and scope, or a profile clear, ended this one. */
    data object Replaced : AttemptEvent
}

/** The Kotlin-side state of one window. It is only written by the native callbacks and by the login coroutine. */
private class CookieOnlyAttempt(val generation: Long) : NativeCookieBrowserSink {
    val events = CompletableDeferred<AttemptEvent>()

    private val lock = Any()
    private val requests = HashMap<Long, CompletableDeferred<Array<Array<String>>?>>()
    private val requestIds = AtomicLong()

    @Volatile
    var windowClosed = false
        private set

    fun supersede() {
        events.complete(AttemptEvent.Replaced)
    }

    fun nextRequestId(): Long = requestIds.incrementAndGet()

    /** Records one cookie request. A window that already closed answers at once with no rows, never by a timeout. */
    fun register(id: Long, pending: CompletableDeferred<Array<Array<String>>?>) {
        val closed = synchronized(lock) {
            if (!windowClosed) requests[id] = pending
            windowClosed
        }
        if (closed) pending.complete(null)
    }

    fun forget(id: Long) {
        synchronized(lock) { requests.remove(id) }
    }

    override fun onFinish() {
        events.complete(AttemptEvent.Finished)
    }

    override fun onCookieRows(requestId: Long, rows: Array<Array<String>>?) {
        val pending = synchronized(lock) { requests.remove(requestId) } ?: return
        pending.complete(boundedRows(rows))
    }

    override fun onWindowClosed(failed: Boolean) {
        val pending = synchronized(lock) {
            windowClosed = true
            requests.values.toList().also { requests.clear() }
        }
        events.complete(AttemptEvent.Closed(failed))
        pending.forEach { it.complete(null) }
    }
}

private fun refused(): CookieOnlyLoginOutcome = CookieOnlyLoginOutcome.Failed(CookieOnlyLoginFailure.NAVIGATION_REFUSED)

/** The canonical "https://host:port" the native side compares by exact match, or null outside the plain host form. */
private fun AuthorizedHttpsOrigin.canonicalOrNull(): String? {
    val lowered = host.lowercase()
    return if (PLAIN_HOST.matches(lowered)) "https://$lowered:$port" else null
}

/*
 * The start page must be https and inside an authorized origin. Anything else is refused before a window opens, so no
 * navigation the policy would later block can ever begin.
 */
private fun startUriFor(startUrl: String, origins: Set<String>): URI? {
    val uri = try {
        URI(startUrl)
    } catch (_: URISyntaxException) {
        return null
    }
    val host = uri.host?.lowercase() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null || !PLAIN_HOST.matches(host)) return null
    val port = if (uri.port == -1) 443 else uri.port
    return uri.takeIf { "https://$host:$port" in origins }
}

private fun openWindow(startUrl: String, origins: List<String>, attempt: CookieOnlyAttempt): Long =
    try {
        NativeCookieBrowser.ensureLoaded()
        NativeCookieBrowser.open(
            startUrl,
            origins.toTypedArray(),
            DesktopStrings["browser_title"],
            DesktopStrings["browser_finish"],
            NativeCookieBrowserListener(attempt),
        )
    } catch (_: LinkageError) {
        0L
    }

/*
 * Reads each authorized origin in turn. A window closed mid-read is a cancellation, and any failed or malformed row
 * fails the whole context: a partial cookie set could look like a finished login.
 */
private suspend fun readContext(
    handle: Long,
    request: CookieOnlyLoginRequest,
    origins: List<String>,
    attempt: CookieOnlyAttempt,
): CookieOnlyLoginOutcome {
    val cookies = LinkedHashMap<Triple<String, String, String>, PrivateCookie>()
    for (origin in origins) {
        val rows = requestRows(handle, origin, attempt)
        if (attempt.windowClosed) return CookieOnlyLoginOutcome.Cancelled
        if (rows == null) return CookieOnlyLoginOutcome.Failed(CookieOnlyLoginFailure.CONTEXT_UNAVAILABLE)
        for (row in rows) {
            val cookie = cookieFromRow(row)
                ?: return CookieOnlyLoginOutcome.Failed(CookieOnlyLoginFailure.CONTEXT_UNAVAILABLE)
            cookies.putIfAbsent(Triple(cookie.name, cookie.domain, cookie.path), cookie)
        }
    }
    return CookieOnlyLoginOutcome.Completed(
        PrivateReusableContext(
            provider = request.provider,
            scope = request.scope,
            generation = request.generation,
            authorizedOrigins = request.authorizedOrigins,
            cookies = cookies.values.toList(),
        ),
    )
}

private suspend fun requestRows(handle: Long, origin: String, attempt: CookieOnlyAttempt): Array<Array<String>>? {
    val id = attempt.nextRequestId()
    val pending = CompletableDeferred<Array<Array<String>>?>()
    attempt.register(id, pending)
    NativeCookieBrowser.requestCookies(handle, id, origin)
    return try {
        withTimeoutOrNull(COOKIE_TIMEOUT_MILLIS) { pending.await() }
    } finally {
        attempt.forget(id)
    }
}

/** Maps one native row (name, value, domain, path, expiry, secure, httpOnly, sameSite) or null when it is malformed. */
private fun cookieFromRow(row: Array<String>): PrivateCookie? {
    val expiry = row[4]
    val expiresAt = if (expiry == "-1") null else expiry.toLongOrNull() ?: return null
    return try {
        PrivateCookie(
            name = row[0],
            value = PlatformSecret(row[1]),
            domain = row[2],
            path = row[3],
            expiresAtUnixMillis = expiresAt,
            secure = row[5] == "true",
            httpOnly = row[6] == "true",
            sameSite = row[7].takeIf { it.isNotEmpty() },
        )
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** Rejects a result that exceeds the native bounds or has the wrong shape, so Java never trusts an oversized read. */
private fun boundedRows(rows: Array<Array<String>>?): Array<Array<String>>? {
    if (rows == null || rows.size > MAX_COOKIE_ROWS) return null
    var bytes = 0L
    for (row in rows) {
        if (row.size != COOKIE_FIELDS) return null
        for (field in row) bytes += field.length
    }
    return rows.takeIf { bytes <= MAX_COOKIE_BYTES }
}
