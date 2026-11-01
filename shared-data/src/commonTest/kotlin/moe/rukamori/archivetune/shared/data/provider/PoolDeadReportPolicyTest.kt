package moe.rukamori.archivetune.shared.data.provider

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.*
import moe.rukamori.archivetune.shared.domain.repository.TerminalPlaybackAuthentication

class PoolDeadReportPolicyTest {
    private class TestClock(var now: Long = 0L) {
        fun nowMs() = now
    }

    private fun reporter(clock: TestClock) = PoolDeadReporter(nowMs = clock::nowMs)

    @Test fun onlyRealDeadSignalsClassifyAsReportable() {
        assertEquals(PoolFailureKind.AUTH_REJECTED, classifyPoolFailure(ProviderHttpFailure(401)))
        assertEquals(PoolFailureKind.AUTH_REJECTED, classifyPoolFailure(ProviderHttpFailure(403)))
        assertEquals(PoolFailureKind.AUTH_REJECTED, classifyPoolFailure(TerminalPlaybackAuthentication()))
        assertEquals(PoolFailureKind.AUTH_REJECTED, classifyPoolFailure(AppleAuthException()))
        assertEquals(PoolFailureKind.ENTITLEMENT_LOST, classifyPoolFailure(ProviderHttpFailure(402)))
        assertEquals(PoolFailureKind.CONTENT_UNAVAILABLE, classifyPoolFailure(ProviderHttpFailure(404)))
        assertEquals(PoolFailureKind.RATE_LIMITED, classifyPoolFailure(ProviderHttpFailure(429)))
        assertEquals(PoolFailureKind.TRANSIENT, classifyPoolFailure(ProviderHttpFailure(500)))
        assertEquals(PoolFailureKind.TRANSIENT, classifyPoolFailure(ProviderHttpFailure(503)))
        assertEquals(PoolFailureKind.TRANSIENT, classifyPoolFailure(ProviderRequestTimeout()))
        assertEquals(PoolFailureKind.TRANSIENT, classifyPoolFailure(ProviderProtocolFailure()))
        assertEquals(PoolFailureKind.TRANSIENT, classifyPoolFailure(IllegalStateException("io")))
        assertEquals(PoolFailureKind.TRANSIENT, classifyPoolFailure(null))
    }

    @Test fun expiredCredentialRecoversOnceAndNeverReports() = runTest {
        val clock = TestClock()
        val reporter = reporter(clock)
        var credential = "stale"
        var recoveries = 0
        val reports = mutableListOf<String>()
        val result = reporter.recoverAndReport<String, String>(
            "tidal", 7L, PoolFailureKind.AUTH_REJECTED, stale = "stale",
            current = { credential },
            recover = { recoveries++; credential = "fresh" },
            retry = { fresh -> PoolRetryOutcome.Success("stream:$fresh") },
        ) { type -> reports += type }
        assertEquals("stream:fresh", result)
        assertEquals(1, recoveries)
        assertTrue(reports.isEmpty())
    }

    @Test fun revokedCredentialStillFailingReportsDeadOnce() = runTest {
        val clock = TestClock()
        val reporter = reporter(clock)
        var credential = "stale"
        var recoveries = 0
        var retries = 0
        val reports = mutableListOf<String>()
        suspend fun fail(): String? = reporter.recoverAndReport(
            "tidal", 9L, PoolFailureKind.AUTH_REJECTED, stale = credential,
            current = { credential },
            recover = { recoveries++; credential = "fresh" },
            retry = { retries++; PoolRetryOutcome.Failure(PoolFailureKind.AUTH_REJECTED) },
        ) { type -> reports += type }
        assertNull(fail())          // the lease moved, and the fresh credential is refused too
        assertNull(fail())          // a second failure inside the window stays deduped
        assertEquals(listOf("dead"), reports)
        assertEquals(1, retries)    // the report fired only after the bounded retry refused again
        assertEquals(1, recoveries) // the second failure was inside the recovery window too
    }

