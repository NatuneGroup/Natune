/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.lastfm

/** ArchiveTune's Last.fm API application credentials, injected at build time (docs/build-credentials.md). */
object LastFmAppCredentials {
    const val API_KEY = ProviderBuildCredentials.ARCHIVETUNE_LASTFM_API_KEY
    const val API_SECRET = ProviderBuildCredentials.ARCHIVETUNE_LASTFM_API_SECRET

    /** True when both values were injected at build time; without them Last.fm sign-in is not configured. */
    val isConfigured: Boolean get() = API_KEY.isNotBlank() && API_SECRET.isNotBlank()

    /** Custom-scheme callback URI registered with Last.fm. The WebView intercepts this. */
    const val AUTH_CALLBACK_URI = "archivetune://lastfm-auth-callback"

    /**
     * Builds the Last.fm web auth URL. The user approves the app in the browser / WebView, then
     * Last.fm redirects to [AUTH_CALLBACK_URI] with a `token` query parameter. [apiKey] is the
     * effective key: the built-in value, or a user-entered one when none was injected.
     */
    fun authUrl(apiKey: String = API_KEY): String =
        "https://www.last.fm/api/auth/?api_key=$apiKey&cb=$AUTH_CALLBACK_URI"
}
