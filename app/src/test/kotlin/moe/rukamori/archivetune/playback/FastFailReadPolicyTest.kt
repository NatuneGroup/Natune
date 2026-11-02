package moe.rukamori.archivetune.playback

import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val spec =
            DataSpec.Builder()
                .setUri("https://example.test/audio")
                .setPosition(0L)
                .setLength(100L)
                .build()
        val resumed = FastFailReadPolicy.resumeDataSpec(spec, bytesServed = 30L)
        assertEquals(30L, resumed.position)
        assertEquals(70L, resumed.length)
    }

    @Test
    fun anUnboundedSpecStaysUnboundedOnResume() {
        val spec =
            DataSpec.Builder()
                .setUri("https://example.test/audio")
                .setPosition(10L)
                .setLength(C.LENGTH_UNSET.toLong())
                .build()
        val resumed = FastFailReadPolicy.resumeDataSpec(spec, bytesServed = 5L)
        assertEquals(15L, resumed.position)
        assertEquals(C.LENGTH_UNSET.toLong(), resumed.length)
    }

    @Test
    fun aResumePastTheDeclaredEndClampsToEmpty() {
        val spec =
            DataSpec.Builder()
                .setUri("https://example.test/audio")
                .setPosition(0L)
                .setLength(20L)
                .build()
        assertEquals(0L, FastFailReadPolicy.resumeDataSpec(spec, bytesServed = 40L).length)
    }
}