    @Test fun concurrentFailuresShareOneRecoveryAndAtMostOneReport() = runTest {
        val clock = TestClock()
        val reporter = reporter(clock)
        var credential = "stale"
        var recoveries = 0
        var reports = 0
        val wins = awaitAll(
            async { reporter.recoverAndReport<String, String>("tidal", 5L, PoolFailureKind.AUTH_REJECTED, "stale",
                current = { credential }, recover = { recoveries++; yield(); delay(10); credential = "fresh" },
                retry = { PoolRetryOutcome.Success(it) }) { reports++ } },
            async { reporter.recoverAndReport<String, String>("tidal", 5L, PoolFailureKind.AUTH_REJECTED, "stale",
                current = { credential }, recover = { recoveries++; credential = "fresh2" },
                retry = { PoolRetryOutcome.Success(it) }) { reports++ } },
            async { reporter.recoverAndReport<String, String>("tidal", 5L, PoolFailureKind.AUTH_REJECTED, "stale",
                current = { credential }, recover = { recoveries++; credential = "fresh3" },
                retry = { PoolRetryOutcome.Success(it) }) { reports++ } },
        )
        assertEquals(3, wins.count { it != null })
        assertEquals(1, recoveries)   // the losers reused the winner's fresh credential
        assertEquals(0, reports)

        reports = 0
        var arl = "arl"
        val dead = awaitAll(
            async { reporter.recoverAndReport<String, String>("deezer", 8L, PoolFailureKind.AUTH_REJECTED, "arl",
                current = { arl }, recover = { arl = "rotated" },
                retry = { PoolRetryOutcome.Failure(PoolFailureKind.AUTH_REJECTED) }) { reports++ } },
            async { reporter.recoverAndReport<String, String>("deezer", 8L, PoolFailureKind.AUTH_REJECTED, "arl",
                current = { arl }, recover = { arl = "rotated2" },
                retry = { PoolRetryOutcome.Failure(PoolFailureKind.AUTH_REJECTED) }) { reports++ } },
        )
        assertTrue(dead.all { it == null })
        assertEquals(1, reports)      // concurrent dead verdicts collapse into one report
    }

    @Test fun transientAndRateLimitedFailuresNeverReport() = runTest {
        val clock = TestClock()
        val reporter = reporter(clock)
        var recoveries = 0
        var reports = 0
        for (kind in listOf(
            PoolFailureKind.TRANSIENT, PoolFailureKind.RATE_LIMITED, PoolFailureKind.CONTENT_UNAVAILABLE,
        )) {
            assertNull(
                reporter.recoverAndReport<String, String>(
                    "tidal", 3L, kind, "token",
                    current = { "token" }, recover = { recoveries++ },
                    retry = { PoolRetryOutcome.Success(it) },
                ) { reports++ },
            )
        }
        assertEquals(0, recoveries)
        assertEquals(0, reports)
    }

    @Test fun entitlementLossReportsNotPremiumWithoutRecovery() = runTest {
        val clock = TestClock()
        val reporter = reporter(clock)
        var recoveries = 0
        val reports = mutableListOf<String>()
        assertNull(
            reporter.recoverAndReport<String, String>(
                "qobuz", 4L, PoolFailureKind.ENTITLEMENT_LOST, "token",
                current = { "token" }, recover = { recoveries++ },
                retry = { PoolRetryOutcome.Success(it) },
            ) { type -> reports += type },
        )
        assertEquals(listOf("not_premium"), reports)
        assertEquals(0, recoveries)
    }

