package moe.rukamori.archivetune.playback

import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailure
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailureReason
import moe.rukamori.archivetune.shared.domain.playback.PlaybackRecoveryBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackErrorRecoveryTest {
    private fun failure(
        reason: PlaybackFailureReason = PlaybackFailureReason.OPEN_FAILED,
        httpStatus: Int? = null,
        causes: List<String> = emptyList(),
    ) = PlaybackFailure(
        occurrenceId = "song",
        reason = reason,
        message = "failure",
        httpStatus = httpStatus,
        causes = causes,
    )

    @Test
    fun aFundedFailureAdvancesPastTheItem() {
        var now = 0L
        val recovery = PlaybackErrorRecovery { now }
        assertTrue(recovery.shouldAdvancePast(failure(httpStatus = 403), positionMs = 5_000L))
    }

    @Test
    fun terminalCategoriesNeverFundAnAdvance() {
        val recovery = PlaybackErrorRecovery { 0L }
        assertFalse(recovery.shouldAdvancePast(failure(httpStatus = 429), positionMs = 0L))
        assertFalse(
            recovery.shouldAdvancePast(failure(reason = PlaybackFailureReason.SOURCE_UNAVAILABLE), positionMs = 0L),
        )
        // A terminal answer does not poison the stretch: the rate-limited failure spent an
        // attempt but no request, so a following recoverable failure still funds an advance.
        assertTrue(recovery.shouldAdvancePast(failure(httpStatus = 403), positionMs = 0L))
    }

    @Test
    fun theStretchFundsAtMostTwoAdvances() {
        var now = 0L
        val recovery = PlaybackErrorRecovery { now }
        assertTrue(recovery.shouldAdvancePast(failure(httpStatus = 403), positionMs = 0L))
        assertTrue(recovery.shouldAdvancePast(failure(httpStatus = 403), positionMs = 0L))
        // PlaybackRecoveryBudget.REQUESTS spent: the third failure falls back to stop-or-skip,
        // and the stretch stays parked — it never refunds itself on the next failure.
        assertEquals(2, PlaybackRecoveryBudget.REQUESTS)
        assertFalse(recovery.shouldAdvancePast(failure(httpStatus = 403), positionMs = 0L))
        assertFalse(recovery.shouldAdvancePast(failure(httpStatus = 403), positionMs = 0L))
        // An explicit reset (recovery, queue teardown) is what buys another advance.
        recovery.reset()
        assertTrue(recovery.shouldAdvancePast(failure(httpStatus = 403), positionMs = 0L))
    }

    @Test
    fun thirtySecondsOfProgressRefundsTheBudget() {
        val recovery = PlaybackErrorRecovery { 0L }
        assertTrue(recovery.shouldAdvancePast(failure(httpStatus = 403), positionMs = 1_000L))
        assertTrue(recovery.shouldAdvancePast(failure(httpStatus = 403), positionMs = 1_000L))
        // A failure deep into the next item means playback moved on: fresh budget.
        assertTrue(
            recovery.shouldAdvancePast(
                failure(httpStatus = 403),
                positionMs = 1_000L + PlaybackRecoveryBudget.WINDOW_MS,
            ),
        )
    }

    @Test
    fun aRecoveryResetRefundsTheStretch() {
        val recovery = PlaybackErrorRecovery { 0L }
        assertTrue(recovery.shouldAdvancePast(failure(httpStatus = 403), positionMs = 0L))
        assertTrue(recovery.shouldAdvancePast(failure(httpStatus = 403), positionMs = 0L))
        recovery.onPlaybackRecovered()
        assertTrue(recovery.shouldAdvancePast(failure(httpStatus = 403), positionMs = 0L))
        recovery.reset()
        assertTrue(recovery.shouldAdvancePast(failure(httpStatus = 403), positionMs = 0L))
    }

    @Test
    fun media3ErrorCodesMapToTheHostReasons() {
        assertEquals(PlaybackFailureReason.OPEN_FAILED, failureReasonFor(2_004))
        assertEquals(PlaybackFailureReason.OUTPUT_FAILED, failureReasonFor(5_002))
        assertEquals(PlaybackFailureReason.DECODE_FAILED, failureReasonFor(4_003))
        assertEquals(PlaybackFailureReason.DECODE_FAILED, failureReasonFor(1_000))
    }
}
