/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.platform

enum class CredentialScope { PERSONAL, SPOTIFY_CATALOG, SPOTIFY_STREAMING, PLAYBACK_POOL }

data class CredentialKey(val provider: String, val scope: CredentialScope, val name: String) {
    init { require(provider.isNotBlank() && name.isNotBlank()) }
}

/** No plaintext fallback. An unavailable OS vault throws; callers surface setup/retry. */
interface SecureCredentialStore {
    suspend fun read(key: CredentialKey): String?
    suspend fun write(key: CredentialKey, value: String)
    suspend fun remove(key: CredentialKey)
}

data class BrowserAuthRequest(
    val provider: String,
    val scope: CredentialScope,
    val authorizationUrl: String,
    val redirectUri: String,
    val state: String,
)

class BrowserAuthResult(val callbackUri: String, val state: String) {
    override fun toString(): String = "BrowserAuthResult(<redacted>)"
}

interface BrowserAuthPort {
    /** Separate cookie/storage profiles per provider AND scope. Cancellation closes only this attempt. */
    suspend fun authorize(request: BrowserAuthRequest): BrowserAuthResult
    suspend fun clearProfile(provider: String, scope: CredentialScope)
}

/** Opaque platform-selected file/bookmark handle, not assumed to be a JVM path. */
data class FileHandle(val value: String)

interface PlatformFiles {
    suspend fun read(file: FileHandle, maxBytes: Long): ByteArray
    /** Atomic replacement, or an error leaving the old file intact. */
    suspend fun writeAtomic(file: FileHandle, bytes: ByteArray)
    suspend fun delete(file: FileHandle)
}

interface ChallengeEvaluator {
    /** Isolated runtime, no network/files; enforce time, memory and output bounds natively. */
    suspend fun evaluate(script: String, input: String, timeoutMs: Long, maxMemoryBytes: Long): String
}

/*
 * Cookie-only native login (additive). M8/M9 implement the browser side of [CookieOnlyBrowserAuthPort]; the
 * account owner (M6 settings vault plus integration) drives verification and [ProviderAccountCommitPort].
 * [BrowserAuthPort] above is unchanged, so existing redirect-based implementers keep compiling. Nothing in
 * this block publishes a cookie to a global client, settings value, log or archive: cookie values live only
 * inside one attempt-owned [PrivateReusableContext] until that context is consumed or closed.
 */

/** Redacts a provider secret everywhere except the one explicit [reveal] at its consumer. */
class PlatformSecret(private val value: String) {
    /** Empty values are allowed: browsers do store cookies with empty values. */
    fun reveal(): String = value
    override fun toString(): String = "PlatformSecret(REDACTED)"
}

/** An HTTPS origin an attempt may navigate to or read cookies from. Every other origin is refused natively. */
data class AuthorizedHttpsOrigin(val host: String, val port: Int = 443) {
    init {
        require(host.isNotBlank() && host.none { it == '/' || it == ':' || it == '@' || it.isWhitespace() })
        require(port in 1..65_535)
    }

    /** Whether a cookie domain, with or without a leading dot, applies to this host. */
    fun accepts(cookieDomain: String): Boolean {
        val domain = cookieDomain.removePrefix(".").lowercase()
        val target = host.lowercase()
        return target == domain || target.endsWith(".$domain")
    }
}

/** One cookie the native attempt read for an authorized origin. [value] stays redacted in diagnostics. */
class PrivateCookie(
    val name: String,
    val value: PlatformSecret,
    val domain: String,
    val path: String,
    /** Epoch milliseconds, or null for a session cookie. */
    val expiresAtUnixMillis: Long?,
    val secure: Boolean,
    val httpOnly: Boolean,
    val sameSite: String?,
) {
    init {
        require(name.isNotBlank() && domain.isNotBlank() && path.startsWith("/"))
    }

    override fun toString(): String = "PrivateCookie(name=$name, domain=$domain, value=REDACTED)"
}

/**
 * The attempt-owned login context. It keeps only cookies that apply to its authorized origins, is consumed by
 * one verifier or commit, and is closed when the attempt ends. It is never a shared HTTP client or cookie jar.
 */
class PrivateReusableContext(
    val provider: String,
    val scope: CredentialScope,
    /** Monotonic per provider and scope. A completion from an older generation must never be committed. */
    val generation: Long,
    private val authorizedOrigins: Set<AuthorizedHttpsOrigin>,
    cookies: List<PrivateCookie>,
) {
    private var cookieRows: List<PrivateCookie>? =
        cookies.filter { cookie -> authorizedOrigins.any { it.accepts(cookie.domain) } }

    init {
        require(provider.isNotBlank() && generation >= 0 && authorizedOrigins.isNotEmpty())
    }

    /** Cookies that apply to [origin]. Asking for an origin the attempt was not authorized for is a caller bug. */
    fun cookiesFor(origin: AuthorizedHttpsOrigin): List<PrivateCookie> {
        check(origin in authorizedOrigins) { "origin is not authorized for this login attempt" }
        val rows = checkNotNull(cookieRows) { "login context is closed" }
        return rows.filter { origin.accepts(it.domain) }
    }

    /** Releases the cookie material. Idempotent. */
    fun close() {
        cookieRows = null
    }

    override fun toString(): String = "PrivateReusableContext($provider, $scope, generation=$generation, REDACTED)"
}

