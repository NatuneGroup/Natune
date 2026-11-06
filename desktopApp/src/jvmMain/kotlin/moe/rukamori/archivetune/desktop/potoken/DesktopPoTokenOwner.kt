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
import moe.rukamori.archivetune.shared.ui.settings.PoTokenHelperState
import moe.rukamori.archivetune.shared.ui.settings.PoTokenHelperStatus

/**
 * The desktop PO token owner, the counterpart of Android's process-wide `BotGuardTokenGenerator`
 * object: one shared BotGuard engine whose QuickJS session, mint cache and broken state the primary
 * player walk, the InnerTubeX tier and the settings generation all share.
 *
 * A build that carries both BotGuard credentials uses that engine when `webClientPoTokenEnabled` is
 * on. Every other build (including the shipped desktop artifact, whose BotGuard keys are empty)
 * falls back to the managed local helper, which mints through the pinned `bgutil` server on
 * loopback. The helper is independent of the old web-client switch and on by default; when it is
 * disabled, unsupported or unavailable the owner answers null exactly as the old unconfigured path
 * did, so requests stay untokened rather than fabricating a token.
 */
internal class DesktopPoTokenOwner(
    private val http: HttpClient,
    private val enabled: () -> Boolean,
    private val log: (String) -> Unit = { DesktopDiagnosticLog.playbackTier(it) },
    private val localHelper: LocalPoTokenHelper? = null,
    private val localHelperEnabled: () -> Boolean = { true },
    private val configuredCredentials: (() -> PoTokenCredentials?)? = null,
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
        configuredCredentials?.invoke() ?: PoTokenCredentials(
            requestKey = DesktopCredentials.value("ARCHIVETUNE_POTOKEN_REQUEST_KEY"),
            googleApiKey = DesktopCredentials.value("ARCHIVETUNE_POTOKEN_GOOGLE_API_KEY"),
        )

    /** The build-secret BotGuard path is usable only when the preference is on and both keys are present. */
    private fun botGuardUsable(): Boolean = enabled() && credentials()?.isConfigured == true

    /** The managed helper is independent of the old web-client switch and on unless the user turned it off. */
    private fun helperUsable(): Boolean = localHelperEnabled() && localHelper?.availableOnHost == true

    /**
     * The minted pair for one [videoId] resolve under the current session, as
     * `resolvePlayerPoToken`/`resolveGvsPoToken` would read them: the video-id-bound player token
     * first, the session token as the fallback. Null when the feature is off, unconfigured or the
     * mint failed — the caller then sends the same untokened request it always did.
     */
    suspend fun mintForPlayback(
        videoId: String,
        signIn: DesktopYouTubeSignIn?,
        visitorData: String?,
    ): MintedPoTokens? {
        val hasLoginContext = signIn != null && !signIn.dataSyncId.isNullOrBlank()
        val sessionId = poTokenSessionId(signIn?.dataSyncId, visitorData, hasLoginContext) ?: return null
        // The build-secret BotGuard flow stays preferred when a build actually carries both keys; otherwise
        // the managed helper answers by default, so a stock build is never untokened merely because the
        // old web-client switch (default off) and the release credentials are absent.
        if (botGuardUsable()) {
            engine.mintToken(identifier = videoId, sessionId = sessionId)?.let { return it }
        }
        if (!localHelperEnabled()) {
            localHelper?.disable()
            return null
        }
        return localHelper?.mint(videoId, sessionId)
    }

    /** The shipped preWarm for [sessionId]; a no-op while no BotGuard engine is configured. */
    internal suspend fun preWarm(sessionId: String) {
        if (!botGuardUsable()) return
        engine.preWarm(sessionId)
    }

    /** Stops the managed helper and drops every minted token, on session close. */
    internal suspend fun close() {
        if (engineRef.isInitialized()) engine.invalidate()
        try {
            localHelper?.close()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        }
    }

    /** The status the settings page shows; it starts nothing and carries no token value. */
    internal suspend fun localHelperStatus(): PoTokenHelperStatus {
        if (!localHelperEnabled()) {
            localHelper?.disable()
            return PoTokenHelperStatus(PoTokenHelperState.DISABLED)
        }
        val helper = localHelper ?: return PoTokenHelperStatus(PoTokenHelperState.UNSUPPORTED)
        return helper.status()
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
        val botGuard = botGuardUsable()
        val helper = localHelperEnabled() && localHelper?.availableOnHost == true
        if (!botGuard && !helper) {
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
        if (botGuard) {
            val minted = engine.mintForSettings(tokenBindingId = sessionId, visitorData = visitor)
                ?: return PoTokenGeneration.Failed(null)
            return PoTokenGeneration.Complete(
                visitorData = minted.visitorData,
                gvsToken = minted.gvsToken,
                playerToken = minted.playerToken,
            )
        }
        // The helper answers the same two bindings the extraction recipe mints: the GVS token for the
        // session id and the player token for the literal identifier `player`.
        val minted = localHelper?.mint(videoId = SETTINGS_PLAYER_BINDING, sessionId = sessionId)
            ?: return PoTokenGeneration.Failed(null)
        return PoTokenGeneration.Complete(
            visitorData = visitor,
            gvsToken = minted.sessionToken,
            playerToken = minted.playerToken,
        )
    }

    /**
     * The InnerTubeX [TokenProvider] the extractor's client director consults. It names
     * `WEB_BOTGUARD` while either the credentialed engine or the managed helper can mint, so the
     * extractor's token gate admits the web clients it can actually satisfy instead of rejecting
     * them for a missing provider. The returned pair follows the library's binding contract —
     * `playerRequestToken` is bound to the visitor data it asked for, `streamingDataToken` to the
     * video id — and the visitor data is echoed back unchanged.
     */
    val tokenProvider: TokenProvider =
        object : TokenProvider {
            override val capabilities: TokenProviderCapabilities
                get() =
                    TokenProviderCapabilities(
                        providers =
                            if (botGuardUsable() || helperUsable()) {
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
                val sessionId = poTokenSessionId(null, visitorData, hasLoginContext = false) ?: return null
                // InnerTubeX calls this inside its own 12 s tier bound, so each mint keeps a short
                // budget of its own: a stalled endpoint leaves the request untokened instead of
                // eating the whole tier.
                if (botGuardUsable()) {
                    val minted =
                        try {
                            withTimeoutOrNull(MINT_BUDGET_MS) {
                                engine.mintToken(identifier = videoId, sessionId = sessionId)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                    if (minted != null) {
                        return PoTokenResult(
                            playerRequestToken = minted.sessionToken,
                            streamingDataToken = minted.playerToken,
                            visitorData = visitorData,
                        )
                    }
                }
                if (!helperUsable()) return null
                val minted =
                    try {
                        withTimeoutOrNull(SIDECAR_MINT_BUDGET_MS) {
                            localHelper?.mint(videoId, sessionId)
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

            override suspend fun invalidateAttestation() {
                engine.invalidate()
                localHelper?.clearTokens()
            }

            override suspend fun close() {
                engine.invalidate()
                localHelper?.clearTokens()
            }
        }

    private companion object {
        // A fixed sanitized detail; never a credential fragment.
        const val NOT_CONFIGURED_DETAIL = "BotGuard credentials are not configured"

        /** The mint allowance a bounded extractor tier grants, leaving the rest to extraction. */
        const val MINT_BUDGET_MS = 3_000L

        /** The helper starts its process lazily; the extractor tier still has to finish inside 12 s. */
        const val SIDECAR_MINT_BUDGET_MS = 9_000L

        /** The literal identifier the extraction recipe binds its player token to. */
        const val SETTINGS_PLAYER_BINDING = "player"
    }
}
