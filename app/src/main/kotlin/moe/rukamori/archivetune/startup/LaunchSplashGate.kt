package moe.rukamori.archivetune.startup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Holds the system splash until the resolved launch route has completed layout. */
internal class LaunchSplashGate(
    private val holdBudgetMs: Long = HOLD_BUDGET_MS,
) {
    private val firstComposition = CompletableDeferred<Unit>()

    fun contentComposed() {
        firstComposition.complete(Unit)
    }

    /** Returns as soon as content composed, or when the finite hold budget runs out. */
    suspend fun awaitRelease() {
        withTimeoutOrNull(holdBudgetMs) { firstComposition.await() }
    }

    companion object {
        /** A stalled compose is released here; an honest one releases far earlier. */
        const val HOLD_BUDGET_MS = 5_000L
    }
}
