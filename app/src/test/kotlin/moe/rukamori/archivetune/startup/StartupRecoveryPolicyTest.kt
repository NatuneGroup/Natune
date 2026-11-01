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

    @Test fun aCurrentBuildMarkerSurvivesABackwardClockCorrection() {
        // Its install identity decides staleness, so a rolled-back timestamp and an existing
        // acknowledgement watermark cannot hide an unfinished startup of this build.
        val install = InstallWindow(installedSinceMs = 1_000L, versionCode = 100L)
        val rolledBack = StartupAttempt(500L, 10, "N20261101", versionCode = 100L, installedSinceMs = 1_000L)
        assertFalse(isStaleAttempt(rolledBack, install, "N20261101"))
        assertFalse(staleAttemptUnacknowledged(rolledBack, install, "N20261101", acknowledgedThroughMs = 9_999L))
        assertTrue(
            previousStartupIncomplete(
                true, pending = rolledBack, livePid = null, install = install, currentVersion = "N20261101",
            ),
        )
        assertTrue(
            previousStartupIncomplete(
                true, pending = rolledBack, livePid = 20, install = install, currentVersion = "N20261101",
            ),
        )
    }

    @Test fun aMarkerFromAnotherBuildOrInstallIsStaleEvenWithALaterTimestamp() {
        val install = InstallWindow(installedSinceMs = 1_000L, versionCode = 100L)
        val otherBuild = StartupAttempt(2_000L, 10, "N20261101", versionCode = 99L, installedSinceMs = 1_000L)
        val otherInstall = StartupAttempt(2_000L, 10, "N20261101", versionCode = 100L, installedSinceMs = 500L)
        assertTrue(isStaleAttempt(otherBuild, install, "N20261101"))
        assertTrue(isStaleAttempt(otherInstall, install, "N20261101"))
        assertFalse(
            previousStartupIncomplete(
                true, pending = otherBuild, livePid = null, install = install, currentVersion = "N20261101",
            ),
        )
    }

    @Test fun aLegacyMarkerWithoutInstallIdentityStillUsesClockAndVersionInference() {
        val install = InstallWindow(installedSinceMs = 1_000L, versionCode = 100L)
        assertTrue(isStaleAttempt(StartupAttempt(500L, 10, "N20261031"), install, "N20261101"))
        assertTrue(isStaleAttempt(StartupAttempt(2_000L, 10, "N20261031"), install, "N20261101"))
        assertFalse(isStaleAttempt(StartupAttempt(2_000L, 10, "N20261101"), install, "N20261101"))
    }

    @Test fun aCurrentBuildRecordIsNeverFromAnEarlierVersionWhateverTheClock() {
        val install = InstallWindow(installedSinceMs = 1_000L, versionCode = 100L)
        val record = crashRecord(timestampMs = 500L, versionCode = 100L)
        assertFalse(record.isFromEarlierVersion(install))
        assertTrue(record.copy(versionCode = 99L).isFromEarlierVersion(install))
        assertTrue(record.copy(versionCode = UNKNOWN_VERSION_CODE).isFromEarlierVersion(install))
        assertFalse(
            crashRecord(timestampMs = 2_000L, versionCode = UNKNOWN_VERSION_CODE)
                .isFromEarlierVersion(install),
        )
    }

    private fun crashRecord(timestampMs: Long, versionCode: Long) = CrashRecord(
        timestampMs = timestampMs,
        pid = 10,
        kind = ProcessExitKind.CRASH,
        status = 0,
        captured = true,
        process = "moe.rukamori.archivetune",
        thread = "main",
        body = "",
        truncated = false,
        versionName = "N20261101",
        versionCode = versionCode,
    )
}
