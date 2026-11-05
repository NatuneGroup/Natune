/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.utils

import android.webkit.CookieManager
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The cookie jar one sign-in WebView owns: the https [hosts] read for cookie names, and the extra
 * `Domain=` [domains] expired from each of them, so a cookie set on the registrable domain is
 * reached from a subdomain origin.
 *
 * Android exposes no per-host cookie removal, so expiry is by name over the cookies an origin can
 * see, which is how the YouTube-scoped playback logout has always worked. A cookie whose path is
 * not `/` is not visible to the root-path probe and is left behind; the YouTube logout accepts the
 * same bound, and an auth cookie is normally scoped to the whole domain.
 */
internal data class WebAuthCookieScope(
    val hosts: List<String>,
    val domains: List<String>,
)

/**
 * One scope per provider.
 *
 * Every sign-in WebView uses the default WebView profile, and no public API clears a single
 * origin's cookies or DOM storage on it, so a wipe that is not limited to the provider signing in
 * signs every other provider out — including the YouTube Music WebView, whose jar the login
 * recovery deliberately preserves. These scopes are the only thing a sign-in flow may expire;
 * process-wide cookie or storage wipes are a cross-provider logout and never run from one.
 */
internal object WebAuthCookieScopes {
    val YOUTUBE =
        WebAuthCookieScope(
            hosts = listOf("music.youtube.com", "www.youtube.com", "youtube.com"),
            domains = listOf(".youtube.com"),
        )

    val DEEZER =
        WebAuthCookieScope(
            hosts = listOf("www.deezer.com", "deezer.com"),
            domains = listOf(".deezer.com"),
        )

    val TIDAL =
        WebAuthCookieScope(
            hosts = listOf("login.tidal.com", "auth.tidal.com", "listen.tidal.com", "tidal.com"),
            domains = listOf(".tidal.com"),
        )

    val QOBUZ =
        WebAuthCookieScope(
            hosts = listOf("play.qobuz.com", "www.qobuz.com", "qobuz.com"),
            domains = listOf(".qobuz.com"),
        )

    /** Apple ID signs in on `idmsa.apple.com`, the store token lands on `music.apple.com`. */
    val APPLE_MUSIC =
        WebAuthCookieScope(
            hosts = listOf("music.apple.com", "idmsa.apple.com", "appleid.apple.com", "www.apple.com", "apple.com"),
            domains = listOf(".apple.com"),
        )

    val LAST_FM =
        WebAuthCookieScope(
            hosts = listOf("www.last.fm", "last.fm"),
            domains = listOf(".last.fm"),
        )

    /** Libre.fm's auth page is served from the apex host, not `www`. */
    val LIBRE_FM =
        WebAuthCookieScope(
            hosts = listOf("libre.fm", "www.libre.fm"),
            domains = listOf(".libre.fm"),
        )

    /** The catalog account's WebView: `sp_dc` is visible on both `open.` and `accounts.spotify.com`. */
    val SPOTIFY =
        WebAuthCookieScope(
            hosts = listOf("open.spotify.com", "accounts.spotify.com", "spotify.com"),
            domains = listOf(".spotify.com"),
        )
}

/**
 * Expires only the cookies [scope] owns, leaving every other origin's jar untouched.
 *
 * Kept free of WebView types so the expiry rules can be exercised on the JVM; the caller supplies
 * the real [CookieManager] accessors.
 */
internal fun expireWebAuthCookies(
    scope: WebAuthCookieScope,
    getCookie: (String) -> String?,
    setCookie: (String, String) -> Unit,
    flush: () -> Unit,
) {
    scope.hosts.forEach { host ->
        val origin = "https://$host"
        getCookie(origin)?.split(';')?.forEach { part ->
            val name = part.substringBefore('=').trim()
            if (name.isNotBlank()) {
                val expired = "$name=; Max-Age=0; Path=/; Secure"
                setCookie(origin, expired)
                setCookie(origin, "$expired; Domain=$host")
                scope.domains.forEach { domain ->
                    setCookie(origin, "$expired; Domain=$domain")
                }
            }
        }
    }
    flush()
}

/** The YouTube-scoped logout: playback sign-out expires YouTube's cookies and nothing else. */
fun clearPlaybackWebAuthSession() {
    // Android exposes no per-domain remove API. Expire only cookie names visible to official
    // YouTube origins, at host/domain scopes; do not touch Google or other providers' storage.
    val manager = CookieManager.getInstance()
    expireYouTubeWebCookies(manager::getCookie, { origin, cookie -> manager.setCookie(origin, cookie) }, manager::flush)
}

internal fun expireYouTubeWebCookies(
    getCookie: (String) -> String?,
    setCookie: (String, String) -> Unit,
    flush: () -> Unit,
) = expireWebAuthCookies(WebAuthCookieScopes.YOUTUBE, getCookie, setCookie, flush)

/**
 * The Spotify catalog sign-out: only Spotify's own cookies are expired.
 *
 * The default WebView profile's DOM storage cannot be cleared for one origin, so it is left alone
 * on purpose. A global `WebStorage.deleteAllData()` here would also expire the YouTube Music
 * WebView session, whose jar sign-in recovery keeps.
 */
suspend fun clearSpotifyCatalogWebAuthSession() {
    withContext(Dispatchers.Main.immediate) {
        val cookieManager = CookieManager.getInstance()
        expireWebAuthCookies(
            WebAuthCookieScopes.SPOTIFY,
            cookieManager::getCookie,
            { origin, cookie -> cookieManager.setCookie(origin, cookie) },
            cookieManager::flush,
        )
    }
}

/**
 * Prepares a sign-in WebView for a fresh attempt.
 *
 * [scope] names the provider whose cookies this flow may expire; a flow that has to reuse the
 * signed-in session passes null and keeps the jar. Nothing here runs process-wide: the sheet and the
 * YouTube Music login share one default profile, so a global cookie or DOM-storage wipe would be a
 * logout for every other provider.
 */
fun resetAuthWebViewSession(
    webView: WebView,
    scope: WebAuthCookieScope?,
    onReady: () -> Unit,
) {
    webView.stopLoading()
    webView.clearHistory()
    webView.clearFormData()
    // No clearCache(): the resource cache holds no credential the sign-in can reuse — cookies do,
    // and they are still expired below. It is also per-application, so wiping it here throws away
    // every provider's scripts and images and makes the next open of a bundle-heavy sign-in page
    // (Apple's login document alone is ~1.8 MB) a full cold download.
    val cookieManager = CookieManager.getInstance()
    cookieManager.setAcceptCookie(true)
    cookieManager.setAcceptThirdPartyCookies(webView, true)
    if (scope != null) {
        expireWebAuthCookies(
            scope,
            cookieManager::getCookie,
            { origin, cookie -> cookieManager.setCookie(origin, cookie) },
            cookieManager::flush,
        )
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)
    }
    onReady()
}

/**
 * Tears down a sign-in WebView whose sheet has left the composition.
 *
 * A leaked WebView keeps its renderer, its connection pool and any in-flight navigation alive for
 * the rest of the process, so every visit to a login screen would leave one more of them competing
 * with the next. [beforeDestroy] runs first so a client can cancel what it posted to the view, then
 * the bridge is detached, the load stopped and the WebView destroyed.
 */
fun WebView.releaseAuthWebView(
    javascriptInterface: String? = null,
    beforeDestroy: ((WebView) -> Unit)? = null,
) {
    beforeDestroy?.invoke(this)
    if (javascriptInterface != null) {
        removeJavascriptInterface(javascriptInterface)
    }
    stopLoading()
    destroy()
}
