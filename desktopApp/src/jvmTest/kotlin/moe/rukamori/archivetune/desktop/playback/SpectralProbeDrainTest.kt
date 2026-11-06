package moe.rukamori.archivetune.desktop.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest

class SpectralProbeDrainTest {
    @Test
    fun aReadThatReturnedIsWaitedFor() = runTest {
        val reading = Job()
        reading.complete()
        assertTrue(awaitAbandonedRead(reading, GRACE_MS))
    }

    @Test
    fun aReadThatNeverReturnsIsAbandonedAfterTheGrace() = runTest {
        // A host that ignores both the probe's cancellation and its I/O deadline must not hold the audit caller.
        assertFalse(awaitAbandonedRead(Job(), GRACE_MS))
    }

    @Test
    fun aReadThatFinishesAfterTheGraceIsAbandonedWithoutThrowing() = runTest {
        val reading = Job()
        assertFalse(awaitAbandonedRead(reading, GRACE_MS))
        // The probe is freed by the read's own completion handler, which lands after the caller has already moved on.
        reading.complete()
        assertTrue(reading.isCompleted)
    }

    private companion object {
        private const val GRACE_MS = 1_000L
    }
}
