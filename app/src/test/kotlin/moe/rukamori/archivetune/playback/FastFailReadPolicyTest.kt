package moe.rukamori.archivetune.playback

import androidx.media3.common.C
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** First-attempt escalation and the mid-stream resume spec. */
class FastFailReadPolicyTest {
    @Test
    fun aSocketTimeoutAnywhereInTheChainEscalates() {
        assertTrue(FastFailReadPolicy.shouldEscalateToSlowPath(SocketTimeoutException("read timed out")))
        assertTrue(
            FastFailReadPolicy.shouldEscalateToSlowPath(
                IOException("stream failed", SocketTimeoutException("connect timed out")),
            ),
        )
        assertTrue(
            FastFailReadPolicy.shouldEscalateToSlowPath(
                IOException(IOException(SocketTimeoutException())),
            ),
        )
    }

    @Test
    fun nonTimeoutFailuresDoNotEscalate() {
        assertFalse(FastFailReadPolicy.shouldEscalateToSlowPath(IOException("refused")))
        assertFalse(
            FastFailReadPolicy.shouldEscalateToSlowPath(
                IOException("failed", IllegalStateException("bad state")),
            ),
        )
    }

    @Test
    fun theResumeSpecContinuesAtTheServedByte() {
        val resumed = FastFailReadPolicy.resumeRange(position = 0L, length = 100L, bytesServed = 30L)
        assertEquals(30L, resumed?.position)
        assertEquals(70L, resumed?.length)
    }

    @Test
    fun anUnboundedSpecStaysUnboundedOnResume() {
        val resumed =
            FastFailReadPolicy.resumeRange(
                position = 10L,
                length = C.LENGTH_UNSET.toLong(),
                bytesServed = 5L,
            )
        assertEquals(15L, resumed?.position)
        assertEquals(C.LENGTH_UNSET.toLong(), resumed?.length)
    }

    @Test
    fun aResumePastTheDeclaredEndReportsExhaustion() {
        // A zero-length open is rejected by Media3 — exhaustion comes back as null so the
        // caller reports end-of-input instead of opening.
        assertNull(FastFailReadPolicy.resumeRange(position = 0L, length = 20L, bytesServed = 20L))
        assertNull(FastFailReadPolicy.resumeRange(position = 0L, length = 20L, bytesServed = 40L))
    }

    @Test
    fun aResumeJustShortOfTheEndStaysOpen() {
        val resumed = FastFailReadPolicy.resumeRange(position = 0L, length = 20L, bytesServed = 19L)
        assertEquals(19L, resumed?.position)
        assertEquals(1L, resumed?.length)
    }
}
