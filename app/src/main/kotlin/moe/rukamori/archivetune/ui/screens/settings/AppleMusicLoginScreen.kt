/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.ui.screens.settings

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.datastore.preferences.core.edit
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.AppleMusicDevTokenKey
import moe.rukamori.archivetune.constants.AppleMusicMediaUserTokenKey
import moe.rukamori.archivetune.ui.component.AuthSignInWebViewClient
import moe.rukamori.archivetune.ui.component.AuthWebViewScreen
import moe.rukamori.archivetune.ui.component.createAuthWebView
import moe.rukamori.archivetune.utils.WebAuthCookieScopes
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.releaseAuthWebView
import moe.rukamori.archivetune.utils.resetAuthWebViewSession
import java.util.concurrent.atomic.AtomicBoolean

const val APPLE_MUSIC_LOGIN_ROUTE = "settings/applemusic/login"

private const val LOGIN_URL = "https://music.apple.com/login"

/**
 * Reads both Apple Music tokens out of the signed-in web player.
 *
 * A cookie probe cannot do this: MusicKit keeps the Music User Token out of the cookie jar, so the
 * token is never visible to a cookie scan. Both tokens are however readable from the live MusicKit
 * instance, and the user token is additionally mirrored into localStorage under a
 * `media-user-token` key, so the scan covers the case where the instance is not reachable on the
 * current page.
 *
 * Polls because MusicKit initialises asynchronously and the user token only appears after the
 * Apple ID flow completes — there is no event to hook. Gives up after ~2 minutes so a page that
 * never signs in does not poll for the lifetime of the screen.
 *
 * Evaluated through evaluateJavascript(): a "javascript:" URL is a document-load API, so the poll
 * would not start until the page had arrived, and it re-enters onPageFinished.
 */
private const val APPLE_HOOK_JS = """
(function () {
  if (window.__atAppleHook) return;
  window.__atAppleHook = true;
  var tries = 0;
  function readStoredUserToken() {
    try {
      for (var i = 0; i < localStorage.length; i++) {
        var k = localStorage.key(i);
        if (k && k.toLowerCase().indexOf('media-user-token') !== -1) {
          var v = localStorage.getItem(k);
          if (v && v.indexOf('0.') === 0) return v;
        }
      }
    } catch (e) {}
    return null;
  }
  var timer = setInterval(function () {
    tries++;
    if (tries > 240) {
      clearInterval(timer);
      try { AppleAuth.onGaveUp(); } catch (e) {}
      return;
    }
    var dev = null, usr = null;
    try {
      var mk = window.MusicKit && window.MusicKit.getInstance && window.MusicKit.getInstance();
      if (mk) { dev = mk.developerToken || null; usr = mk.musicUserToken || null; }
    } catch (e) {}
    if (!usr) usr = readStoredUserToken();
    if (usr) {
      clearInterval(timer);
      try { AppleAuth.onTokens(usr, dev); } catch (e) {}
    }
  }, 500);
})()
"""

/**
 * Runs on Apple's own origin before the login document is loaded a second time. [APPLE_HOOK_JS]
 * reads `media-user-token` from localStorage when MusicKit exposes no live instance, so a token
 * left by a previous account would be handed back after the cookie session expired.
 */
private const val MUSIC_STORAGE_CLEAR_JS =
    "try{localStorage.clear();sessionStorage.clear();}catch(e){}"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AppleMusicLoginScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The poll fires repeatedly; only the first delivery may save and navigate.
    val handled = remember { AtomicBoolean(false) }
    // MusicKit mirrors its Music User Token into localStorage, where it outlives the cookie session.
    // No public API clears one origin's DOM storage on the default WebView profile, and the global
    // wipe this flow used to rely on would sign the YouTube Music WebView out, so the token is
    // cleared on Apple's own origin once per attempt. Without it a disconnected account is silently
    // re-selected by the hook's localStorage fallback.
    val musicStorageCleared = remember { AtomicBoolean(false) }
    // The sheet can be dismissed while the clear is in flight; a reload of the destroyed view fails.
    val released = remember { AtomicBoolean(false) }

    AuthWebViewScreen(
        navController = navController,
        provider = "applemusic",
        title = stringResource(R.string.applemusic_login),
        subtitle = stringResource(R.string.applemusic_login_subtitle),
        // The injected poll is a live 500 ms timer, and it would otherwise keep running — with the
        // renderer, the MusicKit instance and the JavaScript interface behind it — for up to two
        // minutes after the user has closed the sheet. Releasing the WebView tears all of it down.
        onRelease = {
            released.set(true)
            it.releaseAuthWebView("AppleAuth")
        },
        factory = { ctx ->
            createAuthWebView(ctx).apply {
                webViewClient =
                    object : AuthSignInWebViewClient("applemusic", setOf("apple.com")) {
                        override fun onPageFinished(
                            view: WebView,
                            url: String?,
                        ) {
                            if (url?.contains("music.apple.com", ignoreCase = true) != true) return
                            if (musicStorageCleared.compareAndSet(false, true)) {
                                view.evaluateJavascript(MUSIC_STORAGE_CLEAR_JS) {
                                    if (!released.get()) view.loadUrl(LOGIN_URL)
                                }
                                return
                            }
                            view.evaluateJavascript(APPLE_HOOK_JS, null)
                        }
                    }
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onTokens(
                            userToken: String?,
                            devToken: String?,
                        ) {
                            // The user token is what gates sign-in; the developer token is a bonus
                            // when MusicKit was reachable, and the settings page can still scrape
                            // its own if this page could not supply one.
                            val user = userToken?.trim().orEmpty()
                            if (!user.startsWith("0.")) return
                            if (!handled.compareAndSet(false, true)) return
                            val dev = devToken?.trim().orEmpty()
                            scope.launch {
                                context.dataStore.edit { prefs ->
                                    prefs[AppleMusicMediaUserTokenKey] = user
                                    if (dev.isNotBlank()) prefs[AppleMusicDevTokenKey] = dev
                                }
                                Toast
                                    .makeText(context, R.string.applemusic_login_success, Toast.LENGTH_SHORT)
                                    .show()
                                navController.navigateUp()
                            }
                        }

                        @JavascriptInterface
                        fun onGaveUp() {
                            // The poll has no event to hook, so a page that never signs in simply
                            // stops. Say so, or the screen looks like it is still working.
                            if (handled.get()) return
                            scope.launch {
                                Toast
                                    .makeText(context, R.string.applemusic_login_timeout, Toast.LENGTH_LONG)
                                    .show()
                            }
                        }
                    },
                    "AppleAuth",
                )
                // An Apple ID attempt must not inherit an earlier `.apple.com` session, but it also
                // must not expire anything outside Apple's own jar. Apple's DOM storage is cleared
                // by the page callback above, which reloads this URL once the clear has run.
                resetAuthWebViewSession(this, WebAuthCookieScopes.APPLE_MUSIC) {
                    loadUrl(LOGIN_URL)
                }
            }
        },
    )
}
