package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.ktor.client.request.forms.submitForm
import io.ktor.http.parameters
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.desktop.integration.auth.DesktopCookieOnlyBrowserAuth
import moe.rukamori.archivetune.desktop.provider.fetchYouTubeAccountSession
import moe.rukamori.archivetune.desktop.provider.fetchYouTubeVisitorData
import moe.rukamori.archivetune.desktop.provider.verifyYouTubeAccountMenu
import moe.rukamori.archivetune.shared.domain.platform.AuthorizedHttpsOrigin
import moe.rukamori.archivetune.shared.domain.platform.CookieOnlyLoginFailure
import moe.rukamori.archivetune.shared.domain.platform.CookieOnlyLoginOutcome
import moe.rukamori.archivetune.shared.domain.platform.CookieOnlyLoginRequest
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.domain.platform.PrivateCookie
import moe.rukamori.archivetune.shared.domain.settings.DataSyncIdKey
import moe.rukamori.archivetune.shared.domain.settings.InnerTubeCookieKey
import moe.rukamori.archivetune.shared.domain.settings.InnerTubeOAuthRefreshTokenKey
import moe.rukamori.archivetune.shared.domain.settings.VisitorDataKey
import moe.rukamori.archivetune.shared.domain.settings.YouTubeAccountChannel
import moe.rukamori.archivetune.shared.domain.settings.YouTubeAccountIdentity
import moe.rukamori.archivetune.shared.domain.settings.YouTubeManualAuth
import moe.rukamori.archivetune.shared.domain.settings.YouTubeSessionCandidate
import moe.rukamori.archivetune.shared.domain.settings.commitYouTubeManualImport
import moe.rukamori.archivetune.shared.domain.settings.hasYouTubeSignInCookie
import moe.rukamori.archivetune.shared.ui.navigation.ShellNavigation
import moe.rukamori.archivetune.shared.ui.screens.YouTubeLoginActions
import moe.rukamori.archivetune.shared.ui.screens.YouTubeLoginError
import moe.rukamori.archivetune.shared.ui.screens.YouTubeLoginScreen
import moe.rukamori.archivetune.shared.ui.screens.YouTubeLoginStatus
import moe.rukamori.archivetune.shared.ui.screens.YouTubeLoginUi
import moe.rukamori.archivetune.shared.ui.settings.SettingsController

private const val YOUTUBE_PROVIDER = "youtube"
private const val YOUTUBE_LOGIN_URL = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"
private const val GOOGLE_REVOKE_URL = "https://oauth2.googleapis.com/revoke"

/** The cookies of a finished sign-in are read for YouTube Music, the origin the account requests run against. */
private val MUSIC_ORIGIN = AuthorizedHttpsOrigin("music.youtube.com")

/*
 * The Google sign-in and its return to YouTube Music pass through these hosts, including the consent and account
 * upgrade pages. The native window refuses every other navigation, so the list covers the official flow only.
 */
private val YOUTUBE_LOGIN_ORIGINS: Set<AuthorizedHttpsOrigin> = listOf(
    "accounts.google.com", "accounts.youtube.com", "music.youtube.com", "www.youtube.com", "consent.youtube.com",
    "consent.google.com", "myaccount.google.com", "gds.google.com", "www.google.com",
).map { AuthorizedHttpsOrigin(it) }.toSet()

/** How one sign-in attempt ended for the page. */
internal enum class DesktopYouTubeLoginResult { COMMITTED, CANCELLED, FAILED }

/**
 * The desktop YouTube account owner: the cookie-only sign-in window, the private verification of its cookies against
 * accounts_list and account_menu, and the one-edit commit. Nothing is written unless every step succeeds and the
 * attempt is still the newest one, so a failed or replaced attempt leaves the committed account unchanged.
 */
