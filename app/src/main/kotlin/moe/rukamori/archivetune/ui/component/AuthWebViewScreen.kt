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
import android.webkit.WebView
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
import androidx.compose.material3.ModalBottomSheetProperties
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
 * Renders [factory]'s WebView inside the shared Spotify-style sign-in sheet. The WebView stays
 * single-windowed: a popup running the shared diagnostic client would bypass the provider's own
 * redirect interception, cookie capture and JS bridges, so only [authPopupsEnabled] providers may
 * opt in — and none of the routed sign-ins does. Spotify's own sheet keeps its popup host.
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
    // In state, not a plain var: a plain var resets to null on recomposition (the factory runs
    // once) and never triggers one, leaving BackHandler permanently disabled.
    var webView by remember { mutableStateOf<WebView?>(null) }

    AuthWebViewSheet(
        title = title,
        subtitle = subtitle,
        modifier = modifier,
        onDismiss = { onDismiss?.invoke() ?: run { navController.navigateUp() } },
        // canGoBack() is polled at press time because it changes during in-page navigation
        // without recomposing.
        onBack = {
            val view = webView
            if (navigateBackInWebView && view?.canGoBack() == true) {
                view.goBack()
            } else {
                onDismiss?.invoke() ?: run { navController.navigateUp() }
            }
        },
        statusContent = statusContent,
    ) {
        AndroidView(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(MaterialTheme.shapes.large),
            factory = { ctx ->
                factory(ctx).apply {
                    // The routed providers all sign in inside one window; multi-window is a policy
                    // decision the flow opts into, never a default the shell applies.
                    settings.setSupportMultipleWindows(authPopupsEnabled(provider))
                    webView = this
                }
            },
            onRelease = { released ->
                onRelease?.invoke(released)
                if (webView === released) webView = null
            },
        )
    }
}

/** Shared by the routed sign-ins and both Spotify profiles, including their popup container. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AuthWebViewSheet(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
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
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
    ) {
        // The sheet is its own dialog window, so back presses only reach handlers registered
        // inside it; the sheet's own back-dismiss is off so the WebView can go back first.
        BackHandler(onBack = onBack)
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
