package moe.rukamori.archivetune.shared.data.potoken

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** The build-time secrets the BotGuard `Create`/`GenerateIT` requests authenticate with. */
class PoTokenCredentials(
    val requestKey: String,
    val googleApiKey: String,
) {
    val isConfigured: Boolean get() = requestKey.isNotBlank() && googleApiKey.isNotBlank()
    override fun toString(): String = "PoTokenCredentials(configured=$isConfigured)"
}

/** The two tokens one BotGuard session produces: [playerToken] bound to an identifier, [sessionToken] bound to the session id. */
class MintedPoTokens(
    val playerToken: String,
    val sessionToken: String,
) {
    override fun toString(): String = "MintedPoTokens(present)"
}

/** The complete values the settings page persists after one generation. */
class SettingsPoTokens(
    val visitorData: String,
    val gvsToken: String,
    val playerToken: String,
) {
    val isComplete: Boolean get() = visitorData.isNotBlank() && gvsToken.isNotBlank() && playerToken.isNotBlank()
    override fun toString(): String = "SettingsPoTokens(complete=$isComplete)"
}

/**
 * The narrow JavaScript surface the engine needs from the host's QuickJS runtime. [evaluate] awaits the
 * evaluated promise to a string; [evaluateUnit] discards the result. One instance is one BotGuard session:
 * the challenge interpreter, webPoSignalOutput and minter live on it, so minting stays on that runtime
 * until it expires or fails. A QuickJS instance is thread-confined and its evaluation runs on the
 * calling thread, so the implementation performs every call, [close] included, on the single worker
 * dispatcher it owns rather than on the caller's.
 */
interface PoTokenJsRuntime {
    suspend fun evaluate(code: String): String
    suspend fun evaluateUnit(code: String)
    suspend fun close()
}

/**
 * The shared counterpart of Android's `BotGuardTokenGenerator`: it runs YouTube's BotGuard challenge
 * inside the host QuickJS runtime over a stubbed browser environment and mints poTokens with the same
 * Create -> challenge -> GenerateIT -> minter -> per-identifier flow the WebView recipe uses.
 *
 * Session tokens are bound to a session id (the `dataSyncId` user session when signed in, otherwise the
 * visitor data) and player tokens to a per-request identifier (the video id, or the literal `player`
 * the settings extraction mints). The engine keeps one runtime per session id, replays the Android
 * timeouts (45 s cold, 5 s warm) and cache (200 identifiers), and stays honestly null whenever the
 * build carries no BotGuard credentials or the runtime cannot satisfy the recipe.
 */
