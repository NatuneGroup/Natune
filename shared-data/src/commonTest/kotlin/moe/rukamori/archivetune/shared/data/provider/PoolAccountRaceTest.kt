package moe.rukamori.archivetune.shared.data.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import moe.rukamori.archivetune.shared.domain.repository.TerminalPlaybackAuthentication

class PoolAccountRaceTest {
    private class Reports {
        val successes = mutableListOf<Int>()
        val failures = mutableListOf<Int>()
        val dead = mutableListOf<Pair<Int, String>>()
    }

    private suspend fun race(
        attempts: List<Int>,
        reports: Reports,
        maxConcurrent: Int = MAX_CONCURRENT_POOL_ATTEMPTS,
        resolve: suspend (Int) -> String?,
    ) = racePoolAttempts(
        attempts = attempts,
        resolve = resolve,
        report = { id, type -> reports.dead += id to type },
        noteSuccess = { reports.successes += it },
        noteFailure = { reports.failures += it },
        maxConcurrent = maxConcurrent,
    )

    @Test fun winnerCancelsLosersAndReportsOnlyTheWinner() = runTest {
        val reports = Reports()
        val gate = CompletableDeferred<Unit>()
        val started = mutableListOf<Int>()
        val cancelled = mutableListOf<Int>()
        val outcome = race(listOf(1, 2, 3), reports, maxConcurrent = 2) { id ->
            started += id
            if (id == 2) {
                "hit-$id"
            } else {
                try {
                    gate.await()
                    "unreached-$id"
                } catch (lost: CancellationException) {
                    cancelled += id
                    throw lost
                }
            }
        }
        assertEquals("hit-2", outcome.winner)
        assertNull(outcome.failure)
        assertEquals(listOf(2), reports.successes)
        assertTrue(reports.failures.isEmpty(), "losers must not be reported as failures")
        assertTrue(reports.dead.isEmpty(), "losers must not be reported dead")
        // Both in-flight losers were cancelled once the winner landed; nobody reported for them.
        assertEquals(setOf(1, 3), cancelled.toSet())
    }

    @Test fun everyRealFailureReportsOnceAndTheLastOrdinaryFailureSurfaces() = runTest {
        val reports = Reports()
        val unauthorized = ProviderHttpFailure(401)
        val auth = TerminalPlaybackAuthentication()
        val protocol = ProviderProtocolFailure()
        val outcome = race(listOf(1, 2, 3, 4), reports, maxConcurrent = 4) { id ->
            when (id) {
                1 -> throw unauthorized
                2 -> throw auth
                3 -> throw protocol
                else -> null
            }
        }
        assertNull(outcome.winner)
        assertTrue(reports.successes.isEmpty())
        assertEquals(setOf(1, 2, 3), reports.failures.toSet(), "every failed attempt reports its failure once")
        assertEquals(
            setOf(1 to PoolDeadReportPolicy.DEAD, 2 to PoolDeadReportPolicy.DEAD),
            reports.dead.toSet(),
            "401 and the auth refusal report dead; a plain failure does not",
        )
        // Attempt 4 only missed, so it reported nothing; the last ordinary failure is attempt 3's —
        // the terminal authentication refusal reports dead but never becomes the thrown failure.
        assertTrue(outcome.failure === protocol)
    }

    @Test fun attemptCancellationPropagatesAndIsNeverReportedAsFailure() = runTest {
        val reports = Reports()
        val gate = CompletableDeferred<Unit>()
        assertFailsWith<CancellationException> {
            race(listOf(1, 2), reports, maxConcurrent = 2) { id ->
                if (id == 1) throw CancellationException("lane cancelled")
                gate.await()
                null
            }
        }
        assertTrue(reports.successes.isEmpty())
        assertTrue(reports.failures.isEmpty(), "a cancelled lane is not a failed lane")
        assertTrue(reports.dead.isEmpty())
    }

    @Test fun inFlightAttemptsNeverExceedTheLaneCap() = runTest {
        val reports = Reports()
        val secondEntered = CompletableDeferred<Unit>()
        val latches = HashMap<Int, CompletableDeferred<Unit>>()
        var entered = 0
        var inFlight = 0
        var peak = 0
        val job = async {
            race(List(6) { it + 1 }, reports, maxConcurrent = 2) { id ->
                entered += 1
                inFlight += 1
                peak = maxOf(peak, inFlight)
                try {
                    if (entered == 2) secondEntered.complete(Unit)
                    latches.getOrPut(id) { CompletableDeferred() }.await()
                    "v$id"
                } finally {
                    inFlight -= 1
                }
            }
        }
        // Both lanes hold their latch, so a third lane can never open while they are busy.
        secondEntered.await()
        assertEquals(2, entered)
        assertEquals(2, peak)
        latches.getValue(1).complete(Unit)
        val outcome = job.await()
        assertEquals("v1", outcome.winner)
        assertEquals(listOf(1), reports.successes, "only the winner reports success")
        assertTrue(reports.failures.isEmpty(), "the lane cancelled by the winner reports nothing")
        assertTrue(reports.dead.isEmpty())
    }
}
