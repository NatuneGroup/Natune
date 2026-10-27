package moe.rukamori.archivetune.shared.ui.ios

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import moe.rukamori.archivetune.shared.data.platform.ios.IosCookieOnlyBrowserAuth
import moe.rukamori.archivetune.shared.data.provider.verifyDeezerArl
import moe.rukamori.archivetune.shared.domain.platform.AuthorizedHttpsOrigin
import moe.rukamori.archivetune.shared.domain.platform.CookieOnlyLoginFailure
import moe.rukamori.archivetune.shared.domain.platform.CookieOnlyLoginOutcome
import moe.rukamori.archivetune.shared.domain.platform.CookieOnlyLoginRequest
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.ui.settings.ProviderAccountLifecycle
import moe.rukamori.archivetune.shared.ui.settings.ProviderSignInFailure
import moe.rukamori.archivetune.shared.ui.settings.ProviderSignInOutcome
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
 * The iOS Deezer web sign-in: the Android screen's WebView + CookieManager, as an in-memory WKWebView
 * sheet behind the cookie-only port. The `arl` cookie is read only after the user taps Done, and the
 * verify + commit run through the same lifecycle the pasted-arl path uses.
 */
class IosDeezerWebSignIn(private val graph: IosSessionGraph, private val browser: IosCookieOnlyBrowserAuth) {
    private var generation = 0L
    val state = MutableStateFlow<ProviderSignInState>(ProviderSignInState.Idle)

    fun cancel() {
        generation += 1
        state.value = ProviderSignInState.Idle
    }

    /** One sign-in attempt, launched from the page's coroutine scope. Cancelling it closes the sheet and commits nothing. */
    suspend fun signIn() {
        val attemptGeneration = ++generation
        state.value = ProviderSignInState.Opening
        val context = when (
            val outcome = browser.login(
                CookieOnlyLoginRequest(
                    provider = DEEZER_PROVIDER,
                    scope = CredentialScope.PERSONAL,
                    startUrl = DEEZER_LOGIN_URL,
                    authorizedOrigins = DEEZER_LOGIN_ORIGINS,
                    generation = attemptGeneration,
                )
            )
        ) {
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
                ?.value?.reveal()
        } finally {
            context.close()
        }
        if (attemptGeneration != generation) return
        if (arl.isNullOrBlank()) {
            state.value = ProviderSignInState.Failed(ProviderSignInFailure.UNVERIFIED)
            return
        }
        state.value = ProviderSignInState.Verifying
        val account = try {
            verifyDeezerArl(graph.http, arl)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (attemptGeneration != generation) return
        val attempt = graph.accountLifecycle.begin(ProviderAccountLifecycle.DEEZER)
        val outcome = account?.let { graph.accountLifecycle.commitDeezer(attempt, it.userId, arl, it.name, it.lossless) }
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