class BotGuardPoTokenEngine(
    private val http: HttpClient,
    private val credentials: () -> PoTokenCredentials?,
    private val runtimeFactory: suspend () -> PoTokenJsRuntime,
    private val report: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private var runtime: PoTokenJsRuntime? = null
    private var engineSessionId: String? = null
    private var cachedSessionToken: String? = null
    private var expiryEpochMs: Long = 0L
    private var engineReady = false
    private var permanentlyBroken = false

    // Insertion-ordered and bounded like the shipped 200-entry player-token LRU.
    private val identifierCache = LinkedHashMap<String, String>()

    private fun cacheIdentifier(identifier: String, token: String) {
        identifierCache.remove(identifier)
        identifierCache[identifier] = token
        while (identifierCache.size > IDENTIFIER_CACHE_SIZE) {
            identifierCache.remove(identifierCache.entries.first().key)
        }
    }

    /** A token pair for [identifier] under [sessionId], or null when unconfigured/broken/failed. */
    suspend fun mintToken(identifier: String, sessionId: String): MintedPoTokens? {
        if (credentials()?.isConfigured != true) return null
        if (permanentlyBroken || sessionId.isBlank()) return null

        return mutex.withLock {
            if (isEngineReadyFor(sessionId)) {
                val cached = identifierCache[identifier]
                val sessionToken = cachedSessionToken
                if (cached != null && sessionToken != null) {
                    return@withLock MintedPoTokens(playerToken = cached, sessionToken = sessionToken)
                }
            }
            val timeout = if (isEngineReadyFor(sessionId)) WARM_TIMEOUT_MS else COLD_START_TIMEOUT_MS
            try {
                withTimeout(timeout) {
                    mintTokenLocked(identifier, sessionId).also { cacheIdentifier(identifier, it.playerToken) }
                }
            } catch (timeout: TimeoutCancellationException) {
                report("PoToken mint timed out after ${timeout}ms")
                destroyEngineLocked()
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (broken: BrokenPoTokenRuntimeException) {
                report("PoToken runtime permanently broken: ${broken.message}")
                permanentlyBroken = true
                destroyEngineLocked()
                null
            } catch (failure: Exception) {
                report("PoToken mint failed: ${failure::class.simpleName}: ${failure.message}")
                null
            }
        }
    }

    /**
     * The settings-extraction mint, mirroring `PoTokenExtractionActivity`: the player token is bound to
     * the literal identifier `player`, the GVS token to [tokenBindingId] (the session id, or the visitor
     * data when anonymous), and [visitorData] is the value the page persists.
     */
    suspend fun mintForSettings(tokenBindingId: String, visitorData: String): SettingsPoTokens? {
        val minted = mintToken(identifier = "player", sessionId = tokenBindingId) ?: return null
        return SettingsPoTokens(
            visitorData = visitorData,
            gvsToken = minted.sessionToken,
            playerToken = minted.playerToken,
        ).takeIf { it.isComplete }
    }

    /** Keeps the engine warm for [sessionId]; best-effort, every failure is swallowed like Android's preWarm. */
    suspend fun preWarm(sessionId: String) {
        if (credentials()?.isConfigured != true || permanentlyBroken || sessionId.isBlank()) return
        try {
            withTimeout(COLD_START_TIMEOUT_MS) { mutex.withLock { getOrCreateEngineLocked(sessionId) } }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        }
    }

    /** Drops the runtime, the session token and every cached identifier token. */
    suspend fun invalidate() {
        mutex.withLock { destroyEngineLocked() }
    }

    private fun isEngineReadyFor(sessionId: String): Boolean =
        engineReady && engineSessionId == sessionId && cachedSessionToken != null &&
            expiryEpochMs > Clock.System.now().toEpochMilliseconds()

    private suspend fun mintTokenLocked(identifier: String, sessionId: String): MintedPoTokens {
        val (rt, sessionToken, wasNew) = getOrCreateEngineLocked(sessionId)
        val playerToken =
            try {
                mintOn(rt, identifier)
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                if (wasNew) throw failure
                val (freshRuntime, freshSessionToken) = getOrCreateEngineLocked(sessionId, forceNew = true)
                return MintedPoTokens(playerToken = mintOn(freshRuntime, identifier), sessionToken = freshSessionToken)
            }
        return MintedPoTokens(playerToken = playerToken, sessionToken = sessionToken)
    }

    private suspend fun getOrCreateEngineLocked(
        sessionId: String,
        forceNew: Boolean = false,
    ): Triple<PoTokenJsRuntime, String, Boolean> {
        val needsNew = forceNew || !isEngineReadyFor(sessionId)
        if (needsNew) {
            destroyEngineLocked()
            identifierCache.clear()
            val newRuntime = createBootstrappedRuntime(sessionId)
            runtime = newRuntime.runtime
            engineSessionId = sessionId
            cachedSessionToken = newRuntime.sessionToken
            expiryEpochMs = newRuntime.expiryEpochMs
            engineReady = true
        }
        return Triple(
            requireNotNull(runtime),
            requireNotNull(cachedSessionToken),
            needsNew,
        )
    }

    private class BootstrappedRuntime(
        val runtime: PoTokenJsRuntime,
        val sessionToken: String,
        val expiryEpochMs: Long,
    )

    /**
     * One full bootstrap: preamble, `Create`, challenge, `GenerateIT`, minter, session mint — the same
     * order `BotGuardEngine` walks in the WebView. Any failure closes the runtime.
     */
    private suspend fun createBootstrappedRuntime(sessionId: String): BootstrappedRuntime {
        val creds = credentials()?.takeIf { it.isConfigured }
            ?: throw PoTokenMintException("BotGuard credentials are not configured")
        val rt = try {
            runtimeFactory()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw BrokenPoTokenRuntimeException("QuickJS runtime cannot start")
        }
        try {
            rt.evaluateUnit(BOTGUARD_ENV_PROGRAM)

            val createBody = postToBotGuard(BOTGUARD_CREATE_URL, "[ ${poTokenJsString(creds.requestKey)} ]", creds)
            val challenge = parseCreateChallenge(createBody)
            if (challenge.interpreterJavascript == null) {
                // The interpreter-URL form needs a page fetch the recipe never performs, same refusal as the WebView.
                throw PoTokenMintException("BotGuard challenge carried no interpreter script")
            }
            val botguardResponse =
                rt.evaluate("__bgRunBotGuard(JSON.parse(${poTokenJsString(challenge.toDriverJson())}))")

            val generateItBody =
                postToBotGuard(
                    BOTGUARD_GENERATE_IT_URL,
                    "[ ${poTokenJsString(creds.requestKey)}, ${poTokenJsString(botguardResponse)} ]",
                    creds,
                )
            val integrityToken = parseIntegrityToken(generateItBody)

            val minterState = rt.evaluate("__bgCreateMinter(${bytesToJsUint8Array(integrityToken.tokenBytes)})")
            check(minterState == "ok") { "BotGuard minter answered $minterState" }

            val sessionToken = mintOn(rt, sessionId)
            val expiry =
                Clock.System.now().toEpochMilliseconds() +
                    integrityToken.lifetimeSeconds.seconds.inWholeMilliseconds -
                    EXPIRY_SKEW.inWholeMilliseconds
            return BootstrappedRuntime(rt, sessionToken, expiry)
        } catch (failure: Throwable) {
            try {
                rt.close()
            } catch (_: Throwable) {
            }
            if (failure is PoTokenMintException) throw failure
            if (failure is CancellationException) throw failure
            throw classifyJsFailure(failure)
        }
    }

    private suspend fun mintOn(rt: PoTokenJsRuntime, identifier: String): String =
        commaSeparatedBytesToPoToken(rt.evaluate("__bgMint(${identifierToJsUint8Array(identifier)})"))

    private suspend fun postToBotGuard(url: String, jsonBody: String, creds: PoTokenCredentials): String {
        val response =
            http.post(url) {
                header(HttpHeaders.UserAgent, BOTGUARD_USER_AGENT)
                header(HttpHeaders.Accept, "application/json")
                header("x-goog-api-key", creds.googleApiKey)
                header("x-user-agent", "grpc-web-javascript/0.1")
                contentType(ContentType.parse("application/json+protobuf"))
                setBody(jsonBody)
            }
        if (response.status.value != 200) {
            throw PoTokenMintException("BotGuard HTTP ${response.status.value} from $url")
        }
        return response.bodyAsText()
    }

    private suspend fun destroyEngineLocked() {
        val rt = runtime
        runtime = null
        engineSessionId = null
        cachedSessionToken = null
        expiryEpochMs = 0L
        engineReady = false
        rt?.let {
            try {
                it.close()
            } catch (_: Throwable) {
            }
        }
    }

    /** Like Android's `classifyJsError`: interpreter SyntaxErrors mark the engine permanently unusable. */
    private fun classifyJsFailure(failure: Throwable): Exception =
        if (failure.message?.contains("SyntaxError") == true) {
            BrokenPoTokenRuntimeException(failure.message ?: "BotGuard JS failed")
        } else {
            failure as? Exception ?: PoTokenMintException("BotGuard mint failed", failure)
        }

    companion object {
        private const val BOTGUARD_CREATE_URL = "https://www.youtube.com/api/jnn/v1/Create"
        private const val BOTGUARD_GENERATE_IT_URL = "https://www.youtube.com/api/jnn/v1/GenerateIT"
        private const val BOTGUARD_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"

        /** The shipped cold-start bound: engine bootstrap plus the first mint. */
        private const val COLD_START_TIMEOUT_MS = 45_000L

        /** The shipped warm bound: one mint on a live engine. */
        private const val WARM_TIMEOUT_MS = 5_000L
        private const val IDENTIFIER_CACHE_SIZE = 200
        private val EXPIRY_SKEW = 10.minutes
    }
}

/** The JS environment is fundamentally broken (interpreter SyntaxError, runtime boot failure). */
class BrokenPoTokenRuntimeException(message: String) : IllegalStateException(message)

/**
 * `resolvePlayerPoToken`/`resolveGvsPoToken` over one minted pair: a web-po client carries the
 * video-bound player token, then the session token; every other client stays untokened — the same
 * answer the shipped auth state gives when no per-video token exists.
 */
fun poTokenForWebClient(clientName: String, minted: MintedPoTokens?): String? =
    minted?.takeIf { supportsWebPoToken(clientName) }
        ?.let { it.playerToken.takeIf(String::isNotBlank) ?: it.sessionToken }
