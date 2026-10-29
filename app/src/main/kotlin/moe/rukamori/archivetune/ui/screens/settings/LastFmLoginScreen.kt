/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.ui.screens.settings

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.datastore.preferences.core.edit
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.LastFMCustomEndpointKey
import moe.rukamori.archivetune.constants.LastFmProvider
import moe.rukamori.archivetune.constants.LastFMProviderKey
import moe.rukamori.archivetune.constants.LastFMSessionKey
import moe.rukamori.archivetune.constants.LastFMUsernameKey
import moe.rukamori.archivetune.lastfm.LastFM
import moe.rukamori.archivetune.lastfm.LastFmAppCredentials
import moe.rukamori.archivetune.lastfm.models.Authentication
import moe.rukamori.archivetune.scrobbling.lastFmSignInKeys
import moe.rukamori.archivetune.ui.component.AuthWebViewScreen
import moe.rukamori.archivetune.ui.component.createAuthWebView
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.resetAuthWebViewSession
import java.util.concurrent.atomic.AtomicBoolean
import android.webkit.HttpAuthHandler
import moe.rukamori.archivetune.utils.AppProxy

const val LASTFM_LOGIN_ROUTE = "settings/lastfm/login"

/** WebView-based Last.fm sign-in. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LastFmLoginScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Guards against handling the redirect more than once (the WebView can fire
    // shouldOverrideUrlLoading multiple times for the same redirect).
    val handled = remember { AtomicBoolean(false) }

    // The key pair this sign-in uses, the same one later scrobbling uses. Null while it is read.
    val keys by produceState<Pair<String, String>?>(initialValue = null) {
        value = lastFmSignInKeys(context, LastFmProvider.LASTFM)
    }
    val configured = keys?.takeIf { (apiKey, secret) -> apiKey.isNotBlank() && secret.isNotBlank() }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    // Without a build-injected pair and no user-entered one, nothing is loaded: the screen says so and closes.
    LaunchedEffect(keys) {
        if (keys != null && configured == null) {
            toast(context.getString(R.string.lastfm_not_configured))
            navController.navigateUp()
        }
    }

    // Persists the session + configures LastFM at runtime, then closes the screen.
    fun finishLogin(auth: Authentication) {
        scope.launch {
            // Apply the same key pair the session was created with, so subsequent LastFM.* calls
            // (getUserInfo, scrobble, etc.) are signed with the right api_key + secret.
            val (apiKey, secret) = lastFmSignInKeys(context, LastFmProvider.LASTFM)
            LastFM.initialize(apiKey = apiKey, secret = secret)
            LastFM.sessionKey = auth.session.key

            // Persist in DataStore so the session survives app restarts. Also force the provider to
            // LASTFM and clear the custom endpoint: the web auth flow only works against the real
            // last.fm endpoint. The key and secret slots are left as the user set them; the build's
            // pair is read at sign-in and is never copied into them.
            context.dataStore.edit { prefs ->
                prefs[LastFMProviderKey] = LastFmProvider.LASTFM.name
                prefs[LastFMCustomEndpointKey] = ""
                prefs[LastFMUsernameKey] = auth.session.name
                prefs[LastFMSessionKey] = auth.session.key
            }
            withContext(Dispatchers.Main) {
                toast(context.getString(R.string.lastfm_login_success))
                navController.navigateUp()
            }
        }
    }

    // Handles the auth-callback redirect. Returns true if the URL was the redirect
    // and was consumed (so the WebView doesn't actually try to load the
    // archivetune:// scheme, which it can't).
    fun handleRedirect(url: String?): Boolean {
        if (url == null || !url.startsWith(LastFmAppCredentials.AUTH_CALLBACK_URI)) return false
        if (!handled.compareAndSet(false, true)) return true
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val token = uri?.getQueryParameter("token")?.trim()
        if (token.isNullOrBlank()) {
            // No token (user cancelled or Last.fm returned an error) → just close.
            android.util.Log.w("LastFmLogin", "Auth callback without token: $url")
            scope.launch {
                withContext(Dispatchers.Main) {
                    toast(context.getString(R.string.lastfm_login_cancelled))
                    navController.navigateUp()
                }
            }
            return true
        }
        scope.launch {
            val (apiKey, secret) = lastFmSignInKeys(context, LastFmProvider.LASTFM)
            val result =
                withContext(Dispatchers.IO) {
                    // Make sure the LastFM singleton is configured with the same key pair before
                    // calling getSession — the call signs the request with the api_secret.
                    LastFM.initialize(apiKey = apiKey, secret = secret)
                    LastFM.getSession(token)
                }
            result
                .onSuccess { auth -> finishLogin(auth) }
                .onFailure { error ->
                    android.util.Log.e("LastFmLogin", "auth.getSession failed", error)
                    handled.set(false) // Allow retry if the user navigates back
                    withContext(Dispatchers.Main) {
                        toast(context.getString(R.string.lastfm_login_failed))
                        navController.navigateUp()
                    }
                }
        }
        return true
    }

    // The sign-in page is only built once the key pair is known and non-blank.
    val signInPair = configured ?: return

    AuthWebViewScreen(
        navController = navController,
        title = stringResource(R.string.lastfm_login),
        subtitle = stringResource(R.string.auth_webview_lastfm_subtitle),
        factory = { ctx ->
            createAuthWebView(ctx).apply {
                webViewClient =
                    object : WebViewClient() {
                        override fun onReceivedHttpAuthRequest(
                            view: WebView?,
                            handler: HttpAuthHandler?,
                            host: String?,
                            realm: String?,
                        ) {
                            if (handler != null && AppProxy.handleWebViewProxyAuth(handler, host)) return
                            super.onReceivedHttpAuthRequest(view, handler, host, realm)
                        }

                        override fun onPageStarted(
                            view: WebView,
                            url: String?,
                            favicon: Bitmap?,
                        ) {
                            handleRedirect(url)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            url: String?,
                        ): Boolean = handleRedirect(url)
                    }
                resetAuthWebViewSession(ctx, this, clearCookies = true) {
                    loadUrl(LastFmAppCredentials.authUrl(apiKey = signInPair.first))
                }
            }
        },
    )
}
