package moe.rukamori.archivetune.startup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupRecoveryPolicyTest {
    @Test fun failuresInTheStartupMillisecondRemainVisibleUnlessAlreadyReviewed() {
        assertEquals(99L, reportSince(lastStartMs = 100L, reviewedThroughMs = 0L))
        assertEquals(100L, reportSince(lastStartMs = 100L, reviewedThroughMs = 100L))
        assertEquals(0L, reportSince(lastStartMs = null, reviewedThroughMs = 0L))
    }

    @Test fun unfinishedDeadOrUnparseableAttemptEntersSafeMode() {
        assertTrue(previousStartupIncomplete(true, pendingPid = 10, livePid = null))
        assertTrue(previousStartupIncomplete(true, pendingPid = null, livePid = null))
        assertTrue(previousStartupIncomplete(true, pendingPid = 10, livePid = 11))
    }

    @Test fun aSecondLauncherTapDuringTheSameStartupIsNotAPreviousCrash() {
        assertFalse(previousStartupIncomplete(true, pendingPid = 10, livePid = 10))
        assertFalse(previousStartupIncomplete(false, pendingPid = null, livePid = null))
    }
}