internal class DesktopYouTubeAccountOwner(
    private val graph: DesktopSessionGraph,
    private val controller: SettingsController,
) {
    private val browser = DesktopCookieOnlyBrowserAuth()
    private val generations = AtomicLong()
    private val channelState = MutableStateFlow<List<YouTubeAccountChannel>>(emptyList())

    /** The channels of the signed-in account, refreshed by [refreshChannels]. Empty while signed out or unknown. */
    val channels: StateFlow<List<YouTubeAccountChannel>> = channelState.asStateFlow()

    suspend fun verify(candidate: YouTubeSessionCandidate): YouTubeAccountIdentity? =
        verifyYouTubeAccountMenu(graph.http, candidate)

    /** Reads the channel list of the stored session. A failure keeps the last list rather than showing none. */
    suspend fun refreshChannels() {
        val snapshot = graph.settings.snapshots.value ?: return
        val cookie = snapshot[InnerTubeCookieKey, ""]
        if (!hasYouTubeSignInCookie(cookie)) {
            channelState.value = emptyList()
            return
        }
        val session = try {
            fetchYouTubeAccountSession(
                graph.http, cookie, snapshot[VisitorDataKey]?.takeIf { it.isNotBlank() },
                snapshot[DataSyncIdKey, ""].takeIf { it.isNotBlank() },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return
        channelState.value = session.channels
    }

    /** The sign-in window is ephemeral, so signing out only has to end an attempt that is still open. */
    suspend fun logOut() {
        browser.clearProfile(YOUTUBE_PROVIDER, CredentialScope.PERSONAL)
        channelState.value = emptyList()
    }

    /** Revokes the stored OAuth refresh token with Google. The page clears the local session whatever this does. */
    suspend fun revokeCodeSession() {
        val refresh = graph.settings.snapshots.value?.get(InnerTubeOAuthRefreshTokenKey, "").orEmpty()
        if (refresh.isBlank()) return
        graph.http.submitForm(GOOGLE_REVOKE_URL, parameters { append("token", refresh) })
    }

    /**
     * One sign-in attempt. [status] receives every state the page shows. Cancelling the caller closes the window and
     * commits nothing.
     */
    suspend fun signIn(status: (YouTubeLoginStatus) -> Unit): DesktopYouTubeLoginResult {
        val generation = generations.incrementAndGet()
        status(YouTubeLoginStatus.Loading)
        val request = CookieOnlyLoginRequest(
            provider = YOUTUBE_PROVIDER,
            scope = CredentialScope.PERSONAL,
            startUrl = YOUTUBE_LOGIN_URL,
            authorizedOrigins = YOUTUBE_LOGIN_ORIGINS,
            generation = generation,
        )
        val context = when (val outcome = browser.login(request)) {
            is CookieOnlyLoginOutcome.Completed -> outcome.context
            CookieOnlyLoginOutcome.Cancelled -> return DesktopYouTubeLoginResult.CANCELLED
            is CookieOnlyLoginOutcome.Failed -> {
                status(YouTubeLoginStatus.Failed(outcome.failure.toLoginError()))
                return DesktopYouTubeLoginResult.FAILED
            }
        }
        val cookie = try {
            cookieHeader(context.cookiesFor(MUSIC_ORIGIN))
        } finally {
            context.close()
        }
        val error = try {
            commitVerified(cookie, generation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            YouTubeLoginError.Network
        }
        if (error != null) {
            status(YouTubeLoginStatus.Failed(error))
            return DesktopYouTubeLoginResult.FAILED
        }
        if (generation != generations.get()) return DesktopYouTubeLoginResult.CANCELLED
        status(YouTubeLoginStatus.Succeeded)
        return DesktopYouTubeLoginResult.COMMITTED
    }

    /** Verifies the cookie privately and commits it with its identity in one edit. Returns the failure, or null. */
    private suspend fun commitVerified(cookie: String, generation: Long): YouTubeLoginError? {
        if (!hasYouTubeSignInCookie(cookie)) return YouTubeLoginError.LoginFailed
        val session = fetchYouTubeAccountSession(graph.http, cookie, visitorData = null, dataSyncId = null)
            ?: return YouTubeLoginError.Http
        val dataSyncId = session.dataSyncId ?: return YouTubeLoginError.MissingDataSyncId
        val visitorData = session.visitorData ?: fetchYouTubeVisitorData(graph.http)
        val identity = verifyYouTubeAccountMenu(graph.http, YouTubeSessionCandidate(cookie, visitorData, dataSyncId))
            ?: return YouTubeLoginError.LoginFailed
        // A newer attempt owns the session now; this one must not overwrite it.
        if (generation != generations.get()) return null
        controller.edit { commitYouTubeManualImport(YouTubeManualAuth(cookie, visitorData, dataSyncId, null), identity) }
        channelState.value = session.channels
        return null
    }
}

/*
 * One cookie per name for the request header. A host-only cookie for music.youtube.com is more specific than the
 * shared .youtube.com one, so it wins when both are present.
 */
private fun cookieHeader(cookies: List<PrivateCookie>): String =
    cookies.groupBy { it.name }
        .map { (_, sameName) -> sameName.maxBy { it.domain.removePrefix(".").length } }
        .joinToString("; ") { "${it.name}=${it.value.reveal()}" }

private fun CookieOnlyLoginFailure.toLoginError(): YouTubeLoginError = when (this) {
    CookieOnlyLoginFailure.BROWSER_UNAVAILABLE -> YouTubeLoginError.RendererLost
    CookieOnlyLoginFailure.NAVIGATION_REFUSED -> YouTubeLoginError.UnsupportedNavigation
    CookieOnlyLoginFailure.TIMED_OUT, CookieOnlyLoginFailure.CONTEXT_UNAVAILABLE -> YouTubeLoginError.LoginFailed
}

/**
 * The YouTube sign-in page. The sign-in itself runs in the native window, so the page's web area explains where to
 * look. Leaving the page cancels the attempt and closes the window; a committed or cancelled attempt returns here.
 */
@Composable
internal fun DesktopYouTubeLoginPage(owner: DesktopYouTubeAccountOwner, navigation: ShellNavigation) {
    var attempt by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<YouTubeLoginStatus>(YouTubeLoginStatus.Loading) }
    LaunchedEffect(attempt) {
        when (owner.signIn { status = it }) {
            DesktopYouTubeLoginResult.COMMITTED, DesktopYouTubeLoginResult.CANCELLED -> navigation.back()
            DesktopYouTubeLoginResult.FAILED -> Unit
        }
    }
    YouTubeLoginScreen(
        state = YouTubeLoginUi(status),
        actions = YouTubeLoginActions(back = { navigation.back() }, retry = { attempt++ }),
        webSurface = { modifier ->
            val failure = status as? YouTubeLoginStatus.Failed
            val hint = if (failure?.error == YouTubeLoginError.RendererLost) "native_browser_unavailable" else "youtube_login_window_hint"
            Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    DesktopStrings[hint],
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        },
    )
}
