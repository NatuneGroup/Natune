package moe.rukamori.archivetune.shared.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackRecoveryPolicyTest {
    @Test
    fun httpStatusMapsToTheHostCategories() {
        val expected = mapOf(
            401 to PlaybackFailureCategory.TOKEN,
            407 to PlaybackFailureCategory.TOKEN,
            403 to PlaybackFailureCategory.CDN_FORBIDDEN,
            404 to PlaybackFailureCategory.URL_EXPIRED,
            410 to PlaybackFailureCategory.URL_EXPIRED,
            408 to PlaybackFailureCategory.TIMEOUT,
            425 to PlaybackFailureCategory.NETWORK,
            429 to PlaybackFailureCategory.RATE_LIMIT,
            500 to PlaybackFailureCategory.NETWORK,
            503 to PlaybackFailureCategory.NETWORK,
            418 to PlaybackFailureCategory.UNKNOWN,
        )
        for ((status, category) in expected) {
            assertEquals(category, PlaybackFailureClassifier.fromHttpStatus(status), "status $status")
        }
    }

    @Test
    fun aCarriedStatusWinsOverCauseNamesAndReason() {
        val failure = PlaybackFailure(
            "occ", PlaybackFailureReason.DECODE_FAILED, "rejected",
            httpStatus = 403, causes = listOf("SocketTimeoutException: read timed out"),
        )
        assertEquals(PlaybackFailureCategory.CDN_FORBIDDEN, PlaybackFailureClassifier.categorize(failure))
    }

    @Test
    fun firstMatchingCauseNameDecidesTheCategory() {
        val base = PlaybackFailure("occ", PlaybackFailureReason.OPEN_FAILED, "failed")
        val cases = listOf(
            "SabrAttestationRequiredException: attestation" to PlaybackFailureCategory.SABR_ATTESTATION,
            "SabrProtocolException: conversation" to PlaybackFailureCategory.SABR_PROTOCOL,
            "SocketTimeoutException: read" to PlaybackFailureCategory.TIMEOUT,
            "MediaCodecDecoderException: codec" to PlaybackFailureCategory.DECODER,
            "AudioTrackRendererException: write" to PlaybackFailureCategory.RENDERER,
            "NetworkException: down" to PlaybackFailureCategory.NETWORK,
            "UnknownHostException: cdn" to PlaybackFailureCategory.NETWORK,
            "IllegalStateException: whatever" to PlaybackFailureCategory.UNKNOWN,
        )
        for ((cause, category) in cases) {
            assertEquals(category, PlaybackFailureClassifier.categorize(base.copy(causes = listOf(cause))), cause)
        }
    }

    @Test
    fun withoutEvidenceTheReasonDecides() {
        val base = PlaybackFailure("occ", PlaybackFailureReason.OPEN_FAILED, "failed")
        val cases = listOf(
            PlaybackFailureReason.DECODE_FAILED to PlaybackFailureCategory.UNKNOWN,
            PlaybackFailureReason.OUTPUT_FAILED to PlaybackFailureCategory.RENDERER,
            PlaybackFailureReason.SOURCE_UNAVAILABLE to PlaybackFailureCategory.UNAVAILABLE,
            PlaybackFailureReason.OPEN_FAILED to PlaybackFailureCategory.UNKNOWN,
            PlaybackFailureReason.UNSUPPORTED_LOCATOR to PlaybackFailureCategory.UNKNOWN,
        )
        for ((reason, category) in cases) {
            assertEquals(category, PlaybackFailureClassifier.categorize(base.copy(reason = reason)), reason.name)
        }
    }

    @Test
    fun everyCategoryDecidesItsAction() {
        val expected = mapOf(
            PlaybackFailureCategory.CDN_FORBIDDEN to PlaybackRecoveryAction.REFRESH_URL,
            PlaybackFailureCategory.URL_EXPIRED to PlaybackRecoveryAction.REFRESH_URL,
            PlaybackFailureCategory.TOKEN to PlaybackRecoveryAction.REFRESH_TOKEN,
            PlaybackFailureCategory.SABR_ATTESTATION to PlaybackRecoveryAction.REFRESH_TOKEN,
            PlaybackFailureCategory.SABR_PROTOCOL to PlaybackRecoveryAction.RETRY_CLIENT,
            PlaybackFailureCategory.PLAYABILITY to PlaybackRecoveryAction.RETRY_CLIENT,
            PlaybackFailureCategory.UNKNOWN to PlaybackRecoveryAction.RETRY_CLIENT,
            PlaybackFailureCategory.TIMEOUT to PlaybackRecoveryAction.RETRY_SAME_URL,
            PlaybackFailureCategory.NETWORK to PlaybackRecoveryAction.INVALIDATE_NETWORK,
            PlaybackFailureCategory.DECODER to PlaybackRecoveryAction.REBUILD_RENDERER,
            PlaybackFailureCategory.RENDERER to PlaybackRecoveryAction.REBUILD_RENDERER,
            PlaybackFailureCategory.RATE_LIMIT to PlaybackRecoveryAction.NONE,
            PlaybackFailureCategory.UNAVAILABLE to PlaybackRecoveryAction.NONE,
        )
        for ((category, action) in expected) {
            val decision = PlaybackRecoveryPolicy.decide(category, PlaybackRecoveryBudget.starting(0L), 0L)
            assertEquals(action, decision.action, category.name)
            assertEquals(PlaybackRecoveryBudget.ATTEMPTS - 1, decision.budget.attemptsRemaining)
        }
    }

    @Test
    fun anExhaustedBudgetAnswersNoneWithoutSpending() {
        val spent = PlaybackRecoveryBudget(requestsRemaining = 0, attemptsRemaining = 2, deadlineAtMs = 60_000L)
        val decision = PlaybackRecoveryPolicy.decide(PlaybackFailureCategory.TIMEOUT, spent, 0L)
        assertEquals(PlaybackRecoveryAction.NONE, decision.action)
        assertEquals(2, decision.budget.attemptsRemaining)

        val pastDeadline = PlaybackRecoveryBudget(2, 3, deadlineAtMs = 10_000L)
        assertEquals(
            PlaybackRecoveryAction.NONE,
            PlaybackRecoveryPolicy.decide(PlaybackFailureCategory.TIMEOUT, pastDeadline, 10_000L).action,
        )
    }

    @Test
    fun thirtySecondsOfPositionProgressRefundsTheBudget() {
        val budget = PlaybackRecoveryBudget.starting(0L)
            .forPlaybackFailure(positionMs = 0L, nowMs = 0L)
            .consumeRequest().consumeRequest()
        assertFalse(budget.canStart(1L))

        val refunded = budget.forPlaybackFailure(positionMs = 30_000L, nowMs = 1L)
        assertEquals(PlaybackRecoveryBudget.REQUESTS, refunded.requestsRemaining)
        assertEquals(PlaybackRecoveryBudget.ATTEMPTS, refunded.attemptsRemaining)
        assertEquals(30_000L, refunded.lastFailurePositionMs)

        // Progress short of the window keeps the spent budget but still records the position.
        val kept = budget.forPlaybackFailure(positionMs = 29_999L, nowMs = 1L)
        assertEquals(0, kept.requestsRemaining)
        assertEquals(29_999L, kept.lastFailurePositionMs)
    }

    @Test
    fun consumeNeverDropsBelowZero() {
        val budget = PlaybackRecoveryBudget(0, 0, 0L)
        assertEquals(0, budget.consumeRequest().requestsRemaining)
        assertEquals(0, budget.consumeAttempt().attemptsRemaining)
    }
}
