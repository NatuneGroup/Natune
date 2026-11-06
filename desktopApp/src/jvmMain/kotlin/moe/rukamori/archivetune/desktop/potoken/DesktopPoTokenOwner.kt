package moe.rukamori.archivetune.desktop.potoken

import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.desktop.DesktopCredentials
import moe.rukamori.archivetune.desktop.DesktopDiagnosticLog
import moe.rukamori.archivetune.desktop.provider.DesktopYouTubeSignIn
import moe.rukamori.archivetune.desktop.provider.youTubeSignIn
import moe.rukamori.archivetune.shared.data.potoken.BotGuardPoTokenEngine
import moe.rukamori.archivetune.shared.data.potoken.jvmBotGuardJsRuntime
import moe.rukamori.archivetune.shared.data.potoken.MintedPoTokens
import moe.rukamori.archivetune.shared.data.potoken.PoTokenCredentials
import moe.rukamori.archivetune.shared.data.potoken.poTokenForWebClient
import moe.rukamori.archivetune.shared.data.potoken.poTokenSessionId
import moe.rukamori.archivetune.shared.data.provider.fetchYouTubeVisitorData
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.ui.settings.PoTokenGeneration

/**
 * The desktop BotGuard owner, the counterpart of Android's process-wide `BotGuardTokenGenerator`
 * object: one shared engine whose QuickJS session, mint cache and broken state the primary player
 * walk, the InnerTubeX tier and the settings generation all share. Minting runs while the
 * `webClientPoTokenEnabled` preference is on and the build, environment or a system property
 * supplies both BotGuard credentials. Every other state reads exactly like the shipped
 * unconfigured path (null tokens, untokened requests) rather than fabricating a token, which is
 * what a blank release build still does today.
 */
