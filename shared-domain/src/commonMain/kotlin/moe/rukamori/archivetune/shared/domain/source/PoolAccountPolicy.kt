package moe.rukamori.archivetune.shared.domain.source

/**
 * The Playback Pool rules. Pool credentials are shared, borrowed accounts. They are kept apart from
 * the user's own sign-ins, so the pool can be switched off without touching personal access.
 */
object PoolAccountPolicy {
    /** How long an account that just failed to resolve is tried last. */
    const val ACCOUNT_COOLDOWN_MS = 10 * 60 * 1000L

    /** The pool runs only when the user has not opted out and a provider URL is configured. */
    fun isPoolEnabled(poolAccountsEnabled: Boolean, providerUrl: String?): Boolean =
        poolAccountsEnabled && !providerUrl.isNullOrBlank()

    /** Pool accounts are visible only while the pool is enabled. Otherwise the list is empty. */
    fun <T> visibleAccounts(poolEnabled: Boolean, accounts: List<T>): List<T> =
        if (poolEnabled) accounts else emptyList()

    /**
     * The credentials a resolver may use. Personal credentials are always kept. Pool credentials are kept
     * only while the pool is enabled.
     */
    fun <T> credentialsInUse(poolEnabled: Boolean, credentials: List<T>, isPool: (T) -> Boolean): List<T> =
        credentials.filter { poolEnabled || !isPool(it) }

    /**
     * Replaces the pool credentials with a fresh feed and keeps every personal credential. A pool refresh
     * never overwrites the user's own sign-in.
     */
    fun <T> refreshedCredentials(current: List<T>, freshPool: List<T>, isPool: (T) -> Boolean): List<T> =
        current.filterNot(isPool) + freshPool
}

/**
 * Remembers which pool accounts recently failed. The memory is a demotion, not a removal. Every account
 * may be cooling down at once in a thin pool, and an empty list would drop straight to the lossy
 * YouTube path. Callers own the instance and serialize access to it.
 */
class PoolAccountCooldowns(private val nowMs: () -> Long) {
    /** "service:id" mapped to the time the account becomes worth trying first again. */
    private val coolingUntil = mutableMapOf<String, Long>()

    private fun key(service: String, id: Long?): String = "$service:${id ?: 0L}"

    fun noteFailure(service: String, id: Long?) {
        if (id == null || id <= 0L) return
        coolingUntil[key(service, id)] = nowMs() + PoolAccountPolicy.ACCOUNT_COOLDOWN_MS
    }

    /** A successful resolve clears any cooldown left by an earlier blip. */
    fun noteSuccess(service: String, id: Long?) {
        if (id == null || id <= 0L) return
        coolingUntil.remove(key(service, id))
    }

    fun isCoolingDown(service: String, id: Long?): Boolean {
        val key = key(service, id)
        val until = coolingUntil[key] ?: return false
        if (until > nowMs()) return true
        coolingUntil.remove(key)
        return false
    }

    /**
     * Accounts that are not cooling down come first, and premium comes before free within each group.
     * The sort is stable, so equal accounts keep their feed order.
     */
    fun <T> ordered(
        service: String,
        accounts: List<T>,
        idOf: (T) -> Long?,
        premiumOf: (T) -> Boolean,
    ): List<T> = accounts.sortedWith(compareBy({ isCoolingDown(service, idOf(it)) }, { !premiumOf(it) }))

    /** Premium first, with no cooldown consulted. Apple Music's pool ordering works this way. */
    fun <T> orderedByPremium(accounts: List<T>, premiumOf: (T) -> Boolean): List<T> =
        accounts.sortedByDescending(premiumOf)
}
