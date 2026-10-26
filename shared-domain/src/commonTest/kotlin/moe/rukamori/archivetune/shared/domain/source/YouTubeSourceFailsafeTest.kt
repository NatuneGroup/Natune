/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.source

import moe.rukamori.archivetune.shared.domain.source.YouTubeResolveFailure.CANCELLED
import moe.rukamori.archivetune.shared.domain.source.YouTubeResolveFailure.INVALID_LOGIN_CONTEXT
import moe.rukamori.archivetune.shared.domain.source.YouTubeResolveFailure.LOGIN_REQUIRED
import moe.rukamori.archivetune.shared.domain.source.YouTubeResolveFailure.OTHER
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Characterizes the YouTube extractor tiers that run after the shipped primary resolver fails. The order is
 * InnerTubeX, then NewPipe, then Echo. Login failures and cancellation skip every tier.
 */
class YouTubeSourceFailsafeTest {
    /** Runs a suspend walk that never suspends, so the test needs no coroutine test dependency. */
    private fun <T> runSuspending(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
        return checkNotNull(outcome) { "the walk suspended, which these fakes never do" }.getOrThrow()
    }

    @Test
    fun fallbackTiersRunInExtractorOrderWithEchoLast() {
        assertEquals(
            listOf(YouTubeFallbackTier.INNERTUBE_X, YouTubeFallbackTier.NEWPIPE, YouTubeFallbackTier.ECHO),
            YouTubeFallbackTier.entries,
        )
    }

    @Test
    fun tierBudgetsMatchTheShippedTimeouts() {
        assertEquals(12_000L, YouTubeFallbackTier.INNERTUBE_X.timeoutMs)
        assertEquals(12_000L, YouTubeFallbackTier.NEWPIPE.timeoutMs)
        assertEquals(20_000L, YouTubeFallbackTier.ECHO.timeoutMs)
        assertEquals(12_000L, YouTubeFailsafePolicy.TIER_TIMEOUT_MS)
        assertEquals(20_000L, YouTubeFailsafePolicy.ECHO_TIMEOUT_MS)
    }

    @Test
    fun loginAndConfirmationFailuresSkipEveryFallbackTier() {
        for (failure in listOf(INVALID_LOGIN_CONTEXT, LOGIN_REQUIRED)) {
            assertTrue(YouTubeFailsafePolicy.isActionable(failure), failure.name)
            assertTrue(YouTubeFailsafePolicy.stopsFallbacks(failure), failure.name)

            val attempts = mutableListOf<YouTubeFallbackTier>()
            val recovery =
                runSuspending {
                    YouTubeFailsafePolicy.recoverAfterPrimary<String>(failure) { tier, _ ->
                        attempts += tier
                        "stream"
                    }
                }

            assertEquals(YouTubeRecovery.RethrowPrimary, recovery, failure.name)
            assertEquals(emptyList(), attempts, failure.name)
        }
    }

    @Test
    fun aCancelledPrimaryStopsBeforeAnyTierRuns() {
        assertTrue(YouTubeFailsafePolicy.stopsFallbacks(CANCELLED))
        assertFalse(YouTubeFailsafePolicy.isActionable(CANCELLED))

        var attempted = false
        val recovery =
            runSuspending {
                YouTubeFailsafePolicy.recoverAfterPrimary<String>(CANCELLED) { _, _ ->
                    attempted = true
                    "stream"
                }
            }

        assertEquals(YouTubeRecovery.RethrowPrimary, recovery)
        assertFalse(attempted)
    }

    @Test
    fun anOrdinaryFailureWalksTiersInOrderAndStopsAtTheFirstThatResolves() {
        val attempts = mutableListOf<Pair<YouTubeFallbackTier, Long>>()

        val recovery =
            runSuspending {
                YouTubeFailsafePolicy.recoverAfterPrimary<String>(OTHER) { tier, timeoutMs ->
                    attempts += tier to timeoutMs
                    if (tier == YouTubeFallbackTier.NEWPIPE) "newpipe-stream" else null
                }
            }

        assertEquals(YouTubeRecovery.Resolved(YouTubeFallbackTier.NEWPIPE, "newpipe-stream"), recovery)
        assertEquals(
            listOf(YouTubeFallbackTier.INNERTUBE_X to 12_000L, YouTubeFallbackTier.NEWPIPE to 12_000L),
            attempts,
        )
    }

    @Test
    fun echoIsTheLastResortAndRunsWithItsLongerBudget() {
        val attempts = mutableListOf<Pair<YouTubeFallbackTier, Long>>()

        val recovery =
            runSuspending {
                YouTubeFailsafePolicy.recoverAfterPrimary<String>(OTHER) { tier, timeoutMs ->
                    attempts += tier to timeoutMs
                    if (tier == YouTubeFallbackTier.ECHO) "echo-stream" else null
                }
            }

        assertEquals(YouTubeRecovery.Resolved(YouTubeFallbackTier.ECHO, "echo-stream"), recovery)
        assertEquals(
            listOf(
                YouTubeFallbackTier.INNERTUBE_X to 12_000L,
                YouTubeFallbackTier.NEWPIPE to 12_000L,
                YouTubeFallbackTier.ECHO to 20_000L,
            ),
            attempts,
        )
    }

    @Test
    fun aFullyFailedWalkTriesEveryTierOnceAndRethrowsThePrimary() {
        val attempts = mutableListOf<YouTubeFallbackTier>()

        val recovery =
            runSuspending {
                YouTubeFailsafePolicy.recoverAfterPrimary<String>(OTHER) { tier, _ ->
                    attempts += tier
                    null
                }
            }

        assertEquals(YouTubeRecovery.RethrowPrimary, recovery)
        assertEquals(YouTubeFallbackTier.entries.toList(), attempts)
    }

    @Test
    fun aTierThatThrowsCountsAsFailedAndTheWalkMovesOn() {
        val recovery =
            runSuspending {
                YouTubeFailsafePolicy.recoverAfterPrimary<String>(OTHER) { tier, _ ->
                    if (tier == YouTubeFallbackTier.INNERTUBE_X) throw IllegalStateException("tier broke")
                    "newpipe-stream"
                }
            }

        assertEquals(YouTubeRecovery.Resolved(YouTubeFallbackTier.NEWPIPE, "newpipe-stream"), recovery)
    }

    @Test
    fun callerCancellationPropagatesOutOfTheWalkInsteadOfBecomingAFailedTier() {
        val attempts = mutableListOf<YouTubeFallbackTier>()

        assertFailsWith<CancellationException> {
            runSuspending {
                YouTubeFailsafePolicy.recoverAfterPrimary<String>(OTHER) { tier, _ ->
                    attempts += tier
                    throw CancellationException("caller cancelled")
                }
            }
        }

        assertEquals(listOf(YouTubeFallbackTier.INNERTUBE_X), attempts)
    }
}
