package moe.rukamori.archivetune.shared.data.potoken

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.shared.data.platform.ios.iosBuildSetting
import moe.rukamori.archivetune.shared.data.provider.IosYouTubeBrowseTransport
import moe.rukamori.archivetune.shared.data.provider.fetchYouTubeVisitorData
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot

/**
 * The iOS BotGuard owner, the counterpart of Android's process-wide `BotGuardTokenGenerator`
 * object: one shared engine whose QuickJS session, mint cache and broken state the shipped
 * client walk, the InnerTubeX tier and the settings generation all share. Minting only runs
 * while the `webClientPoTokenEnabled` preference is on and the build carries both BotGuard
 * credentials; every other state answers exactly like the shipped unconfigured path.
 */
class IosPoTokenSupport(
    private val http: HttpClient,
    private val transport: IosYouTubeBrowseTransport,
    private val enabled: () -> Boolean,
    private val report: (String) -> Unit = {},
) {
    private val engineRef = lazy {
        BotGuardPoTokenEngine(
            http = http,
            credentials = { credentials() },
            runtimeFactory = { IosBotGuardJsRuntime.create() },
            report = report,
        )
    }
    private val engine get() = engineRef.value

    private fun credentials(): PoTokenCredentials? =
        PoTokenCredentials(
            requestKey = iosBuildSetting("ArchiveTunePoTokenRequestKey"),
            googleApiKey = iosBuildSetting("ArchiveTunePoTokenGoogleApiKey"),
        ).takeIf { it.isConfigured }

    /**
     * The minted pair for one [videoId] resolve under the transport session, as the shipped
     * `PlaybackAuthState` binds them: the session token on the `dataSyncId` user session when
     * signed in, the visitor data otherwise; the player token on the video id.
     */
    internal suspend fun mintForPlayback(videoId: String, session: InnerTube.SessionSnapshot): MintedPoTokens? {
        if (!enabled()) return null
        val signedIn = session.sapisid?.isNotBlank() == true
        val hasLoginContext = signedIn && !session.dataSyncId.isNullOrBlank()
        val sessionId = poTokenSessionId(session.dataSyncId, session.visitorData, hasLoginContext) ?: return null
        return engine.mintToken(identifier = videoId, sessionId = sessionId)
    }

    /**
     * The shipped preWarm, mirroring the shipped app's warm on first auth state: the session id
     * comes from the live transport session exactly like [mintForPlayback] binds it. A no-op while
     * the poToken preference is off or the session has nothing to bind yet.
     */
    suspend fun preWarm() {
        if (!enabled()) return
        val session = transport.innerTubeSession.sessionSnapshot()
        val signedIn = session.sapisid?.isNotBlank() == true
        val sessionId =
            poTokenSessionId(
                session.dataSyncId,
                session.visitorData,
                hasLoginContext = signedIn && !session.dataSyncId.isNullOrBlank(),
            ) ?: return
        engine.preWarm(sessionId)
    }

    /** Drops the QuickJS runtime and the token caches, if a session ever minted. */
    suspend fun close() {
        if (engineRef.isInitialized()) engine.invalidate()
    }

    /**
     * The settings "regenerate" action, mirroring `PoTokenExtractionActivity` without a WebView:
     * the visitor data comes from the persisted session (or one network fetch, like the recipe's
     * own page), the GVS token binds to the session id and the player token to the literal
     * `player` identifier the shipped page mints. The persisted source URL stays informational —
     * the headless recipe needs no WebView destination.
     */
    suspend fun generate(preferences: PreferenceSnapshot?): SettingsPoTokens? {
        if (credentials()?.isConfigured != true) return null
        transport.refreshSession(preferences)
        val session = transport.innerTubeSession.sessionSnapshot()
        val signedIn = session.sapisid?.isNotBlank() == true
        val hasLoginContext = signedIn && !session.dataSyncId.isNullOrBlank()
        val visitor =
            session.visitorData?.takeIf(String::isNotBlank)
                ?: try {
                    fetchYouTubeVisitorData(http)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                ?: return null
        // tokenBindingId = sessionId ?: visitorData, as the Android extraction activity binds it.
        val sessionId =
            poTokenSessionId(session.dataSyncId, visitor, hasLoginContext) ?: visitor
        return engine.mintForSettings(tokenBindingId = sessionId, visitorData = visitor)
    }

    /**
     * The InnerTubeX [TokenProvider] the extractor's client director consults. It names
     * `WEB_BOTGUARD` only while minting is enabled and configured; the returned pair follows the
     * library's binding contract — `playerRequestToken` on the asked visitor data,
     * `streamingDataToken` on the video id.
     */
    internal val tokenProvider: TokenProvider =
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

    private companion object {
        /** The mint allowance a bounded extractor tier grants, leaving the rest to extraction. */
        const val MINT_BUDGET_MS = 3_000L
    }
}