    @Test fun reportGateDeduplicatesThirtyMinutesPerAccount() = runTest {
        val clock = TestClock()
        val gate = PoolReportGate(clock::nowMs)
        assertTrue(gate.allow("tidal", 1L, "dead"))
        assertFalse(gate.allow("tidal", 1L, "dead"))          // same window
        assertTrue(gate.allow("tidal", 1L, "not_premium"))    // a different type reports separately
        assertTrue(gate.allow("tidal", 2L, "dead"))           // a different account reports separately
        clock.now += PoolDeadReportPolicy.REPORT_DEDUPE_MS + 1
        assertTrue(gate.allow("tidal", 1L, "dead"))           // the window lapsed
        assertFalse(gate.allow("tidal", null, "dead"))        // manual accounts never report
        assertFalse(gate.allow("tidal", 0L, "dead"))
    }

    @Test fun unmovedOrUnavailableLeaseNeverReports() = runTest {
        val clock = TestClock()
        val reporter = reporter(clock)
        var retries = 0
        val reports = mutableListOf<String>()
        // The feed still serves the credential that failed: it believes the account healthy, so
        // the refusal the caller saw was the stale token — nothing about the account is proven.
        assertNull(
            reporter.recoverAndReport<String, String>(
                "tidal", 11L, PoolFailureKind.AUTH_REJECTED, "stale",
                current = { "stale" }, recover = { },
                retry = { retries++; PoolRetryOutcome.Success(it) },
            ) { type -> reports += type },
        )
        // The feed cannot even name the credential — no round-trip completed — so no verdict.
        assertNull(
            reporter.recoverAndReport<String, String>(
                "tidal", 12L, PoolFailureKind.AUTH_REJECTED, "stale",
                current = { null }, recover = { },
                retry = { retries++; PoolRetryOutcome.Success(it) },
            ) { type -> reports += type },
        )
        assertEquals(0, retries)
        assertTrue(reports.isEmpty())
    }

    @Test fun cancelledLeaderRollsBackAndWaiterRecovers() = runTest {
        val clock = TestClock()
        val reporter = reporter(clock)
        var credential = "stale"
        var recoveries = 0
        val reports = mutableListOf<String>()
        val leader = async {
            reporter.recoverAndReport<String, String>(
                "tidal", 6L, PoolFailureKind.AUTH_REJECTED, "stale",
                current = { credential },
                recover = { delay(Long.MAX_VALUE) },
                retry = { PoolRetryOutcome.Success(it) },
            ) { type -> reports += type }
        }
        runCurrent() // the leader admits the recovery window, then parks inside the round-trip
        val waiter = async {
            reporter.recoverAndReport<String, String>(
                "tidal", 6L, PoolFailureKind.AUTH_REJECTED, "stale",
                current = { credential },
                recover = { recoveries++; credential = "fresh" },
                retry = { PoolRetryOutcome.Success("stream:$it") },
            ) { type -> reports += type }
        }
        runCurrent() // the waiter queues on the flight gate behind the leader
        leader.cancel()
        assertEquals("stream:fresh", waiter.await())
        assertFailsWith<CancellationException> { leader.await() }
        assertEquals(1, recoveries)
        assertTrue(reports.isEmpty())
    }

    @Test fun flightsArePerKeyAndTheirMapEmptiesAfterward() = runTest {
        val flights = KeyedSingleFlight()
        val order = mutableListOf<String>()
        val first = async {
            flights.run("tidal:1", satisfied = { null }) { delay(10); order += "one"; "one" }
        }
        val second = async {
            flights.run("tidal:2", satisfied = { null }) { delay(5); order += "two"; "two" }
        }
        val waiter = async {
            flights.run("tidal:1", satisfied = { null }) { delay(1); order += "waiter"; "waiter" }
        }
        assertEquals(listOf("one", "two", "waiter"), awaitAll(first, second, waiter))
        // "two" finished before "one" — distinct keys never shared a gate; the same-key waiter
        // queued behind its leader and ran last.
        assertEquals(listOf("two", "one", "waiter"), order)
        assertEquals(0, flights.trackedKeyCount())
    }