internal class DesktopPoTokenOwner(
    private val http: HttpClient,
    private val enabled: () -> Boolean,
    private val log: (String) -> Unit = { DesktopDiagnosticLog.playbackTier(it) },
) {
    private val engineRef = lazy {
        BotGuardPoTokenEngine(
            http = http,
            credentials = { credentials() },
            runtimeFactory = { jvmBotGuardJsRuntime() },
            report = log,
        )
    }
    private val engine get() = engineRef.value

    private fun credentials(): PoTokenCredentials? =
        PoTokenCredentials(
            requestKey = DesktopCredentials.value(REQUEST_KEY_NAME),
            googleApiKey = DesktopCredentials.value(API_KEY_NAME),
        )

    /**
     * The minted pair for one [videoId] resolve under the current session, as
     * `resolvePlayerPoToken`/`resolveGvsPoToken` would read them: the video-id-bound player token
     * first, the session token as the fallback. Null when the feature is off or the mint failed —
     * the caller then sends the same untokened request it always did.
     */
    suspend fun mintForPlayback(
        videoId: String,
        signIn: DesktopYouTubeSignIn?,
        visitorData: String?,
    ): MintedPoTokens? {
        if (!enabled()) return null
        val hasLoginContext = signIn != null && !signIn.dataSyncId.isNullOrBlank()
        val sessionId = poTokenSessionId(signIn?.dataSyncId, visitorData, hasLoginContext) ?: return null
        return engine.mintToken(identifier = videoId, sessionId = sessionId)
    }

    /** The shipped preWarm for [sessionId]; a no-op while the poToken preference is off. */
    internal suspend fun preWarm(sessionId: String) {
        if (!enabled()) return
        engine.preWarm(sessionId)
    }

    /** Drops the QuickJS runtime and the token caches, if a session ever minted. */
    internal suspend fun close() {
        if (engineRef.isInitialized()) engine.invalidate()
    }

    /** The token one web-client player request or stream URL carries; null outside the web set. */
    fun tokenFor(clientName: String, minted: MintedPoTokens?): String? =
        poTokenForWebClient(clientName, minted)

    /**
     * The settings "regenerate" action, mirroring `PoTokenExtractionActivity` without the WebView:
     * the visitor data comes from the stored session (or one network fetch, like the recipe's own
     * page), the GVS token binds to the session id (the `dataSyncId` user session when signed in)
     * and the player token to the literal `player` identifier the shipped page mints.
     * [sourceUrl] is the Android WebView destination; the headless recipe needs no page, so the
     * persisted preference stays purely informational here.
     */
    suspend fun generate(sourceUrl: String, snapshot: PreferenceSnapshot?): PoTokenGeneration {
        val creds = credentials()
        if (creds == null || !creds.isConfigured) {
            return PoTokenGeneration.Failed(NOT_CONFIGURED_DETAIL)
        }
        val session = snapshot.youTubeSignIn()
        val hasLoginContext = session != null && !session.dataSyncId.isNullOrBlank()
        val visitor =
            session?.visitorData?.takeIf(String::isNotBlank)
                ?: try {
                    fetchYouTubeVisitorData(http)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                ?: return PoTokenGeneration.Failed(null)
        // tokenBindingId = sessionId ?: visitorData, as the Android extraction activity binds it.
        val sessionId =
            poTokenSessionId(session?.dataSyncId, visitor, hasLoginContext) ?: visitor
        val minted = engine.mintForSettings(tokenBindingId = sessionId, visitorData = visitor)
            ?: return PoTokenGeneration.Failed(null)
        return PoTokenGeneration.Complete(
            visitorData = minted.visitorData,
            gvsToken = minted.gvsToken,
            playerToken = minted.playerToken,
        )
    }

    /**
     * The InnerTubeX [TokenProvider] the extractor's client director consults. It names
     * `WEB_BOTGUARD` only while minting is enabled; the returned pair follows the library's
     * binding contract — `playerRequestToken` is bound to the visitor data it asked for,
     * `streamingDataToken` to the video id — and the visitor data is echoed back unchanged.
     */
    val tokenProvider: TokenProvider =
        object : TokenProvider {
            override val capabilities: TokenProviderCapabilities
                get() =
                    TokenProviderCapabilities(
                        providers =
                            if (enabled() && credentials()?.isConfigured == true) {
                                setOf(PoTokenProviderKind.WEB_BOTGUARD)
                            } else {
                                emptySet()
                            },
                        usesWebView = false,
                    )

            override suspend fun getPoToken(
                videoId: String,
                visitorData: String,
                cookie: String?,
            ): PoTokenResult? {
                if (!enabled()) return null
                val sessionId = poTokenSessionId(null, visitorData, hasLoginContext = false) ?: return null
                // InnerTubeX calls this inside its own 12 s tier bound, so the mint keeps a short
                // budget of its own: a stalled BotGuard endpoint leaves the request untokened
                // instead of eating the whole tier.
                val minted =
                    try {
                        withTimeoutOrNull(MINT_BUDGET_MS) {
                            engine.mintToken(identifier = videoId, sessionId = sessionId)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    } ?: return null
                return PoTokenResult(
                    playerRequestToken = minted.sessionToken,
                    streamingDataToken = minted.playerToken,
                    visitorData = visitorData,
                )
            }

            override suspend fun invalidateAttestation() = engine.invalidate()

            override suspend fun close() = engine.invalidate()
        }

    /** Drops the minted session and the identifier cache after the network rejected a token. */
    suspend fun invalidateAttestation() {
        if (engineRef.isInitialized()) engine.invalidate()
    }

    private companion object {
        const val REQUEST_KEY_NAME = "ARCHIVETUNE_POTOKEN_REQUEST_KEY"
        const val API_KEY_NAME = "ARCHIVETUNE_POTOKEN_GOOGLE_API_KEY"

        // A fixed sanitized detail; never a credential fragment.
        const val NOT_CONFIGURED_DETAIL = "BotGuard credentials are not configured"

        /** The mint allowance a bounded extractor tier grants, leaving the rest to extraction. */
        const val MINT_BUDGET_MS = 3_000L
    }
}
