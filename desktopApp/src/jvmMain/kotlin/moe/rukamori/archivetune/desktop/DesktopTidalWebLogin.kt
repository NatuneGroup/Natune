package moe.rukamori.archivetune.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.desktop.integration.auth.NativeCookieBrowser
import moe.rukamori.archivetune.desktop.integration.auth.NativeCookieBrowserListener
import moe.rukamori.archivetune.desktop.integration.auth.NativeCookieBrowserSink
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.data.provider.TIDAL_PKCE_REDIRECT_URI
import moe.rukamori.archivetune.shared.ui.navigation.ShellNavigation
import moe.rukamori.archivetune.shared.ui.settings.ProviderAccountLifecycle
import moe.rukamori.archivetune.shared.ui.settings.ProviderSignInFailure
import moe.rukamori.archivetune.shared.ui.settings.ProviderSignInKind
import moe.rukamori.archivetune.shared.ui.settings.ProviderSignInOutcome
import moe.rukamori.archivetune.shared.ui.settings.ProviderSignInPage
import moe.rukamori.archivetune.shared.ui.settings.ProviderSignInState
import java.net.URI
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

/**
 * The desktop form of the Android `settings/tidal/login` WebView: the same native sign-in window,
 * opened on the PKCE authorize URL with the fixed https redirect as its capture prefix. The native
 * navigation gate reports the redirect URL once and cancels it — the code never lands in a page.
 */
internal class DesktopTidalWebSignIn(private val graph: DesktopSessionGraph) {
    val state = MutableStateFlow<ProviderSignInState>(ProviderSignInState.Idle)
    private val generations = AtomicLong()

    fun cancel() {
        generations.incrementAndGet()
        state.value = ProviderSignInState.Idle
    }

    suspend fun signIn() {
        val generation = generations.incrementAndGet()
        if (!graph.tidalAuth.pkceSignInAvailable) {
            state.value = ProviderSignInState.Failed(ProviderSignInFailure.UNAVAILABLE)
            return
        }
        state.value = ProviderSignInState.Opening
        val authorization = try {
            graph.tidalAuth.startPkceAuthorization()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (authorization == null) {
            state.value = ProviderSignInState.Failed(ProviderSignInFailure.UNAVAILABLE)
            return
        }
        if (generation != generations.get()) return
        state.value = ProviderSignInState.Waiting
        val captured = capturePkceRedirect(authorization.authorizeUrl)
        if (generation != generations.get()) return
        if (captured == null) {
            state.value = ProviderSignInState.Idle
            return
        }
        val code = captured.queryParam("code")
        if (code.isNullOrBlank()) {
            state.value = ProviderSignInState.Failed(ProviderSignInFailure.UNVERIFIED)
            return
        }
        state.value = ProviderSignInState.Verifying
        val session = try {
            graph.tidalAuth.exchangePkceCode(authorization, code)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (generation != generations.get()) return
        if (session == null) {
            state.value = ProviderSignInState.Failed(ProviderSignInFailure.UNVERIFIED)
            return
        }
        val attempt = graph.accountLifecycle.begin(ProviderAccountLifecycle.TIDAL)
        val outcome = graph.accountLifecycle.commitTidal(attempt, session)
        if (generation != generations.get()) return
        state.value = when (outcome) {
            ProviderSignInOutcome.Accepted ->
                ProviderSignInState.SignedIn(session.accountName ?: session.userId?.toString().orEmpty())
            ProviderSignInOutcome.NotSaved -> ProviderSignInState.Failed(ProviderSignInFailure.NOT_SAVED)
            else -> ProviderSignInState.Failed(ProviderSignInFailure.UNVERIFIED)
        }
    }

    /** Waits on the native window until its navigation gate captures the redirect or the window closes. */
    private suspend fun capturePkceRedirect(authorizeUrl: String): String? {
        val sink = PkceCaptureSink()
        var handle = 0L
        try {
            handle = withContext(Dispatchers.IO + NonCancellable) {
                try {
                    NativeCookieBrowser.ensureLoaded()
                    NativeCookieBrowser.open(
                        authorizeUrl,
                        TIDAL_WEB_ORIGINS,
                        DesktopStrings["browser_title"],
                        DesktopStrings["browser_finish"],
                        TIDAL_PKCE_REDIRECT_URI,
                        NativeCookieBrowserListener(sink),
                    )
                } catch (_: LinkageError) {
                    0L
                }
            }
            if (handle == 0L) return null
            return withTimeoutOrNull(CAPTURE_TIMEOUT_MILLIS) { sink.captured.await() }
        } finally {
            if (handle != 0L) NativeCookieBrowser.close(handle)
        }
    }

    private class PkceCaptureSink : NativeCookieBrowserSink {
        val captured = CompletableDeferred<String>()

        override fun onNavigate(url: String) {
            captured.complete(url)
        }

        override fun onFinish() {
            captured.complete("")
        }

        override fun onCookieRows(requestId: Long, rows: Array<Array<String>>?) {}

        override fun onWindowClosed(failed: Boolean) {
            captured.complete("")
        }
    }

    private companion object {
        const val CAPTURE_TIMEOUT_MILLIS = 300_000L

        /* The origins a PKCE authorization may visit; the capture prefix is checked before this gate. */
        val TIDAL_WEB_ORIGINS = arrayOf(
            "https://login.tidal.com",
            "https://auth.tidal.com",
            "https://tidal.com",
            "https://www.tidal.com",
            "https://offer.tidal.com",
            "https://account.tidal.com",
        )
    }
}

private fun String.queryParam(name: String): String? = try {
    URI(this).query?.split('&')
        ?.firstOrNull { it.substringBefore('=') == name }
        ?.substringAfter('=', "")
        ?.takeIf { it.isNotBlank() }
} catch (_: Exception) {
    null
}

@Composable
internal fun DesktopTidalLoginPage(signIn: DesktopTidalWebSignIn, navigation: ShellNavigation) {
    val state by signIn.state.collectAsState()
    val scope = rememberCoroutineScope()
    ProviderSignInPage(
        kind = ProviderSignInKind.TIDAL_WEB,
        state = state,
        onStart = { scope.launch { signIn.signIn() } },
        onCancel = signIn::cancel,
        onDone = { navigation.back() },
    )
}
