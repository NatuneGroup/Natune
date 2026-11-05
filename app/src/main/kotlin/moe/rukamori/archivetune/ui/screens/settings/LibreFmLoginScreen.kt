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
import android.widget.Toast
import androidx.compose.runtime.Composable
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
import moe.rukamori.archivetune.constants.LastFmProvider
import moe.rukamori.archivetune.constants.LastFMCustomEndpointKey
import moe.rukamori.archivetune.constants.LastFMProviderKey
import moe.rukamori.archivetune.constants.LastFMSessionKey
import moe.rukamori.archivetune.constants.LastFMUsernameKey
import moe.rukamori.archivetune.lastfm.LastFM
import moe.rukamori.archivetune.lastfm.LastFmAppCredentials
import moe.rukamori.archivetune.lastfm.models.Authentication
import moe.rukamori.archivetune.scrobbling.lastFmSignInKeys
import moe.rukamori.archivetune.ui.component.AuthSignInWebViewClient
import moe.rukamori.archivetune.ui.component.AuthWebViewScreen
import moe.rukamori.archivetune.ui.component.createAuthWebView
import moe.rukamori.archivetune.utils.WebAuthCookieScopes
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.resetAuthWebViewSession
import java.util.concurrent.atomic.AtomicBoolean

const val LASTFM_LIBREFM_LOGIN_ROUTE = "settings/lastfm/librefm-login"

/**
 * WebView-based Libre.fm sign-in. Identical flow to [LastFmLoginScreen] but pointing at
 * libre.fm — Libre.fm is API-compatible with Last.fm, so the same `auth.getSession` token exchange
 * works against `https://libre.fm/2.0/`. The key pair is the one Libre.fm scrobbling uses: the user's
 * own Libre.fm key, else the compatibility pair, so the session matches later signed calls.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LibreFmLoginScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val handled = remember { AtomicBoolean(false) }

    // Null only while the pair is being read; Libre.fm always resolves to a non-blank pair.
    val keys by produceState<Pair<String, String>?>(initialValue = null) {
        value = lastFmSignInKeys(context, LastFmProvider.LIBREFM)
    }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun finishLogin(auth: Authentication) {
        scope.launch {
            // Configure the runtime to talk to libre.fm with the same key pair the session is created
            // under, so later scrobble / now-playing writes are signed consistently.
            val (apiKey, secret) = lastFmSignInKeys(context, LastFmProvider.LIBREFM)
            LastFM.configure(
                endpoint = LastFM.LIBREFM_API_ENDPOINT,
                apiKey = apiKey,
                secret = secret,
                sessionKey = auth.session.key,
            )
            context.dataStore.edit { prefs ->
                prefs[LastFMProviderKey] = LastFmProvider.LIBREFM.name
                prefs[LastFMCustomEndpointKey] = ""
                // The Libre.fm and Custom key slots hold only what the user entered: the build's pair is
                // never copied into them, and no slot is cleared, so a custom setup survives sign-in.
                prefs[LastFMUsernameKey] = auth.session.name
                prefs[LastFMSessionKey] = auth.session.key
            }
            withContext(Dispatchers.Main) {
                toast(context.getString(R.string.lastfm_login_success))
                navController.navigateUp()
            }
        }
    }

    fun handleRedirect(url: String?): Boolean {
        if (url == null || !url.startsWith(LastFmAppCredentials.AUTH_CALLBACK_URI)) return false
        if (!handled.compareAndSet(false, true)) return true
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val token = uri?.getQueryParameter("token")?.trim()
        if (token.isNullOrBlank()) {
            android.util.Log.w("LibreFmLogin", "Auth callback without token: $url")
            scope.launch {
                withContext(Dispatchers.Main) {
                    toast(context.getString(R.string.lastfm_login_cancelled))
                    navController.navigateUp()
                }
            }
            return true
        }
        scope.launch {
            val (apiKey, secret) = lastFmSignInKeys(context, LastFmProvider.LIBREFM)
            val result = withContext(Dispatchers.IO) {
                // Configure against libre.fm BEFORE calling getSession — the
                // auth.getSession signature uses the api_secret, so we need
                // the runtime endpoint + secret set to the libre.fm pair for
                // the call to sign + route correctly.
                LastFM.configure(
                    endpoint = LastFM.LIBREFM_API_ENDPOINT,
                    apiKey = apiKey,
                    secret = secret,
                    sessionKey = null,
                )
                LastFM.getSession(token)
            }
            result
                .onSuccess { auth -> finishLogin(auth) }
                .onFailure { error ->
                    android.util.Log.e("LibreFmLogin", "auth.getSession failed", error)
                    handled.set(false)
                    withContext(Dispatchers.Main) {
                        toast(context.getString(R.string.lastfm_login_failed))
                        navController.navigateUp()
                    }
                }
        }
        return true
    }

    // The sign-in page is only built once the key pair has been read.
    val signInPair = keys ?: return

    AuthWebViewScreen(
        navController = navController,
        provider = "librefm",
        title = stringResource(R.string.lastfm_connect_librefm_button),
        subtitle = stringResource(R.string.auth_webview_librefm_subtitle),
        factory = { ctx ->
            createAuthWebView(ctx).apply {
                webViewClient = object : AuthSignInWebViewClient("librefm", setOf("libre.fm")) {
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
                // Libre.fm's own cookies only; the default profile is shared with every other
                // provider's sign-in, YouTube Music's included.
                resetAuthWebViewSession(this, WebAuthCookieScopes.LIBRE_FM) {
                    // Libre.fm's auth URL — same shape as Last.fm's but
                    // pointing at libre.fm. Note the no-`www` host and the
                    // slightly different path (no trailing slash before `?`).
                    loadUrl(libreFmAuthUrl(signInPair.first))
                }
            }
        },
    )
}

private fun libreFmAuthUrl(apiKey: String): String =
    "https://libre.fm/api/auth/?api_key=$apiKey&cb=${LastFmAppCredentials.AUTH_CALLBACK_URI}"
