/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.screens.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.spotify.SpotifyAccountUiState
import moe.rukamori.archivetune.spotify.SpotifyAuth
import moe.rukamori.archivetune.ui.component.AuthPopupWebChromeClient
import moe.rukamori.archivetune.ui.component.AuthSignInWebViewClient
import moe.rukamori.archivetune.ui.component.AuthWebViewSheet
import moe.rukamori.archivetune.ui.component.authPopupHandoffAllowed
import moe.rukamori.archivetune.ui.component.authPopupsEnabled
import moe.rukamori.archivetune.ui.component.hostInLoginFlow
import moe.rukamori.archivetune.ui.component.sanitizeAuthWebViewUserAgent
import moe.rukamori.archivetune.ui.component.DefaultDialog
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroupScope
import moe.rukamori.archivetune.ui.component.SwitchPreference
import moe.rukamori.archivetune.ui.component.createAuthWebView
import moe.rukamori.archivetune.utils.WebAuthCookieScopes
import moe.rukamori.archivetune.utils.resetAuthWebViewSession

private val SpotifyAccountIconSize = 44.dp

// The hosts a sign-in popup belongs to: social-login pages get their own window, but anything
// landing back on Spotify continues in the parent instead of stacking a second WebView over it.
private val SPOTIFY_FLOW_HOSTS = setOf("spotify.com")

