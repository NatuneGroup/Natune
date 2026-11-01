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
import moe.rukamori.archivetune.shared.data.lyrics.PaxsenixAppleMusicToken
import moe.rukamori.archivetune.shared.domain.platform.AuthorizedHttpsOrigin
import moe.rukamori.archivetune.shared.domain.platform.CookieOnlyLoginFailure
import moe.rukamori.archivetune.shared.domain.platform.CookieOnlyLoginOutcome
import moe.rukamori.archivetune.shared.domain.platform.CookieOnlyLoginRequest
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.domain.settings.AppleMusicDevTokenKey
import moe.rukamori.archivetune.shared.domain.settings.AppleMusicMediaUserTokenKey
import moe.rukamori.archivetune.shared.ui.navigation.ShellNavigation
import moe.rukamori.archivetune.shared.ui.settings.ProviderSignInFailure
import moe.rukamori.archivetune.shared.ui.settings.ProviderSignInKind
import moe.rukamori.archivetune.shared.ui.settings.ProviderSignInPage
import moe.rukamori.archivetune.shared.ui.settings.ProviderSignInState

private const val APPLE_PROVIDER = "applemusic"
private const val APPLE_LOGIN_URL = "https://music.apple.com/login"
private const val APPLE_MEDIA_USER_TOKEN_COOKIE = "media-user-token"
private const val APPLE_MUSIC_ACCOUNT = "Apple Music"
private val APPLE_MUSIC_ORIGIN = AuthorizedHttpsOrigin("music.apple.com")
private val APPLE_ORIGIN = AuthorizedHttpsOrigin("apple.com")
private val APPLE_LOGIN_ORIGINS = setOf(
    APPLE_MUSIC_ORIGIN,
    APPLE_ORIGIN,
    AuthorizedHttpsOrigin("www.apple.com"),
    AuthorizedHttpsOrigin("appleid.apple.com"),
    AuthorizedHttpsOrigin("idmsa.apple.com"),
    AuthorizedHttpsOrigin("account.apple.com"),
    AuthorizedHttpsOrigin("auth.apple.com"),
)

/**
 * The desktop Apple Music web sign-in: the cookie-only login window in place of the Android screen's
 * WebView + CookieManager. MusicKit keeps the media user token out of the jar, but the page also
 * stores it where the jar can see it, as a `media-user-token` cookie on the Apple domain. The
 * developer token is scraped with the same [PaxsenixAppleMusicToken] the lyrics provider uses, and
 * the pair commits into the two settings keys the Apple Music page also edits by hand.
 */
internal class DesktopAppleMusicWebSignIn(private val graph: DesktopSessionGraph) {
    private val browser = DesktopCookieOnlyBrowserAuth()
    private val generations = AtomicLong()
    val state = MutableStateFlow<ProviderSignInState>(ProviderSignInState.Idle)

    fun cancel() {
        generations.incrementAndGet()
        state.value = ProviderSignInState.Idle
    }

    /** One sign-in attempt, launched from the page's coroutine scope. Cancelling commits nothing. */
    suspend fun signIn() {
        val generation = generations.incrementAndGet()
        state.value = ProviderSignInState.Opening
        val request = CookieOnlyLoginRequest(
            provider = APPLE_PROVIDER,
            scope = CredentialScope.PERSONAL,
            startUrl = APPLE_LOGIN_URL,
            authorizedOrigins = APPLE_LOGIN_ORIGINS,
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
        val mediaUserToken = try {
            context.cookiesFor(APPLE_MUSIC_ORIGIN)
                .plus(context.cookiesFor(APPLE_ORIGIN))
                .firstOrNull { it.name == APPLE_MEDIA_USER_TOKEN_COOKIE }
                ?.value?.reveal()
        } finally {
            context.close()
        }
        if (generation != generations.get()) return
        if (mediaUserToken.isNullOrBlank() || !mediaUserToken.startsWith("0.")) {
            state.value = ProviderSignInState.Failed(ProviderSignInFailure.UNVERIFIED)
            return
        }
        state.value = ProviderSignInState.Verifying
        val developerToken = try {
            PaxsenixAppleMusicToken(graph.http).ensureFresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val saved = try {
            graph.settings.edit {
                this[AppleMusicMediaUserTokenKey] = mediaUserToken
                if (!developerToken.isNullOrBlank()) this[AppleMusicDevTokenKey] = developerToken
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (generation != generations.get()) return
        state.value = if (saved) {
            ProviderSignInState.SignedIn(APPLE_MUSIC_ACCOUNT)
        } else {
            ProviderSignInState.Failed(ProviderSignInFailure.NOT_SAVED)
        }
    }
}

private fun CookieOnlyLoginFailure.toSignInFailure() = when (this) {
    CookieOnlyLoginFailure.BROWSER_UNAVAILABLE -> ProviderSignInFailure.UNAVAILABLE
    CookieOnlyLoginFailure.TIMED_OUT -> ProviderSignInFailure.TIMED_OUT
    else -> ProviderSignInFailure.UNVERIFIED
}

@Composable
internal fun DesktopAppleMusicLoginPage(signIn: DesktopAppleMusicWebSignIn, navigation: ShellNavigation) {
    val state by signIn.state.collectAsState()
    val scope = rememberCoroutineScope()
    ProviderSignInPage(
        kind = ProviderSignInKind.APPLE_MUSIC_WEB,
        state = state,
        onStart = { scope.launch { signIn.signIn() } },
        onCancel = signIn::cancel,
        onDone = { navigation.back() },
    )
}