/** A login attempt for one provider and scope. Its generation orders attempts; the newest one wins. */
data class CookieOnlyLoginRequest(
    val provider: String,
    val scope: CredentialScope,
    /** HTTPS start page for the attempt. */
    val startUrl: String,
    /** The only origins the attempt may navigate to and read cookies from. */
    val authorizedOrigins: Set<AuthorizedHttpsOrigin>,
    val generation: Long,
) {
    init {
        require(provider.isNotBlank() && startUrl.startsWith("https://"))
        require(authorizedOrigins.isNotEmpty() && generation >= 0)
    }
}

/** How one attempt ended. Only [Completed] carries cookies, and only inside its private context. */
sealed interface CookieOnlyLoginOutcome {
    /** The caller now owns [context] and must consume it or close it. */
    class Completed(val context: PrivateReusableContext) : CookieOnlyLoginOutcome

    /** The user or a newer attempt closed this one. Its context was released and nothing was committed. */
    data object Cancelled : CookieOnlyLoginOutcome

    /** No usable context was produced. [failure] is a stable code, never page content. */
    data class Failed(val failure: CookieOnlyLoginFailure) : CookieOnlyLoginOutcome
}

enum class CookieOnlyLoginFailure { BROWSER_UNAVAILABLE, NAVIGATION_REFUSED, TIMED_OUT, CONTEXT_UNAVAILABLE }

/**
 * Native cookie-only login. One call owns one isolated browser context for (provider, scope). Cancelling the
 * calling coroutine closes only that attempt, and other providers or scopes keep their own profiles.
 */
interface CookieOnlyBrowserAuthPort {
    suspend fun login(request: CookieOnlyLoginRequest): CookieOnlyLoginOutcome
    suspend fun clearProfile(provider: String, scope: CredentialScope)
}

/** A provider account as the provider's own API reports it. Display names never identify an account. */
data class ProviderIdentity(val provider: String, val scope: CredentialScope, val accountId: String) {
    init {
        require(provider.isNotBlank() && accountId.isNotBlank())
    }

    /** Cross-scope check: the same provider account signed into two scopes, such as Spotify catalog and streaming. */
    fun isSameProviderAccount(other: ProviderIdentity): Boolean =
        provider == other.provider && accountId == other.accountId
}

/**
 * Asks the provider who a private context belongs to, using only that context. A verifier writes nothing and
 * caches nothing. If it throws, the currently accepted account stays unchanged.
 */
interface ProviderIdentityVerifier {
    suspend fun verify(context: PrivateReusableContext): ProviderIdentity
}

/**
 * A verified candidate that exists only in memory until [ProviderAccountCommitPort.commit] accepts it. [values]
 * maps logical credential names, as used by the settings vault routes, to their secret values.
 */
class PendingProviderAccount(
    val identity: ProviderIdentity,
    val generation: Long,
    val values: Map<String, PlatformSecret>,
) {
    init {
        require(generation >= 0 && values.isNotEmpty())
    }

    override fun toString(): String = "PendingProviderAccount(${identity.provider}, ${identity.scope}, REDACTED)"
}

enum class ProviderAccountCommitFailure { VAULT_UNAVAILABLE, STORAGE_REJECTED }

sealed interface ProviderAccountCommitResult {
    /** The candidate became the accepted account in one transaction. */
    data class Accepted(val identity: ProviderIdentity) : ProviderAccountCommitResult

    /** The identity is already accepted under another scope of the same provider. The candidate is discarded. */
    data class SameAccountRejected(val identity: ProviderIdentity) : ProviderAccountCommitResult

    /** A newer attempt for this provider and scope exists. Nothing was written. */
    data object StaleGeneration : ProviderAccountCommitResult

    data class Failed(val failure: ProviderAccountCommitFailure) : ProviderAccountCommitResult
}

/**
 * The single owner of the pending-to-accepted transition. An implementation writes every value of the candidate
 * together with its identity, or none of them, and checks the generation and cross-scope same-account rules inside
 * that same transaction. A failed commit leaves the previously accepted account unchanged.
 */
interface ProviderAccountCommitPort {
    suspend fun commit(pending: PendingProviderAccount): ProviderAccountCommitResult
}
