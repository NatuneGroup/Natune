package moe.rukamori.archivetune.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.desktop.integration.auth.DesktopCookieOnlyBrowserAuth
import moe.rukamori.archivetune.shared.data.provider.verifyDeezerArl
import moe.rukamori.archivetune.shared.domain.platform.AuthorizedHttpsOrigin
import moe.rukamori.archivetune.shared.domain.platform.CookieOnlyLoginFailure
import moe.rukamori.archivetune.shared.domain.platform.CookieOnlyLoginOutcome
import moe.rukamori.archivetune.shared.domain.platform.CookieOnlyLoginRequest
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.ui.navigation.ShellNavigation
import moe.rukamori.archivetune.shared.ui.settings.ProviderAccountLifecycle
import moe.rukamori.archivetune.shared.ui.settings.ProviderSignInKind
import moe.rukamori.archivetune.shared.ui.settings.ProviderSignInOutcome
import moe.rukamori.archivetune.shared.ui.settings.ProviderSignInPage
import moe.rukamori.archivetune.shared.ui.settings.ProviderSignInFailure
import moe.rukamori.archivetune.shared.ui.settings.ProviderSignInState

private const val DEEZER_PROVIDER = "deezer"
private const val DEEZER_LOGIN_URL = "https://www.deezer.com/login"
private const val DEEZER_ARL_COOKIE = "arl"
private val DEEZER_ORIGIN = AuthorizedHttpsOrigin("deezer.com")
private val DEEZER_WWW_ORIGIN = AuthorizedHttpsOrigin("www.deezer.com")
private val DEEZER_LOGIN_ORIGINS = setOf(
    DEEZER_ORIGIN,
    DEEZER_WWW_ORIGIN,
    AuthorizedHttpsOrigin("connect.deezer.com"),
    AuthorizedHttpsOrigin("login.deezer.com"),
)

/**
 * The desktop Deezer web sign-in: the cookie-only login window the Android screen's WebView +
 * CookieManager became on a host that cannot hook requests. The page is `www.deezer.com`, the read
 * is the `arl` cookie, and the verify + commit run through the same lifecycle the pasted-arl path
 * uses, so a rejected cookie and a rejected paste land on the same refusal.
 */
internal class DesktopDeezerWebSignIn(private val graph: DesktopSessionGraph) {
    private val browser = DesktopCookieOnlyBrowserAuth()
    private val generations = AtomicLong()
    val state = MutableStateFlow<ProviderSignInState>(ProviderSignInState.Idle)

    fun cancel() {
        generations.incrementAndGet()
        state.value = ProviderSignInState.Idle
    }

    /** One sign-in attempt, launched from the page's coroutine scope. Cancelling it closes the window and commits nothing. */
    suspend fun signIn() {
        val generation = generations.incrementAndGet()
        state.value = ProviderSignInState.Opening
        val request = CookieOnlyLoginRequest(
            provider = DEEZER_PROVIDER,
            scope = CredentialScope.PERSONAL,
            startUrl = DEEZER_LOGIN_URL,
            authorizedOrigins = DEEZER_LOGIN_ORIGINS,
            generation = generation,
        )
        val context = when (val outcome = browser.login(request)) {
            is CookieOnlyLoginOutcome.Completed -> outcome.context
            CookieOnlyLoginOutcome.Cancelled -> {
                state.value = ProviderSignInState.Idle
                return
            }
            is CookieOnlyLoginOutcome.Failed -> {
                state.value = ProviderSignInState.Failed(outcome.failure.toSignInFailure())
                return
            }
        }
        val arl = try {
            context.cookiesFor(DEEZER_ORIGIN)
                .plus(context.cookiesFor(DEEZER_WWW_ORIGIN))
                .firstOrNull { it.name == DEEZER_ARL_COOKIE }
                ?.value
                ?.reveal()
        } finally {
            context.close()
        }
        if (generation != generations.get()) return
        if (arl == null || arl.reveal().isBlank()) {
            state.value = ProviderSignInState.Failed(ProviderSignInFailure.UNVERIFIED)
            return
        }
        state.value = ProviderSignInState.Verifying
        val account = try {
            verifyDeezerArl(graph.http, arl.reveal())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (generation != generations.get()) return
        val attempt = graph.accountLifecycle.begin(ProviderAccountLifecycle.DEEZER)
        val outcome = account?.let { graph.accountLifecycle.commitDeezer(attempt, it.userId, arl.reveal(), it.name, it.lossless) }
        when (outcome) {
            ProviderSignInOutcome.Accepted ->
                state.value = ProviderSignInState.SignedIn(requireNotNull(account).name)
            ProviderSignInOutcome.Stale -> Unit
            else -> state.value = ProviderSignInState.Failed(ProviderSignInFailure.REJECTED)
        }
    }
}

private fun CookieOnlyLoginFailure.toSignInFailure() = when (this) {
    CookieOnlyLoginFailure.BROWSER_UNAVAILABLE -> ProviderSignInFailure.UNAVAILABLE
    CookieOnlyLoginFailure.TIMED_OUT -> ProviderSignInFailure.TIMED_OUT
    else -> ProviderSignInFailure.UNVERIFIED
}

@Composable
internal fun DesktopDeezerLoginPage(signIn: DesktopDeezerWebSignIn, navigation: ShellNavigation) {
    val state by signIn.state.collectAsState()
    val scope = rememberCoroutineScope()
    ProviderSignInPage(
        kind = ProviderSignInKind.DEEZER_WEB,
        state = state,
        onStart = { scope.launch { signIn.signIn() } },
        onCancel = signIn::cancel,
        onDone = { navigation.back() },
    )
}
