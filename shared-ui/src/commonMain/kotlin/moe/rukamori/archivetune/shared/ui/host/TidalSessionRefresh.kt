package moe.rukamori.archivetune.shared.ui.host

import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.shared.data.provider.TidalAccountAuth
import moe.rukamori.archivetune.shared.data.provider.TidalRefreshResult
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.SettingsStore
import moe.rukamori.archivetune.shared.domain.settings.TidalAuthFlowKey
import moe.rukamori.archivetune.shared.domain.settings.TidalNeedsReloginKey
import moe.rukamori.archivetune.shared.domain.settings.TidalTokenExpiryKey
import moe.rukamori.archivetune.shared.ui.settings.PersonalTidalLease
import moe.rukamori.archivetune.shared.ui.settings.ProviderAccountLifecycle
import moe.rukamori.archivetune.shared.ui.settings.ProviderSignInOutcome

/**
 * Refreshes the personal TIDAL session shortly before it expires, as the Android playback service does. The attempt is
 * begun before TIDAL is asked, so a sign-in or sign-out that lands meanwhile wins, and the refreshed session is committed
 * only when TIDAL names the same account. A refused token or a retired client marks the account for a new sign-in and
 * keeps the stored session; a missing client or a network failure changes nothing.
 */
class TidalSessionRefresh(
    private val auth: TidalAccountAuth,
    private val lifecycle: ProviderAccountLifecycle,
    private val settings: SettingsStore,
    private val preferences: () -> PreferenceSnapshot?,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val lock = Mutex()

    /** True when a refreshed session was committed, so the caller reads a new lease. */
    suspend fun refreshIfDue(lease: PersonalTidalLease): Boolean {
        if (!due()) return false
        val refreshToken = lease.refreshToken?.reveal()?.takeIf { it.isNotBlank() } ?: return false
        return lock.withLock {
            // Another resolve may have refreshed while this one waited; its commit already moved the expiry.
            if (!due()) return@withLock true
            val flow = preferences()?.get(TidalAuthFlowKey).orEmpty()
            val attempt = lifecycle.begin(ProviderAccountLifecycle.TIDAL)
            when (val result = auth.refresh(refreshToken, flow)) {
                is TidalRefreshResult.Refreshed -> {
                    val session = auth.verifiedSession(result.grant, flow, refreshFallback = refreshToken)
                        ?: return@withLock false
                    if (session.userId?.toString() != lease.accountId) return@withLock false
                    lifecycle.commitTidal(attempt, session) == ProviderSignInOutcome.Accepted
                }
                TidalRefreshResult.Rejected, TidalRefreshResult.RetiredClient -> {
                    settings.edit { this[TidalNeedsReloginKey] = true }
                    false
                }
                TidalRefreshResult.Unavailable -> false
            }
        }
    }

    private fun due(): Boolean {
        val expiry = preferences()?.get(TidalTokenExpiryKey) ?: 0L
        return expiry > 0L && expiry - nowMs() <= REFRESH_MARGIN_MS
    }

    private companion object {
        /** The Android service refreshes five minutes before expiry; the same margin keeps both hosts in step. */
        const val REFRESH_MARGIN_MS = 5 * 60 * 1_000L
    }
}
