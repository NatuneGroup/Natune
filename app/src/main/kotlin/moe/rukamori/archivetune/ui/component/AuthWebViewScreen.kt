/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Shared chrome for the provider sign-in WebViews (YouTube, Tidal, Qobuz, Deezer).
 * This is a deliberate 1:1 match of SpotifyLoginSheet in BackupAndRestore.kt: a full-height
 * ModalBottomSheet with 28dp top corners over colorScheme.surface, a bold titleLarge heading,
 * an onSurfaceVariant bodyMedium subtitle, and the WebView clipped into shapes.large — same
 * 20dp horizontal / 20dp bottom padding and same 12dp vertical spacing. Every sign-in surface
 * in the app now looks identical to the Spotify one.
 * These four flows are navigation routes rather than a boolean-gated sheet, so dismissing
 * (swipe-down, scrim tap, or system back) maps to navigateUp() instead of clearing a flag.
 * Like Spotify's sheet there is no back arrow — the sheet itself is the affordance.
 * It also removes three copy-pasted bugs those four screens shared:
 * - the TopAppBar was a *sibling* of a fillMaxSize() AndroidView, so it painted on top of the page
 * instead of above it, covering the first ~64dp of every login form;
 * - the captured WebView lived in a plain `var`, which resets to null on recomposition (the
 * AndroidView factory only runs once) and never triggers one, leaving BackHandler permanently
 * disabled. DeezerLoginScreen documented this bug rather than fixing it;
 * - BackHandler keyed `enabled` off canGoBack(), which flips during in-page navigation without
 * recomposing, so the flag went stale either way.
 */



package moe.rukamori.archivetune.ui.component

import android.content.Context
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import moe.rukamori.archivetune.shared.ui.component.AuthWebViewLayout

/**
 * Renders [factory]'s WebView inside the shared Spotify-style sign-in sheet. [provider] tags the
 * diagnostic lines the shared popup chrome and [factory]'s client emit. The WebView sits in a
 * [FrameLayout] host so a gesture-opened popup can stack on top of it without covering the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthWebViewScreen(
    navController: NavController,
    provider: String,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onRelease: ((WebView) -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    navigateBackInWebView: Boolean = true,
    statusContent: (@Composable () -> Unit)? = null,
    factory: (Context) -> WebView,
) {
    // The sheet's own view plus the top-most window: a gesture-opened popup when one is stacked.
    var webView by remember { mutableStateOf<WebView?>(null) }
    var activeWebView by remember { mutableStateOf<WebView?>(null) }
    var popupClient by remember { mutableStateOf<AuthPopupWebChromeClient?>(null) }

    AuthWebViewSheet(
        title = title,
        subtitle = subtitle,
        modifier = modifier,
        onDismiss = { onDismiss?.invoke() ?: run { navController.navigateUp() } },
        statusContent = statusContent,
    ) {
        AndroidView(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(MaterialTheme.shapes.large),
            factory = { ctx ->
                val host = FrameLayout(ctx)
                val main = factory(ctx)
                // Gesture popups were silently dying here: with multiple windows off, window.open
                // returned null to the page and a "Sign in with …" substitute had nowhere to go.
                main.settings.setSupportMultipleWindows(true)
                val chrome =
                    AuthPopupWebChromeClient(
                        container = host,
                        parentWebView = main,
                        provider = provider,
                        onActiveWebViewChanged = { active -> activeWebView = active },
                    )
                main.webChromeClient = chrome
                host.addView(
                    main,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                popupClient = chrome
                webView = main
                activeWebView = main
                host
            },
            onRelease = {
                popupClient?.closePopups()
                webView?.let { released -> onRelease?.invoke(released) }
                webView = null
                activeWebView = null
                popupClient = null
            },
        )
    }

    // Enabled purely on presence: canGoBack() is polled at press time because it changes during
    // in-page navigation without recomposing. A stacked popup closes first instead of the sheet.
    BackHandler(enabled = webView != null) {
        val active = activeWebView
        val main = webView
        when {
            active != null && main != null && active !== main -> {
                if (active.canGoBack()) {
                    active.goBack()
                } else {
                    popupClient?.closePopups()
                }
            }

            navigateBackInWebView && main?.canGoBack() == true -> {
                main.goBack()
            }

            else -> {
                onDismiss?.invoke() ?: run { navController.navigateUp() }
            }
        }
    }
}

/** Shared by the routed sign-ins and both Spotify profiles, including their popup container. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AuthWebViewSheet(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    statusContent: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        modifier = modifier.fillMaxHeight(),
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = { WindowInsets.safeDrawing },
        // A WebView scroll is a page drag, not a sheet drag: letting the sheet take vertical
        // swipes both stole scrolling from the form and dismissed in-progress sign-ins.
        sheetGesturesEnabled = false,
    ) {
        AuthWebViewLayout(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            header = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                statusContent?.invoke()
            },
            content = content,
        )
    }
}