    @Test fun reLeaseAnswersTheExplicitRecoveryOutcome() = runTest {
        val clock = TestClock()
        val reporter = reporter(clock)
        var tuple = Triple("tok", "app", "secret1")
        // The pool can rotate the secret without touching the user token; the tuple marker must
        // still count that lease as moved so the caller retries the credential it now serves.
        val moved = reporter.reLease(
            "qobuz", 3L, Triple("tok", "app", "secret1"),
            current = { tuple },
            recover = { tuple = Triple("tok", "app", "secret2") },
        )
        assertEquals(PoolRecovery.Recovered(Triple("tok", "app", "secret2")), moved)
        // The same credential still served means the refusal was the stale lease, not the account.
        assertEquals(
            PoolRecovery.Unchanged,
            reporter.reLease("qobuz", 4L, "tok", current = { "tok" }, recover = { }),
        )
        // No cached credential readable means no verdict is possible.
        assertEquals(
            PoolRecovery.Unavailable,
            reporter.reLease<String>("qobuz", 5L, "tok", current = { null }, recover = { }),
        )
    }

    @Test fun retryEntitlementLossReportsNotPremium() = runTest {
        val clock = TestClock()
        val reporter = reporter(clock)
        var credential = "stale"
        val reports = mutableListOf<String>()
        // The re-lease moved and the retried credential came back refused for entitlement (a 402):
        // that is the real-dead verdict the server needs, reported under its own type.
        assertNull(
            reporter.recoverAndReport<String, String>(
                "tidal", 13L, PoolFailureKind.AUTH_REJECTED, "stale",
                current = { credential },
                recover = { credential = "fresh" },
                retry = { PoolRetryOutcome.Failure(PoolFailureKind.ENTITLEMENT_LOST) },
            ) { type -> reports += type },
        )
        assertEquals(listOf("not_premium"), reports)
    }

    @Test fun recoveryFlightIsServiceWideAndEachCallerReReadsItsCredential() = runTest {
        val clock = TestClock()
        val reporter = reporter(clock)
        val credentials = mutableMapOf(5L to "stale-a", 6L to "stale-b")
        var recoveries = 0
        val retries = mutableListOf<Pair<Long, String>>()
        suspend fun fail(id: Long) = reporter.recoverAndReport<String, String>(
            "tidal", id, PoolFailureKind.AUTH_REJECTED, credentials.getValue(id),
            current = { credentials[id] },
            recover = {
                recoveries++
                delay(10)   // hold the round-trip so the second account arrives mid-flight
                credentials[5L] = "fresh-a"
                credentials[6L] = "fresh-b"
            },
            retry = { fresh -> retries += id to fresh; PoolRetryOutcome.Success("s:$id:$fresh") },
        ) { }
        // Account B must join A's in-flight refresh and re-read its own credential afterwards —
        // an account-keyed flight let B skip recovery while its token was still stale.
        // awaitAll answers in launch order, so both retries land in the order they were raced.
        val results = awaitAll(async { fail(5L) }, async { fail(6L) })
        assertEquals(listOf("s:5:fresh-a", "s:6:fresh-b"), results)
        assertEquals(1, recoveries)
        assertEquals(setOf(5L to "fresh-a", 6L to "fresh-b"), retries.toSet())
    }

    @Test fun serviceGateAdmitsOneCallerPerWindowAtomically() = runTest {
        val clock = TestClock()
        val gate = PoolReportGate(clock::nowMs, PoolDeadReportPolicy.RECOVERY_DEDUPE_MS)
        // The expired-lease re-lease on every host goes through this admission; same-ms callers
        // must split one round-trip, not each pay for it the way a merge-then-compare map allowed.
        val admitted = awaitAll(
            async { gate.allowService("tidal", "expired") },
            async { gate.allowService("tidal", "expired") },
            async { gate.allowService("tidal", "expired") },
        )
        assertEquals(1, admitted.count { it })
        assertFalse(gate.allowService("tidal", "expired"))
        clock.now += PoolDeadReportPolicy.RECOVERY_DEDUPE_MS + 1
        assertTrue(gate.allowService("tidal", "expired"))
    }
}
