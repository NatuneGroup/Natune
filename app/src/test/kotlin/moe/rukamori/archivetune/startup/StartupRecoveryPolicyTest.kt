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

    @Test fun anAttemptRecordedBeforeThisInstallCannotEnterSafeMode() {
        val install = InstallWindow(installedSinceMs = 1_000L, versionCode = 100L)
        val stale = StartupAttempt(500L, 10, "N20261031")
        assertFalse(
            previousStartupIncomplete(
                true, pending = stale, livePid = null, install = install, currentVersion = "N20261101",
            ),
        )
        assertFalse(
            previousStartupIncomplete(
                true, pending = stale, livePid = 20, install = install, currentVersion = "N20261101",
            ),
        )
    }

    @Test fun anAttemptStampedByAnotherVersionIsAlsoStale() {
        val install = InstallWindow(installedSinceMs = 100L, versionCode = 100L)
        val stale = StartupAttempt(1_000L, 10, "N20261031")
        assertTrue(isStaleAttempt(stale, install, "N20261101"))
        assertFalse(isStaleAttempt(stale.copy(version = "N20261101"), install, "N20261101"))
        assertFalse(
            previousStartupIncomplete(
                true, pending = stale, livePid = null, install = install, currentVersion = "N20261101",
            ),
        )
    }

    @Test fun aStaleAttemptIsReportedOnceUntilAcknowledged() {
        val install = InstallWindow(installedSinceMs = 1_000L, versionCode = 100L)
        val stale = StartupAttempt(500L, 10, "N20261031")
        assertTrue(staleAttemptUnacknowledged(stale, install, "N20261101", acknowledgedThroughMs = 0L))
        assertFalse(staleAttemptUnacknowledged(stale, install, "N20261101", acknowledgedThroughMs = 500L))
        assertFalse(staleAttemptUnacknowledged(stale, install, "N20261101", acknowledgedThroughMs = 900L))
    }

    @Test fun aCurrentBuildAttemptThatDiedUnfinishedStillEntersSafeMode() {
        val install = InstallWindow(installedSinceMs = 100L, versionCode = 100L)
        val failed = StartupAttempt(1_000L, 10, "N20261101")
        assertTrue(
            previousStartupIncomplete(
                true, pending = failed, livePid = null, install = install, currentVersion = "N20261101",
            ),
        )
        assertTrue(
            previousStartupIncomplete(
                true, pending = failed, livePid = 20, install = install, currentVersion = "N20261101",
            ),
        )
        assertFalse(
            previousStartupIncomplete(
                true, pending = failed, livePid = 10, install = install, currentVersion = "N20261101",
            ),
        )
    }
}
