package moe.rukamori.archivetune.startup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Decides when the system splash may hand over to the app window.
 *
 * Composition is the release signal: the first committed composition already contains a branch that
 * can draw — onboarding's own loading indicator while its state is unresolved — so the hand-over
 * never waits for a preference or database read. The budget is only an upper bound for a launch
 * whose content never commits; it is a safety net, not a readiness delay, and it is never the path
 * a healthy launch takes.
 */
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
        const val HOLD_BUDGET_MS = 500L
    }
}
