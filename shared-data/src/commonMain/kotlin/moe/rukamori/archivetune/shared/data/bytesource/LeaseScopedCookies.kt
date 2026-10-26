package moe.rukamori.archivetune.shared.data.bytesource

import moe.rukamori.archivetune.shared.domain.platform.AccountAdmission
import moe.rukamori.archivetune.shared.domain.platform.ProviderIdentity

/**
 * A cookie bound to the account and origin that issued it; it must never travel elsewhere. A row read from the
 * store carries the entry scope it was read for, so it matches only the one entry that holds it.
 */
data class ScopedCookie(
    val account: ByteSourceAccount,
    val origin: StreamOrigin,
    val name: String,
    val value: String,
)

/** Outcome of a redirect hop. [carriesCredentials] tells the caller whether cookies may follow. */
data class RedirectDecision(
    val allowed: Boolean,
    val carriesCredentials: Boolean,
)

/** Decides which stored cookies a lease may send and whether a redirect may proceed. */
object LeaseCookiePolicy {
    /**
     * Cookies that may be attached to a request to [requestOrigin] under [lease]: only cookies whose
     * account equals lease.account AND whose origin equals requestOrigin, and only when requestOrigin
     * is permitted by the lease. Returns an empty list otherwise.
     */
    fun cookiesFor(
        lease: ByteSourceLease,
        requestOrigin: StreamOrigin,
        stored: Collection<ScopedCookie>,
    ): List<ScopedCookie> {
        if (requestOrigin !in lease.permittedOrigins) return emptyList()
        return stored.filter { it.account == lease.account && it.origin == requestOrigin }
    }

    /**
     * A redirect is allowed only to a permitted origin and never from https to http. Credentials
     * continue only on the same origin; any cross-origin hop strips them.
     */
    fun redirect(
        lease: ByteSourceLease,
        from: StreamOrigin,
        to: StreamOrigin,
    ): RedirectDecision {
        if (to !in lease.permittedOrigins) return RedirectDecision(allowed = false, carriesCredentials = false)
        if (from.scheme == "https" && to.scheme == "http") {
            return RedirectDecision(allowed = false, carriesCredentials = false)
        }
        return RedirectDecision(allowed = true, carriesCredentials = to == from)
    }
}

/**
 * The account store's read of the cookies that one stored credential entry holds for one origin. [account] names the
 * provider's real user id and the single entry that admitted it, through [ByteSourceAccount.accountScope]. The store
 * returns only rows that entry stored, and none when that scope is absent, so two pool entries of one real user never
 * share cookies. The store is the vault-backed account owner that the host supplies, so no portable owner keeps a
 * cookie jar. Lease code never reads cookies directly; it goes through [cookiesForLease], which applies
 * [LeaseCookiePolicy].
 */
interface ScopedCookieSource {
    /**
     * Cookies stored for the entry that [account] names, under [origin]. Empty when the account is anonymous, carries
     * no entry scope, is signed out, or has no cookies there. Each returned row carries [account], scope included.
     */
    suspend fun storedCookies(
        account: ByteSourceAccount,
        origin: StreamOrigin,
    ): List<ScopedCookie>
}

/**
 * The cookies that [lease] may send with a request to [requestOrigin]. An origin the lease does not permit
 * reads nothing from the store, so a refused hop never touches account material.
 */
suspend fun ScopedCookieSource.cookiesForLease(
    lease: ByteSourceLease,
    requestOrigin: StreamOrigin,
): List<ScopedCookie> {
    if (requestOrigin !in lease.permittedOrigins) return emptyList()
    // A signed-in lease must name the entry it was admitted under. Without that scope the store would have to guess
    // between a personal slot and a pool entry of the same user, so the read stops here and touches no account data.
    if (lease.account.accountId != null && lease.account.accountScope == null) return emptyList()
    return LeaseCookiePolicy.cookiesFor(lease, requestOrigin, storedCookies(lease.account, requestOrigin))
}

/**
 * The byte-stream account for an accepted provider identity. A lease and its cookie rows must name the same account,
 * so this is the one place where the two shapes are joined. Pass the entry scope that admitted the identity. Leave it
 * null only for cookie rows that are written before any entry is known, because the store binds the scope on read.
 */
fun ProviderIdentity.toByteSourceAccount(accountScope: String? = null): ByteSourceAccount =
    ByteSourceAccount(provider = provider, accountId = accountId, accountScope = accountScope)

/** The byte-stream account of an admission: the admitted identity, bound to the entry scope that admitted it. */
fun AccountAdmission.toByteSourceAccount(): ByteSourceAccount = accepted.identity.toByteSourceAccount(accountScope)
