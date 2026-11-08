package moe.rukamori.archivetune.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Credential-free settings status, updated by the feed owner even when refresh runs in the background. */
data class PoolFeedStatus(
    val tidal: Int = 0,
    val qobuz: Int = 0,
    val deezer: Int = 0,
    val appleMusic: Int = 0,
    val amazon: Int = 0,
    val lastAttemptAtMillis: Long = 0L,
    val error: String? = null,
    val refreshing: Boolean = false,
)

internal object PoolFeedStatusStore {
    private val mutableStatus = MutableStateFlow(PoolFeedStatus())
    val status: StateFlow<PoolFeedStatus> = mutableStatus.asStateFlow()

    fun publish(refreshing: Boolean = false) {
        mutableStatus.value = PoolFeedStatus(
            tidal = PoolAccountManager.tidalAccounts().size,
            qobuz = PoolAccountManager.qobuzAccounts().size,
            deezer = PoolAccountManager.deezerAccounts().size,
            appleMusic = PoolAccountManager.appleMusicAccounts().size,
            amazon = PoolAccountManager.amazonAccounts().size,
            lastAttemptAtMillis = PoolAccountManager.lastRefreshAtMillis,
            error = PoolAccountManager.lastFeedError,
            refreshing = refreshing,
        )
    }
}
