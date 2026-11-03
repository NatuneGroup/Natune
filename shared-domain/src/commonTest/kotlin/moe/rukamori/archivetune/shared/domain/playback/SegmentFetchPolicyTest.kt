package moe.rukamori.archivetune.shared.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Spotify's segment retry classes: status-0 transport and truncated bodies earn one more attempt. */
class SegmentFetchPolicyTest {
    @Test
    fun onlyTransportAndTruncationAreRetryable() {
        assertTrue(SegmentFetchPolicy.isRetryable(SegmentFetchPolicy.FailureClass.NO_RESPONSE))
        assertTrue(SegmentFetchPolicy.isRetryable(SegmentFetchPolicy.FailureClass.TRUNCATED_BODY))
        assertFalse(SegmentFetchPolicy.isRetryable(SegmentFetchPolicy.FailureClass.HTTP_STATUS))
        assertFalse(SegmentFetchPolicy.isRetryable(SegmentFetchPolicy.FailureClass.PROTOCOL))
    }

    @Test
    fun theAttemptBudgetIsTwo() {
        assertTrue(
            SegmentFetchPolicy.allowsAnotherAttempt(SegmentFetchPolicy.FailureClass.NO_RESPONSE, attempt = 1),
        )
        assertFalse(
            SegmentFetchPolicy.allowsAnotherAttempt(SegmentFetchPolicy.FailureClass.NO_RESPONSE, attempt = 2),
        )
        assertFalse(
            SegmentFetchPolicy.allowsAnotherAttempt(SegmentFetchPolicy.FailureClass.HTTP_STATUS, attempt = 1),
        )
    }

    @Test
    fun aDeclaredContentLengthIsThePromise() {
        assertEquals(
            500L,
            SegmentFetchPolicy.expectedSegmentBytes(
                contentLengthBytes = 500L,
                rangeTotalBytes = 1_000L,
                positionBytes = 200L,
            ),
        )
    }

    @Test
    fun anUndeclaredPartialBodyOwesTheRestOfTheRange() {
        assertEquals(
            800L,
            SegmentFetchPolicy.expectedSegmentBytes(
                contentLengthBytes = -1L,
                rangeTotalBytes = 1_000L,
                positionBytes = 200L,
            ),
        )
    }

    @Test
    fun nothingDeclaredMeansNoTruncationCheck() {
        assertEquals(
            SegmentFetchPolicy.UNKNOWN_LENGTH,
            SegmentFetchPolicy.expectedSegmentBytes(
                contentLengthBytes = -1L,
                rangeTotalBytes = -1L,
                positionBytes = 0L,
            ),
        )
        assertEquals(
            SegmentFetchPolicy.UNKNOWN_LENGTH,
            SegmentFetchPolicy.expectedSegmentBytes(
                contentLengthBytes = 0L,
                rangeTotalBytes = 100L,
                positionBytes = 100L,
            ),
        )
    }
}
