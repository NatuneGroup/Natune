package moe.rukamori.archivetune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The download retry classes mirror the segment-fetch status-0 model. */
class DownloadFailureClassTest {
    @Test
    fun connectionErrorsAndMissingStatusesAreNoResponse() {
        assertEquals(
            DownloadFailureClass.NO_RESPONSE,
            classifyDownloadFailure(isConnectionError = true, isServerError = false, responseCode = -1),
        )
        // A status of 0 means the server never answered — transport class, not an HTTP verdict.
        assertEquals(
            DownloadFailureClass.NO_RESPONSE,
            classifyDownloadFailure(isConnectionError = false, isServerError = false, responseCode = 0),
        )
    }

    @Test
    fun serverAndClientStatusesSplit() {
        assertEquals(
            DownloadFailureClass.SERVER,
            classifyDownloadFailure(isConnectionError = false, isServerError = true, responseCode = 503),
        )
        assertEquals(
            DownloadFailureClass.HTTP_STATUS,
            classifyDownloadFailure(isConnectionError = false, isServerError = false, responseCode = 403),
        )
    }

    @Test
    fun retryabilityMatchesTheSegmentModel() {
        assertTrue(isRetryableDownloadFailure(DownloadFailureClass.NO_RESPONSE))
        assertTrue(isRetryableDownloadFailure(DownloadFailureClass.SERVER))
        assertTrue(isRetryableDownloadFailure(DownloadFailureClass.TRUNCATED_BODY))
        assertFalse(isRetryableDownloadFailure(DownloadFailureClass.HTTP_STATUS))
        assertFalse(isRetryableDownloadFailure(DownloadFailureClass.EMPTY_RESULT))
    }
}
