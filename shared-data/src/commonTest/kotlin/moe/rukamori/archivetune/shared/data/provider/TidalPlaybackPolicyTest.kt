package moe.rukamori.archivetune.shared.data.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TidalPlaybackPolicyTest {
    @Test fun onlyRealTokenRejectionsReLeaseAndOnlyLostEntitlementsReportNotPremium() {
        for (kind in listOf(TidalFailureKind.QUALITY_RESTRICTED, TidalFailureKind.RATE_LIMITED)) {
            assertEquals(false, PoolDeadReportPolicy.needsRecovery(kind.toPoolFailureKind()))
            assertNull(PoolDeadReportPolicy.reportTypeFor(kind.toPoolFailureKind()))
        }
        assertEquals(true, PoolDeadReportPolicy.needsRecovery(TidalFailureKind.AUTH_REJECTED.toPoolFailureKind()))
        assertEquals(PoolDeadReportPolicy.NOT_PREMIUM,
            PoolDeadReportPolicy.reportTypeFor(TidalFailureKind.ENTITLEMENT_LOST.toPoolFailureKind()))
    }

    @Test fun observedSubscription401DoesNotRejectTheToken() {
        assertEquals(TidalFailureKind.QUALITY_RESTRICTED, classifyTidalFailure(401, 5003,
            "Requested quality is not allowed in user's subscription"))
        assertEquals(TidalFailureKind.QUALITY_RESTRICTED, classifyTidalFailure(403, null,
            "Requested quality is not allowed by the subscription"))
        assertEquals(TidalFailureKind.AUTH_REJECTED, classifyTidalFailure(401, 6001, "Token expired"))
        assertEquals(TidalFailureKind.ENTITLEMENT_LOST, classifyTidalFailure(401, null, "User is not premium"))
        assertEquals(TidalFailureKind.RATE_LIMITED, classifyTidalFailure(429, 5003, null))
        assertEquals(TidalFailureKind.CONTENT_UNAVAILABLE, classifyTidalFailure(404, null, null))
        assertEquals(TidalFailureKind.TRANSIENT, classifyTidalFailure(503, null, null))
    }

    @Test fun hiResFallsBackToLosslessOnTheSameTrack() = runTest {
        val calls = mutableListOf<String>()
        val downgrades = mutableListOf<Pair<String, String>>()
        val result = resolveTidalQuality("HI_RES_LOSSLESS", { from, to -> downgrades += from to to }) { quality ->
            calls += quality
            if (quality == "HI_RES_LOSSLESS") throw TidalPlaybackFailure(401, 5003, TidalFailureKind.QUALITY_RESTRICTED)
            "full-track-$quality"
        }
        assertEquals("full-track-LOSSLESS", result)
        assertEquals(listOf("HI_RES_LOSSLESS", "LOSSLESS"), calls)
        assertEquals(listOf("HI_RES_LOSSLESS" to "LOSSLESS"), downgrades)
    }

    @Test fun losslessFallsBackToHighOnlyForAQualityRefusal() = runTest {
        val calls = mutableListOf<String>()
        assertEquals("full-track-HIGH", resolveTidalQuality("LOSSLESS") { quality ->
            calls += quality
            if (quality == "LOSSLESS") throw TidalPlaybackFailure(401, 5003, TidalFailureKind.QUALITY_RESTRICTED)
            "full-track-$quality"
        })
        assertEquals(listOf("LOSSLESS", "HIGH"), calls)
    }

    @Test fun everyQualityRefusedStopsAtHigh() = runTest {
        val calls = mutableListOf<String>()
        assertFailsWith<TidalPlaybackFailure> {
            resolveTidalQuality<String>("HI_RES_LOSSLESS") { quality ->
                calls += quality
                throw TidalPlaybackFailure(401, 5003, TidalFailureKind.QUALITY_RESTRICTED)
            }
        }
        assertEquals(listOf("HI_RES_LOSSLESS", "LOSSLESS", "HIGH"), calls)
        assertEquals(listOf("HIGH"), tidalQualityAttempts("HIGH"))
    }

    @Test fun authRateLimitTransientAndCancellationNeverRetryQuality() = runTest {
        for (error in listOf(
            TidalPlaybackFailure(401, 6001, TidalFailureKind.AUTH_REJECTED),
            TidalPlaybackFailure(429, null, TidalFailureKind.RATE_LIMITED),
            TidalPlaybackFailure(503, null, TidalFailureKind.TRANSIENT),
            CancellationException("caller stopped"),
        )) {
            var calls = 0
            assertFailsWith<Exception> {
                resolveTidalQuality<String>("HI_RES_LOSSLESS") { calls++; throw error }
            }
            assertEquals(1, calls)
        }
    }

    @Test fun missingTrackOrManifestDoesNotRetryAndCooldownIsBounded() = runTest {
        var calls = 0
        assertNull(resolveTidalQuality<String>("LOSSLESS") { calls++; null })
        assertEquals(1, calls)
        assertEquals(60_000L, tidalRateLimitCooldownMs(null))
        assertEquals(120_000L, tidalRateLimitCooldownMs(120_000L))
        assertEquals(1_000L, tidalRateLimitCooldownMs(0L))
        assertEquals(600_000L, tidalRateLimitCooldownMs(Long.MAX_VALUE))
    }

    @Test fun rateLimitSkipsRequestsUntilExpiryEvenIfEveryAccountIsLimited() = runTest {
        var now = 1_000L
        val gate = TidalRateLimitGate { now }
        gate.limit("one", 120_000L)
        gate.limit("two", null)
        val calls = mutableListOf<String>()
        assertNull(resolveTidalPoolAccounts(listOf("one", "two"), true, gate::canAttempt, { _: String -> true }) {
            calls += it; "full"
        })
        assertEquals(emptyList(), calls)
        now += 60_000L
        assertEquals(false, gate.canAttempt("one"))
        assertEquals(true, gate.canAttempt("two"))
        now += 60_000L
        assertEquals(true, gate.canAttempt("one"))
    }

    @Test fun earlyLossyAnswerCannotHideALaterLosslessAccount() = runTest {
        val calls = mutableListOf<Int>()
        assertEquals("flac", resolveTidalPoolAccounts(listOf(1, 2, 3), true, { true }, { it: String -> it == "flac" }) {
            calls += it
            if (it == 1) "aac" else "flac"
        })
        assertEquals(listOf(1, 2), calls)
        assertEquals("aac", resolveTidalPoolAccounts(listOf(1, 2), true, { true }, { _: String -> false }) {
            if (it == 1) "aac" else null
        })
    }
}
