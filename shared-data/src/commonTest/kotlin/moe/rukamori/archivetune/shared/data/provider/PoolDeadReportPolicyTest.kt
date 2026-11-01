package moe.rukamori.archivetune.shared.data.provider

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
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
        val reports = mutableListOf<String>()
        suspend fun fail(): String? = reporter.recoverAndReport(
            "tidal", 9L, PoolFailureKind.AUTH_REJECTED, stale = credential,
            current = { credential },
            recover = { recoveries++ },
            retry = { PoolRetryOutcome.Failure(PoolFailureKind.AUTH_REJECTED) },
        ) { type -> reports += type }
        assertNull(fail())          // the lease never moved, so dead is reported
        assertNull(fail())          // and a second failure inside the window stays deduped
        assertEquals(listOf("dead"), reports)
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
        val dead = awaitAll(
            async { reporter.recoverAndReport<String, String>("deezer", 8L, PoolFailureKind.AUTH_REJECTED, "arl",
                current = { "arl" }, recover = { }, retry = { PoolRetryOutcome.Failure(PoolFailureKind.AUTH_REJECTED) }) { reports++ } },
            async { reporter.recoverAndReport<String, String>("deezer", 8L, PoolFailureKind.AUTH_REJECTED, "arl",
                current = { "arl" }, recover = { }, retry = { PoolRetryOutcome.Failure(PoolFailureKind.AUTH_REJECTED) }) { reports++ } },
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
}
