package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.domain.platform.PlatformSecret
import moe.rukamori.archivetune.shared.domain.platform.ProviderAccountCommitResult
import moe.rukamori.archivetune.shared.domain.platform.ProviderIdentity
import moe.rukamori.archivetune.shared.domain.settings.SettingsStore
import moe.rukamori.archivetune.shared.domain.settings.TidalSessionFacts
import moe.rukamori.archivetune.shared.domain.settings.clearDeezerAccountFacts
import moe.rukamori.archivetune.shared.domain.settings.clearQqMusicAccountFacts
import moe.rukamori.archivetune.shared.domain.settings.clearTidalSessionFacts
import moe.rukamori.archivetune.shared.domain.settings.commitDeezerAccountFacts
import moe.rukamori.archivetune.shared.domain.settings.commitQqMusicAccountFacts
import moe.rukamori.archivetune.shared.domain.settings.commitTidalSessionFacts
import moe.rukamori.archivetune.shared.domain.settings.normalizeDeezerArl

/**
 * One begun personal sign-in. It is taken before the provider is asked, so a verification that finishes after a newer
 * attempt, a refresh or a sign-out cannot commit. Abandoning it needs no call: nothing was written, and the next attempt
 * or sign-out starts above its generation.
 */
class ProviderSignInAttempt internal constructor(val provider: String, val generation: Long) {
    override fun toString(): String = "ProviderSignInAttempt($provider, $generation)"
}

/** How one sign-in commit ended. Only [Accepted] leaves an account connected. */
enum class ProviderSignInOutcome {
    /** The verified account, its credentials and its display facts are saved. */
    Accepted,

    /** The provider did not name an account, so nothing was saved. */
    Unverified,

    /** A newer attempt, refresh or sign-out exists for this account slot. Nothing was saved. */
    Stale,

    /** The account is already signed in under another scope that refuses a second copy. Nothing was saved. */
    SameAccountRejected,

    /** The vault refused the write. The previous account, if any, stays as it was. */
    NotSaved,
}

/**
 * The personal sign-in lifecycle of the TIDAL, Deezer and QQ Music settings pages: begin, verify, then commit or abandon,
 * and sign-out. Credentials and the verified identity go through [accounts] in one vault edit, so the leases that
 * playback reads are issued only for a verified account and stop at once on sign-out or replacement. The display facts
 * that the pages show follow in a second edit of [settings], and only after the account commit was accepted.
 */
class ProviderAccountLifecycle(
    private val accounts: VaultProviderAccountStore,
    private val settings: SettingsStore,
) {
    suspend fun begin(provider: String): ProviderSignInAttempt =
        ProviderSignInAttempt(provider, accounts.beginAttempt(provider, CredentialScope.PERSONAL))

    /**
     * Commits a TIDAL session that the host checked with TIDAL. The user id is the account identity; a session without one
     * is refused. The refresh token is kept only when the flow issued one, since a captured web bearer has none.
     */
    suspend fun commitTidal(attempt: ProviderSignInAttempt, session: TidalSessionFacts): ProviderSignInOutcome {
        require(attempt.provider == TIDAL) { "Attempt belongs to another provider" }
        val userId = session.userId?.takeIf { it > 0L } ?: return ProviderSignInOutcome.Unverified
        if (session.accessToken.isBlank()) return ProviderSignInOutcome.Unverified
        val credentials = buildMap {
            put("tidalAccessToken", PlatformSecret(session.accessToken))
            session.refreshToken.takeIf { it.isNotBlank() }?.let { put("tidalRefreshToken", PlatformSecret(it)) }
        }
        return commit(attempt, userId.toString(), credentials) { settings.edit { commitTidalSessionFacts(session) } }
    }

    /** Commits an ARL that the host checked with the Deezer gateway, as the account [userId] reported there. */
    suspend fun commitDeezer(
        attempt: ProviderSignInAttempt,
        userId: Long,
        arl: String,
        accountName: String,
        premium: Boolean,
    ): ProviderSignInOutcome {
        require(attempt.provider == DEEZER) { "Attempt belongs to another provider" }
        val normalized = normalizeDeezerArl(arl)
        if (userId <= 0L || normalized.isBlank() || accountName.isBlank()) return ProviderSignInOutcome.Unverified
        return commit(attempt, userId.toString(), mapOf("deezerArl" to PlatformSecret(normalized))) {
            settings.edit { commitDeezerAccountFacts(accountName, premium) }
        }
    }

    /**
     * Commits a finished QQ Music sign-in. QQ offers no profile endpoint that verifies a ticket, so the account is the
     * musicid that the login itself returned, as on Android; "0" and non-digits are refused.
     */
    suspend fun commitQqMusic(
        attempt: ProviderSignInAttempt,
        uin: String,
        musicKey: String,
        nickname: String?,
        refreshCredential: String?,
    ): ProviderSignInOutcome {
        require(attempt.provider == QQ) { "Attempt belongs to another provider" }
        val account = uin.trim()
        val ticket = musicKey.trim()
        if (account.isEmpty() || account == "0" || account.any { it !in '0'..'9' } || ticket.isEmpty()) {
            return ProviderSignInOutcome.Unverified
        }
        val credentials = buildMap {
            put("qqMusicMusickey", PlatformSecret(ticket))
            refreshCredential?.takeIf { it.isNotBlank() }?.let { put("qqMusicCredential", PlatformSecret(it)) }
        }
        return commit(attempt, account, credentials) { settings.edit { commitQqMusicAccountFacts(account, nickname) } }
    }

    /**
     * Signs [provider] out: the identity, credentials and cookie rows go in one vault edit, every earlier attempt is
     * refused, and the display facts are cleared after it.
     */
    suspend fun signOut(provider: String) {
        accounts.signOut(provider, CredentialScope.PERSONAL)
        settings.edit {
            when (provider) {
                TIDAL -> clearTidalSessionFacts()
                DEEZER -> clearDeezerAccountFacts()
                QQ -> clearQqMusicAccountFacts()
            }
        }
    }

    private suspend fun commit(
        attempt: ProviderSignInAttempt,
        accountId: String,
        credentials: Map<String, PlatformSecret>,
        facts: suspend () -> Unit,
    ): ProviderSignInOutcome {
        val identity = ProviderIdentity(attempt.provider, CredentialScope.PERSONAL, accountId)
        val pending = providerAccountPending(identity, attempt.generation, credentials, cookies = emptyList())
        return when (accounts.commit(pending)) {
            is ProviderAccountCommitResult.Accepted -> {
                facts()
                ProviderSignInOutcome.Accepted
            }
            is ProviderAccountCommitResult.SameAccountRejected -> ProviderSignInOutcome.SameAccountRejected
            ProviderAccountCommitResult.StaleGeneration -> ProviderSignInOutcome.Stale
            is ProviderAccountCommitResult.Failed -> ProviderSignInOutcome.NotSaved
        }
    }

    companion object {
        const val TIDAL = "tidal"
        const val DEEZER = "deezer"
        const val QQ = "qq"
    }
}
