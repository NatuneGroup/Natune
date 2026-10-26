package moe.rukamori.archivetune.shared.domain.platform

/**
 * The account scope of genuinely anonymous access, such as a public instance that signs nothing in. No account slot is
 * named by it, so admission never runs for it. It is never a fallback for a personal scope that is missing: the caller
 * refuses that case, because a personal stream read anonymously would be a silent downgrade.
 */
const val ANONYMOUS_ACCOUNT_SCOPE: String = "public"

/**
 * The accepted account behind one account scope: who it is, and the generation that its commit wrote. A later commit
 * for the same scope always carries a higher generation, so an older admission never matches a newer account.
 */
data class AcceptedAccount(
    val identity: ProviderIdentity,
    val generation: Long,
) {
    init {
        require(generation >= 0) { "an accepted generation is non-negative" }
    }
}

/**
 * Read-only access to the accepted-account store. Native openers use it to re-check an account when they open.
 *
 * The store is the same one that performs [ProviderAccountCommitPort.commit], so a read sees the state that the last
 * commit wrote and never a half-written candidate. Reads never write and never return credential values. The store
 * decides how an account scope string names its credential slot; callers pass the scope through unchanged.
 */
interface AcceptedAccountReader {
    /** The account accepted under [accountScope] for [provider], or null when no account is accepted there. */
    suspend fun acceptedAccount(
        provider: String,
        accountScope: String,
    ): AcceptedAccount?

    /**
     * Captures the accepted account at prepare time, so that a later open can compare against it.
     * Returns null when no account is accepted, because there is then nothing to admit.
     */
    suspend fun admit(
        provider: String,
        accountScope: String,
    ): AccountAdmission? =
        acceptedAccount(provider, accountScope)
            ?.takeIf { it.identity.provider == provider }
            ?.let { AccountAdmission(accountScope = accountScope, accepted = it) }
}

/**
 * The account a native recipe was prepared under. [accountScope] is the scope the recipe carries, and [accepted] is
 * the account observed when the recipe was prepared. An opener compares both again before it reads, so an account
 * that was signed out or replaced after preparation stops being readable, though the recipe itself is unchanged.
 */
data class AccountAdmission(
    val accountScope: String,
    val accepted: AcceptedAccount,
) {
    init {
        require(accountScope.isNotBlank()) { "an admitted account needs its account scope" }
    }

    /** The provider the account belongs to, read from its identity so the two can never disagree. */
    val provider: String
        get() = accepted.identity.provider
}