/** Renders the Spotify account / playlist preferences inside a [PreferenceGroupScope]. */
internal fun PreferenceGroupScope.spotifyAccountPreferences(
    state: SpotifyAccountUiState,
    showPlaylists: Boolean,
    syncHistory: Boolean,
    onConnectClick: () -> Unit,
    onShowPlaylistsChange: (Boolean) -> Unit,
    onSyncHistoryChange: (Boolean) -> Unit,
    onReloadClick: () -> Unit,
    onLogoutClick: () -> Unit,
) {
    if (!state.isAuthenticated) {
        item {
            PreferenceEntry(
                title = { Text(stringResource(R.string.spotify_connect)) },
                description = stringResource(R.string.spotify_not_connected),
                icon = { Icon(painterResource(R.drawable.spotify_icon), null) },
                trailingContent = {
                    AnimatedVisibility(visible = state.isLoading) {
                        CircularWavyProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                onClick = onConnectClick,
                isEnabled = !state.isLoading,
            )
        }
        return
    }

    item {
        PreferenceEntry(
            title = {
                Text(
                    text =
                        if (state.accountName.isNotBlank()) {
                            stringResource(R.string.spotify_connected_as, state.accountName)
                        } else {
                            stringResource(R.string.spotify_account)
                        },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            description =
                when {
                    state.isLoading -> stringResource(R.string.spotify_loading_library)
                    state.playlistCount > 0 -> stringResource(R.string.spotify_available_count, state.playlistCount)
                    else -> stringResource(R.string.spotify_no_sources)
                },
            icon = { SpotifyAccountIcon(avatarUrl = state.accountAvatarUrl) },
            trailingContent = {
                AnimatedVisibility(visible = state.isLoading) {
                    CircularWavyProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            isEnabled = false,
        )
    }

    item {
        SwitchPreference(
            title = { Text(stringResource(R.string.spotify_show_playlist)) },
            description = stringResource(R.string.spotify_show_playlist_desc),
            icon = { Icon(painterResource(R.drawable.spotify_icon), null) },
            checked = showPlaylists,
            onCheckedChange = onShowPlaylistsChange,
            isEnabled = !state.isLoading,
        )
    }

    item {
        SwitchPreference(
            title = { Text(stringResource(R.string.spotify_sync_history)) },
            description = stringResource(R.string.spotify_sync_history_desc),
            icon = { Icon(painterResource(R.drawable.spotify_icon), null) },
            checked = syncHistory,
            onCheckedChange = onSyncHistoryChange,
            isEnabled = !state.isLoading,
        )
    }

    item {
        PreferenceEntry(
            title = { Text(stringResource(R.string.spotify_reload_playlist)) },
            description = stringResource(R.string.spotify_reload_playlist_desc),
            icon = { Icon(painterResource(R.drawable.sync), null) },
            onClick = onReloadClick,
            isEnabled = !state.isLoading,
        )
    }

    item {
        PreferenceEntry(
            title = { Text(stringResource(R.string.action_logout)) },
            icon = { Icon(painterResource(R.drawable.logout), null) },
            onClick = onLogoutClick,
            isEnabled = !state.isLoading,
        )
    }
}

@Composable
private fun SpotifyAccountIcon(avatarUrl: String?) {
    val context = LocalContext.current
    val requestSize = with(LocalDensity.current) { SpotifyAccountIconSize.roundToPx() }
    val accountIcon = painterResource(R.drawable.spotify_icon)
    val imageRequest =
        remember(context, avatarUrl, requestSize) {
            avatarUrl
                ?.takeIf(String::isNotBlank)
                ?.let {
                    ImageRequest
                        .Builder(context)
                        .data(it)
                        .size(requestSize)
                        .build()
                }
        }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (imageRequest != null) {
            AsyncImage(
                model = imageRequest,
                contentDescription = null,
                placeholder = accountIcon,
                error = accountIcon,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                painter = accountIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun SpotifyLoginSheet(
    onDismiss: () -> Unit,
    onCookiesCaptured: (spDc: String, spKey: String) -> Unit,
    isolatedProfile: String? = null,
    title: String? = null,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var mainWebView by remember { mutableStateOf<WebView?>(null) }
    var captured by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            webView?.destroySpotifyLoginWebView()
            mainWebView?.takeIf { it !== webView }?.destroySpotifyLoginWebView()
            webView = null
            mainWebView = null
        }
    }

    BackHandler(enabled = webView != null) {
        val activeWebView = webView
        val rootWebView = mainWebView
        when {
            activeWebView?.canGoBack() == true -> {
                activeWebView.goBack()
            }

            activeWebView != null && rootWebView != null && activeWebView !== rootWebView -> {
                activeWebView.destroySpotifyLoginWebView()
                webView = rootWebView
            }

            else -> {
                onDismiss()
            }
        }
    }

    AuthWebViewSheet(
        title = title ?: stringResource(R.string.spotify_login_title),
        subtitle = stringResource(R.string.spotify_waiting_for_login),
        onDismiss = onDismiss,
    ) {
        AndroidView(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(MaterialTheme.shapes.large),
            factory = { context ->
                val container = FrameLayout(context)
                var selectedProfileCookies: CookieManager? = null
                val spotifyWebView =
                    createAuthWebView(
                        context,
                        beforeConfigure = { loginView ->
                            selectedProfileCookies = isolatedProfile?.let { useIsolatedSpotifyProfile(loginView, it) }
                        },
                    ).apply {
                        val profileCookieManager = selectedProfileCookies
                        // A second account must never meet the first one's cookies, and its own
                        // cookies must not be left behind for the next sign-in to pick up. Only a
                        // profile's own jar may be swept: without MULTI_PROFILE the WebView falls
                        // back to the shared default profile, and removing all of its cookies would
                        // sign every other provider out.
                        val forgetAfterCapture = profileCookieManager != null
                        val cookieManager = profileCookieManager ?: CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)
                        configureSpotifyLoginWebView()

                        fun captureCookies(url: String?): Boolean {
                            if (captured) return true
                            val cookies = readSpotifyCookies(cookieManager, url)
                            val spDc = cookies["sp_dc"].orEmpty()
                            if (spDc.isBlank()) return false
                            captured = true
                            if (forgetAfterCapture) {
                                cookieManager.removeAllCookies { cookieManager.flush() }
                            } else {
                                cookieManager.flush()
                            }
                            onCookiesCaptured(spDc, cookies["sp_key"].orEmpty())
                            return true
                        }

                        webViewClient =
                            object : AuthSignInWebViewClient("spotify", SPOTIFY_FLOW_HOSTS) {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): Boolean =
                                    shouldOverrideSpotifyLoginUrl(
                                        view = view,
                                        url = request.url?.toString(),
                                        captureCookies = { url -> captureCookies(url) },
                                    )

                                @Deprecated("Deprecated in Java")
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    url: String?,
                                ): Boolean =
                                    shouldOverrideSpotifyLoginUrl(
                                        view = view,
                                        url = url,
                                        captureCookies = { targetUrl -> captureCookies(targetUrl) },
                                    )

                                override fun onPageStarted(
                                    view: WebView,
                                    url: String?,
                                    favicon: android.graphics.Bitmap?,
                                ) {
                                    captureCookies(url)
                                }

                                override fun onPageFinished(
                                    view: WebView,
                                    url: String?,
                                ) {
                                    captureCookies(url)
                                }
                            }
                        val popupProfile = isolatedProfile.takeIf { profileCookieManager != null }
                        webChromeClient =
                            AuthPopupWebChromeClient(
                                container = container,
                                parentWebView = this,
                                provider = "spotify",
                                onActiveWebViewChanged = { activeWebView -> webView = activeWebView },
                                consoleLoggedHosts = SPOTIFY_FLOW_HOSTS,
                                createPopup = { parent ->
                                    createSpotifyLoginPopup(parent, popupProfile) { url -> captureCookies(url) }
                                },
                            )
                        webView = this
                        mainWebView = this
                        if (profileCookieManager != null) {
                            // The profile has its own jar and storage; the default profile, and
                            // with it every other provider's sign-in, is left untouched.
                            resetAuthWebViewSession(this, scope = null) {
                                profileCookieManager.removeAllCookies {
                                    loadUrl(SpotifyAuth.LOGIN_URL)
                                }
                            }
                        } else {
                            // No profile support: the shared jar is the only one there is, so expire
                            // Spotify's own cookies instead of removing every provider's.
                            resetAuthWebViewSession(this, WebAuthCookieScopes.SPOTIFY) {
                                loadUrl(SpotifyAuth.LOGIN_URL)
                            }
                        }
                    }
                container.addView(
                    spotifyWebView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                container
            },
            update = {
                webView = webView ?: mainWebView
            },
        )
    }
}

/**
 * Moves [webView] into its own WebView profile, with a cookie jar and storage separate from the
 * default profile's, and returns that profile's cookie manager. Null when the installed WebView
 * has no multi-profile support; callers then fall back to wiping the shared jar, which is what the
 * catalog sign-in does on every open anyway. Must run before the WebView loads anything.
 */
private fun useIsolatedSpotifyProfile(
    webView: WebView,
    profileName: String,
): CookieManager? {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return null
    return runCatching {
        val profile = ProfileStore.getInstance().getOrCreateProfile(profileName)
        WebViewCompat.setProfile(webView, profileName)
        profile.webStorage.deleteAllData()
        profile.cookieManager
    }.getOrNull()
}

private fun WebView.destroySpotifyLoginWebView() {
    stopLoading()
    loadUrl("about:blank")
    (parent as? ViewGroup)?.removeView(this)
    destroy()
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configureSpotifyLoginWebView() {
    settings.apply {
        setSupportMultipleWindows(authPopupsEnabled("spotify"))
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // The engine's own UA minus the WebView-only tokens — the frozen Chrome/131 string this
        // replaced contradicted the runtime Sec-CH-UA hints, which is exactly the kind of
        // automation tell Spotify's bot check answers with "Something went wrong".
        userAgentString = sanitizeAuthWebViewUserAgent(WebSettings.getDefaultUserAgent(context))
    }
}

/**
 * Builds the gesture-opened popup for the shared chrome client: same isolated profile as the
 * parent when one is in use, same cookie acceptance, same sign-in client — plus the rule that a
 * Spotify-hosted navigation belongs to the parent window, not to a stacked popup.
 */
private fun AuthPopupWebChromeClient.createSpotifyLoginPopup(
    parent: WebView,
    profileName: String?,
    captureCookies: (String?) -> Boolean,
): WebView {
    var popupProfileCookies: CookieManager? = null
    return createAuthWebView(
        parent.context,
        beforeConfigure = { popup ->
            popupProfileCookies = profileName?.let { useIsolatedSpotifyProfile(popup, it) }
        },
    ).apply {
        // A popup outside the parent's profile would sign in against the shared jar.
        val cookieManager = popupProfileCookies ?: CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(this, true)
        configureSpotifyLoginWebView()
        webViewClient =
            object : AuthSignInWebViewClient("spotify", SPOTIFY_FLOW_HOSTS) {
                // Only a main-frame GET whose method is on the request may be replayed into the
                // parent: a form_post OAuth return reissued as loadUrl would drop code/state.
                private fun movesToParent(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean =
                    authPopupHandoffAllowed(request.isForMainFrame, request.method) &&
                        hostInLoginFlow(request.url?.host, SPOTIFY_FLOW_HOSTS) &&
                        moveToParent(view, request.url?.toString())

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean =
                    movesToParent(view, request) ||
                        shouldOverrideSpotifyLoginUrl(
                            view = view,
                            url = request.url?.toString(),
                            captureCookies = captureCookies,
                        )

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    url: String?,
                ): Boolean =
                    shouldOverrideSpotifyLoginUrl(
                        view = view,
                        url = url,
                        captureCookies = captureCookies,
                    )

                override fun onPageStarted(
                    view: WebView,
                    url: String?,
                    favicon: android.graphics.Bitmap?,
                ) {
                    // No method info here, so no handoff: the popup shares the session jar and the
                    // capture, so a Spotify-hosted load completes in place either way.
                    captureCookies(url)
                }

                override fun onPageFinished(
                    view: WebView,
                    url: String?,
                ) {
                    captureCookies(url)
                }
            }
    }
}

private fun shouldOverrideSpotifyLoginUrl(
    view: WebView,
    url: String?,
    captureCookies: (String?) -> Boolean,
): Boolean {
    if (captureCookies(url)) return true

    val targetUrl = url?.takeIf(String::isNotBlank) ?: return false
    if (targetUrl.isWebViewLoadableUrl()) return false

    targetUrl.intentBrowserFallbackUrl()?.let { fallbackUrl -> view.loadUrl(fallbackUrl) }
    return true
}

private fun String.isWebViewLoadableUrl(): Boolean {
    val scheme = runCatching { Uri.parse(this).scheme?.lowercase() }.getOrNull()
    return scheme == "http" ||
        scheme == "https" ||
        scheme == "javascript" ||
        scheme == "data" ||
        scheme == "blob"
}

private fun String.intentBrowserFallbackUrl(): String? =
    runCatching { Intent.parseUri(this, Intent.URI_INTENT_SCHEME) }
        .getOrNull()
        ?.getStringExtra("browser_fallback_url")
        ?.takeIf { it.isWebViewLoadableUrl() }

private fun readSpotifyCookies(
    cookieManager: CookieManager,
    currentUrl: String?,
): Map<String, String> {
    val urls =
        linkedSetOf(
            "https://open.spotify.com",
            "https://accounts.spotify.com",
            "https://spotify.com",
        )
    currentUrl?.toSpotifyCookieOrigin()?.let(urls::add)
    val cookies = linkedMapOf<String, String>()
    // No flush() here. This is a probe: it runs from onPageStarted, onPageFinished and both
    // shouldOverrideUrlLoading overloads, i.e. several times per navigation, and flush() "will block
    // the caller until it is done and may perform I/O" — on the UI thread, inside the navigation
    // path. It also cannot help, because flush() only writes out cookies that getCookie already
    // returns. The one flush that matters is after a capture, below, so the session survives.
    urls.forEach { url ->
        cookieManager
            .getCookie(url)
            ?.split(";")
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.forEach { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) return@forEach
                val key = part.substring(0, separator).trim()
                val value = part.substring(separator + 1).trim()
                if (key.isNotBlank()) {
                    cookies[key] = value
                }
            }
    }
    return cookies
}

private fun String.toSpotifyCookieOrigin(): String? {
    val uri = runCatching { Uri.parse(this) }.getOrNull() ?: return null
    val host = uri.host?.lowercase() ?: return null
    if (host != "spotify.com" && !host.endsWith(".spotify.com")) return null
    val scheme =
        uri.scheme
            ?.takeIf { it.equals("https", ignoreCase = true) || it.equals("http", ignoreCase = true) }
            ?: "https"
    return "$scheme://$host"
}

@Composable
internal fun SpotifyErrorDialog(
    message: String,
    onDismiss: () -> Unit,
) {
    DefaultDialog(
        onDismiss = onDismiss,
        title = { Text(stringResource(R.string.import_failed)) },
        buttons = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(android.R.string.ok))
            }
        },
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
