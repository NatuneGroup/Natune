package moe.rukamori.archivetune.desktop.provider

import java.util.UUID
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceAccount
import moe.rukamori.archivetune.shared.data.bytesource.ScopedCookie
import moe.rukamori.archivetune.shared.data.bytesource.ScopedCookieSource
import moe.rukamori.archivetune.shared.data.bytesource.StreamOrigin
import moe.rukamori.archivetune.shared.domain.platform.AcceptedAccount
import moe.rukamori.archivetune.shared.domain.platform.AcceptedAccountReader
import moe.rukamori.archivetune.shared.domain.platform.AccountAdmission
import moe.rukamori.archivetune.shared.domain.platform.ProviderIdentity

/**
 * Runtime admissions for verified legacy credential lists, without changing their persisted format or personal slots.
 * A scope names one captured credential, not its position in a list; removing/replacing it invalidates every read.
 */
internal class DesktopSourceAdmissions(
    private val accounts: AcceptedAccountReader,
    private val cookies: ScopedCookieSource,
) : AcceptedAccountReader, ScopedCookieSource {
    internal class Lease(
        val scope: String,
        val account: AcceptedAccount,
        val credential: Any,
        val current: () -> Boolean,
        val urlAllowed: (String) -> Boolean = { true },
    )

    private val lock = Any()
    private val leases = LinkedHashMap<String, Lease>()
    private var closed = false
    private var generation = 0L

    fun accept(identity: ProviderIdentity, credential: Any, current: () -> Boolean): Lease? =
        acceptPermitted(identity, credential, { true }, current)

    fun acceptPermitted(identity: ProviderIdentity, credential: Any, urlAllowed: (String) -> Boolean,
        current: () -> Boolean): Lease? = synchronized(lock) {
        if (closed || !current()) return@synchronized null
        leases.entries.removeIf { !it.value.current() }
        leases.values.firstOrNull {
            it.account.identity == identity && it.credential == credential && it.current()
        }?.let { return@synchronized it }
        // Never evict an active account: an already-playing lease may still need it.
        if (leases.size >= MAX_ACCOUNTS) return@synchronized null
        val scope = PREFIX + UUID.randomUUID()
        Lease(scope, AcceptedAccount(identity, ++generation), credential, current, urlAllowed).also { leases[scope] = it }
    }

    fun permitsUrl(scope: String, url: String): Boolean = synchronized(lock) {
        if (!scope.startsWith(PREFIX)) return@synchronized true
        val lease = leases[scope] ?: return@synchronized false
        active(lease) && runCatching { lease.urlAllowed(url) }.getOrDefault(false)
    }

    fun active(lease: Lease): Boolean = synchronized(lock) {
        if (closed || leases[lease.scope] !== lease) return@synchronized false
        if (lease.current()) return@synchronized true
        leases.remove(lease.scope)
        false
    }

    override suspend fun acceptedAccount(provider: String, accountScope: String): AcceptedAccount? {
        if (!accountScope.startsWith(PREFIX)) return accounts.acceptedAccount(provider, accountScope)
        return synchronized(lock) {
            val lease = leases[accountScope] ?: return@synchronized null
            if (active(lease) && lease.account.identity.provider == provider) lease.account else null
        }
    }

    override suspend fun admit(provider: String, accountScope: String): AccountAdmission? =
        if (!accountScope.startsWith(PREFIX)) accounts.admit(provider, accountScope)
        else acceptedAccount(provider, accountScope)?.let { AccountAdmission(accountScope, it) }

    override suspend fun storedCookies(account: ByteSourceAccount, origin: StreamOrigin): List<ScopedCookie> =
        // Qobuz's signed CDN URL and Spotify's PlayPlay CDN need no browser cookie. In particular the streaming
        // sp_dc never goes to an audio CDN, and the catalog jar cannot answer one of these scopes.
        if (account.accountScope?.startsWith(PREFIX) == true) emptyList() else cookies.storedCookies(account, origin)

    fun close() = synchronized(lock) {
        closed = true
        leases.clear()
    }

    private companion object {
        const val PREFIX = "desktop-source:"
        const val MAX_ACCOUNTS = 64
    }
}
