package moe.rukamori.archivetune.shared.ui.ios

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import moe.rukamori.archivetune.shared.data.lyrics.PaxsenixAppleMusicToken
import moe.rukamori.archivetune.shared.data.platform.ios.IosAppleMusicLoginSheet
import moe.rukamori.archivetune.shared.domain.settings.AppleMusicDevTokenKey
import moe.rukamori.archivetune.shared.domain.settings.AppleMusicMediaUserTokenKey
import moe.rukamori.archivetune.shared.ui.settings.ProviderSignInFailure
import moe.rukamori.archivetune.shared.ui.settings.ProviderSignInState

private const val APPLE_MUSIC_ACCOUNT = "Apple Music"

/**
 * The iOS Apple Music web sign-in: the Android login WebView as the JS-evaluating
 * [IosAppleMusicLoginSheet]. A captured media user token commits straight into the two settings keys
 * the Apple Music page also edits by hand — there is no Apple account API here to verify the pair
 * against, so SignedIn means written, exactly what the Android screen reports.
 */
internal class IosAppleMusicWebSignIn(
    private val graph: IosSessionGraph,
    private val sheet: IosAppleMusicLoginSheet,
) {
    private var generation = 0L
    val state = MutableStateFlow<ProviderSignInState>(ProviderSignInState.Idle)

    /** Sheet dismissed or navigated away: a late result of the dead attempt commits nothing. */
    fun cancel() {
        generation += 1
        state.value = ProviderSignInState.Idle
    }

    /** One sign-in attempt, launched from the page's coroutine scope. */
    suspend fun signIn() {
        val attemptGeneration = ++generation
        state.value = ProviderSignInState.Opening
        val tokens = try {
            state.value = ProviderSignInState.Waiting
            sheet.tokens()
        } catch (timeout: TimeoutCancellationException) {
            state.value = ProviderSignInState.Failed(ProviderSignInFailure.TIMED_OUT)
            return
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
        if (attemptGeneration != generation) return
        if (tokens == null) {
            state.value = ProviderSignInState.Idle
            return
        }
        state.value = ProviderSignInState.Verifying
        // MusicKit hands most pages its developer token; the scrape is the same fallback the lyrics
        // provider uses, so a page that kept it to itself still leaves a working pair.
        val developerToken = tokens.developerToken?.takeIf { it.isNotBlank() } ?: try {
            PaxsenixAppleMusicToken(graph.http).ensureFresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val saved = try {
            graph.settings.edit {
                this[AppleMusicMediaUserTokenKey] = tokens.mediaUserToken
                if (!developerToken.isNullOrBlank()) this[AppleMusicDevTokenKey] = developerToken
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (attemptGeneration != generation) return
        state.value = if (saved) {
            ProviderSignInState.SignedIn(APPLE_MUSIC_ACCOUNT)
        } else {
            ProviderSignInState.Failed(ProviderSignInFailure.NOT_SAVED)
        }
    }
}
