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
        val install = install()
        val stale = StartupAttempt(500L, 10, "N20261031")
        assertFalse(previousStartupIncomplete(true, pending = stale, livePid = null, install = install))
        assertFalse(previousStartupIncomplete(true, pending = stale, livePid = 20, install = install))
    }

    @Test fun anAttemptStampedByAnotherVersionIsAlsoStale() {
        val install = install()
        val stale = StartupAttempt(1_000L, 10, "N20261031")
        assertTrue(isStaleAttempt(stale, install))
        assertFalse(isStaleAttempt(stale.copy(version = "N20261101"), install))
        assertFalse(previousStartupIncomplete(true, pending = stale, livePid = null, install = install))
    }

    @Test fun aStaleAttemptIsReportedOnceUntilAcknowledged() {
        val install = install()
        val stale = StartupAttempt(500L, 10, "N20261031")
        assertTrue(staleAttemptUnacknowledged(stale, install, acknowledgedThroughMs = 0L))
        assertFalse(staleAttemptUnacknowledged(stale, install, acknowledgedThroughMs = 500L))
        assertFalse(staleAttemptUnacknowledged(stale, install, acknowledgedThroughMs = 900L))
    }

    @Test fun aCurrentBuildAttemptThatDiedUnfinishedStillEntersSafeMode() {
        val install = install()
        val failed = StartupAttempt(1_000L, 10, "N20261101")
        assertTrue(previousStartupIncomplete(true, pending = failed, livePid = null, install = install))
        assertTrue(previousStartupIncomplete(true, pending = failed, livePid = 20, install = install))
        assertFalse(previousStartupIncomplete(true, pending = failed, livePid = 10, install = install))
    }

    @Test fun aCurrentBuildMarkerSurvivesABackwardClockCorrection() {
        // Its install identity decides staleness, so a rolled-back timestamp and an existing
        // acknowledgement watermark cannot hide an unfinished startup of this build.
        val install = install()
        val rolledBack =
            StartupAttempt(500L, 10, "N20261101", versionCode = 100L, installedSinceMs = 1_000L)
        assertFalse(isStaleAttempt(rolledBack, install))
        assertFalse(staleAttemptUnacknowledged(rolledBack, install, acknowledgedThroughMs = 9_999L))
        assertTrue(
            previousStartupIncomplete(true, pending = rolledBack, livePid = null, install = install),
        )
        assertTrue(
            previousStartupIncomplete(true, pending = rolledBack, livePid = 20, install = install),
        )
    }

    @Test fun aMarkerFromAnotherBuildOrInstallIsStaleEvenWithALaterTimestamp() {
        val install = install()
        val otherBuild =
            StartupAttempt(2_000L, 10, "N20261101", versionCode = 99L, installedSinceMs = 1_000L)
        val otherName =
            StartupAttempt(2_000L, 10, "N20261031", versionCode = 100L, installedSinceMs = 1_000L)
        val otherInstall =
            StartupAttempt(2_000L, 10, "N20261101", versionCode = 100L, installedSinceMs = 500L)
        assertTrue(isStaleAttempt(otherBuild, install))
        assertTrue(isStaleAttempt(otherName, install))
        assertTrue(isStaleAttempt(otherInstall, install))
        assertFalse(
            previousStartupIncomplete(true, pending = otherBuild, livePid = null, install = install),
        )
    }

    @Test fun installStampsAreComparedOnlyWhenBothSidesRecordedOne() {
        val install = install()
        // The current install stamp is unknown, e.g. PackageManager failed at read time.
        val unstampedInstall = install(installedSinceMs = 0L)
        val stamped =
            StartupAttempt(2_000L, 10, "N20261101", versionCode = 100L, installedSinceMs = 500L)
        assertFalse(isStaleAttempt(stamped, unstampedInstall))
        assertTrue(
            previousStartupIncomplete(
                true, pending = stamped, livePid = null, install = unstampedInstall,
            ),
        )
        // The marker's stamp is unknown.
        val unstamped = StartupAttempt(2_000L, 10, "N20261101", versionCode = 100L, installedSinceMs = 0L)
        assertFalse(isStaleAttempt(unstamped, install))
        assertTrue(
            previousStartupIncomplete(true, pending = unstamped, livePid = null, install = install),
        )
        // Both unknown: the build identity alone decides.
        assertFalse(isStaleAttempt(unstamped, unstampedInstall))
        // Both known and different: stale.
        assertTrue(isStaleAttempt(stamped, install))
    }

    @Test fun aLegacyMarkerWithoutInstallIdentityStillUsesClockAndVersionInference() {
        val install = install()
        assertTrue(isStaleAttempt(StartupAttempt(500L, 10, "N20261031"), install))
        assertTrue(isStaleAttempt(StartupAttempt(2_000L, 10, "N20261031"), install))
        assertFalse(isStaleAttempt(StartupAttempt(2_000L, 10, "N20261101"), install))
    }

    @Test fun aCurrentBuildRecordIsNeverFromAnEarlierVersionWhateverTheClock() {
        val install = install()
        val record = crashRecord(timestampMs = 500L, versionCode = 100L, installedSinceMs = 1_000L)
        assertFalse(record.isFromEarlierVersion(install))
        assertTrue(record.copy(versionCode = 99L).isFromEarlierVersion(install))
        assertTrue(record.copy(versionCode = UNKNOWN_VERSION_CODE).isFromEarlierVersion(install))
        assertFalse(
            crashRecord(timestampMs = 2_000L, versionCode = UNKNOWN_VERSION_CODE)
                .isFromEarlierVersion(install),
        )
    }

    @Test fun anEqualVersionCodeAloneDoesNotMakeARecordCurrent() {
        val install = install()
        val sameCode = crashRecord(timestampMs = 2_000L, versionCode = 100L, installedSinceMs = 1_000L)
        assertTrue(sameCode.copy(versionName = "N20261031").isFromEarlierVersion(install))
        assertTrue(sameCode.copy(installedSinceMs = 500L).isFromEarlierVersion(install))
        assertFalse(sameCode.isFromEarlierVersion(install))
        // An unknown install stamp can never prove a different install.
        assertFalse(sameCode.copy(installedSinceMs = 0L).isFromEarlierVersion(install))
    }

    @Test fun aSingleStartupCrashIsOnlyOneUnluckyLaunch() {
        val starts = listOf(start(1_000L, 10))
        val crashes = listOf(crashRecord(timestampMs = 5_000L, versionCode = 100L, pid = 10))
        assertEquals(1, consecutiveStartupCrashes(starts, crashes, install(), nowMs = 100_000L))
    }

    @Test fun twoBackToBackStartupCrashesInTheSameBuildAreACrashLoop() {
        val starts = listOf(start(1_000L, 10), start(6_000L, 11))
        val crashes = listOf(
            crashRecord(timestampMs = 5_000L, versionCode = 100L, pid = 10),
            crashRecord(timestampMs = 9_000L, versionCode = 100L, pid = 11),
        )
        assertEquals(2, consecutiveStartupCrashes(starts, crashes, install(), nowMs = 100_000L))
    }

    @Test fun aCrashLongAfterItsProcessStartedIsARuntimeCrash() {
        val starts = listOf(start(1_000L, 10))
        val crashes = listOf(crashRecord(timestampMs = 60_000L, versionCode = 100L, pid = 10))
        assertEquals(0, consecutiveStartupCrashes(starts, crashes, install(), nowMs = 100_000L))
    }

    @Test fun anUncleanExitWithoutCrashEvidenceIsNeverACrashLoop() {
        // A swipe-away, force-stop, reboot or low-memory kill leaves starts but no crash record.
        val starts = listOf(start(1_000L, 10), start(6_000L, 11))
        assertEquals(0, consecutiveStartupCrashes(starts, emptyList(), install(), nowMs = 100_000L))
    }

    @Test fun aCompletedStartupBetweenCrashesBreaksTheStreak() {
        val starts = listOf(start(1_000L, 10), start(20_000L, 11), start(40_000L, 12))
        val crashes = listOf(
            crashRecord(timestampMs = 5_000L, versionCode = 100L, pid = 10),
            crashRecord(timestampMs = 45_000L, versionCode = 100L, pid = 12),
        )
        // The middle attempt ran past its startup window without crashing.
        assertEquals(1, consecutiveStartupCrashes(starts, crashes, install(), nowMs = 100_000L))
    }

    @Test fun aHealthyLaunchAfterTheNewestCrashEndsTheStreak() {
        val starts = listOf(start(1_000L, 10), start(6_000L, 11), start(50_000L, 12))
        val crashes = listOf(
            crashRecord(timestampMs = 5_000L, versionCode = 100L, pid = 10),
            crashRecord(timestampMs = 9_000L, versionCode = 100L, pid = 11),
        )
        assertEquals(0, consecutiveStartupCrashes(starts, crashes, install(), nowMs = 100_000L))
    }

    @Test fun onlyCrashExitReasonsCountAsEvidence() {
        val starts = listOf(start(1_000L, 10), start(6_000L, 11))
        val exits = listOf(
            crashRecord(timestampMs = 5_000L, versionCode = 100L, pid = 10,
                kind = ProcessExitKind.LOW_MEMORY),
            crashRecord(timestampMs = 9_000L, versionCode = 100L, pid = 11,
                kind = ProcessExitKind.INITIALIZATION_FAILURE),
        )
        assertEquals(0, consecutiveStartupCrashes(starts, exits, install(), nowMs = 100_000L))
    }

    @Test fun aCrashFromAnEarlierBuildDoesNotCount() {
        val starts = listOf(start(1_000L, 10), start(6_000L, 11))
        val crashes = listOf(
            crashRecord(timestampMs = 5_000L, versionCode = 99L, pid = 10),
            crashRecord(timestampMs = 9_000L, versionCode = 99L, pid = 11),
        )
        assertEquals(0, consecutiveStartupCrashes(starts, crashes, install(), nowMs = 100_000L))
    }

    @Test fun aStartStillInsideItsWindowDoesNotBreakTheStreak() {
        // The newest attempt may still crash; it is not a completed startup yet.
        val starts = listOf(start(1_000L, 10), start(98_000L, 11))
        val crashes = listOf(crashRecord(timestampMs = 5_000L, versionCode = 100L, pid = 10))
        assertEquals(1, consecutiveStartupCrashes(starts, crashes, install(), nowMs = 100_000L))
    }

    @Test fun anAcknowledgedCrashLoopStaysQuietWhenClosedAndReopened() {
        // Closing the screen acknowledges through the newest listed record; on reopen the same
        // evidence must not re-evaluate into a loop while the new attempt is inside its window.
        val starts = listOf(start(1_000L, 10), start(6_000L, 11), start(9_500L, 12))
        val crashes = listOf(
            crashRecord(timestampMs = 5_000L, versionCode = 100L, pid = 10),
            crashRecord(timestampMs = 9_000L, versionCode = 100L, pid = 11),
        )
        assertEquals(2, consecutiveStartupCrashes(starts, crashes, install(), nowMs = 10_000L))
        assertEquals(
            0,
            consecutiveStartupCrashes(
                starts, crashes, install(), nowMs = 10_000L, acknowledgedThroughMs = 9_000L,
            ),
        )
    }

    @Test fun aRuntimeCrashOfAJournaledHealthyStartDoesNotCount() {
        // The first frame ended startup; a death still inside the wall-clock window is a runtime
        // crash, so the start that journaled its frame matches nothing.
        val starts = listOf(
            start(1_000L, 10, healthyAtMs = 3_000L),
            start(50_000L, 11, healthyAtMs = 52_000L),
        )
        val crashes = listOf(crashRecord(timestampMs = 8_000L, versionCode = 100L, pid = 10))
        assertEquals(0, consecutiveStartupCrashes(starts, crashes, install(), nowMs = 100_000L))
    }

    @Test fun aJournaledFirstFrameCompletesTheNewestStartInsideItsWindow() {
        // Without the journal stamp the newest attempt would still be "inside its window" and the
        // two older startup crashes would keep the gate armed; the stamped frame ends the streak.
        val starts = listOf(
            start(1_000L, 10), start(6_000L, 11), start(9_500L, 12, healthyAtMs = 9_900L),
        )
        val crashes = listOf(
            crashRecord(timestampMs = 5_000L, versionCode = 100L, pid = 10),
            crashRecord(timestampMs = 9_000L, versionCode = 100L, pid = 11),
        )
        assertEquals(0, consecutiveStartupCrashes(starts, crashes, install(), nowMs = 10_000L))
    }

    @Test fun aHealthyStartThatCrashedAtRuntimeKeepsTheStreakAtOne() {
        // The same attempt appears twice: journaled with its first-frame stamp and again from the
        // last-start marker without one. The merged copy must not let the runtime death count, and
        // a start that died does not prove the launch survived either — only the real startup
        // crash remains.
        val starts = listOf(
            start(1_000L, 10),
            start(6_000L, 11, healthyAtMs = 7_000L),
            start(6_000L, 11),
        )
        val crashes = listOf(
            crashRecord(timestampMs = 3_000L, versionCode = 100L, pid = 10),
            crashRecord(timestampMs = 13_000L, versionCode = 100L, pid = 11),
        )
        assertEquals(1, consecutiveStartupCrashes(starts, crashes, install(), nowMs = 100_000L))
    }

    private fun start(timestampMs: Long, pid: Int, healthyAtMs: Long = 0L) =
        StartupAttempt(
            timestampMs, pid, "N20261101",
            versionCode = 100L, installedSinceMs = 1_000L, healthyAtMs = healthyAtMs,
        )

    private fun install(
        installedSinceMs: Long = 1_000L,
        versionCode: Long = 100L,
        versionName: String = "N20261101",
    ) = InstallWindow(installedSinceMs, versionCode, versionName)

    private fun crashRecord(
        timestampMs: Long,
        versionCode: Long,
        pid: Int = 10,
        kind: ProcessExitKind = ProcessExitKind.CRASH,
        versionName: String = "N20261101",
        installedSinceMs: Long = 0L,
    ) = CrashRecord(
        timestampMs = timestampMs,
        pid = pid,
        kind = kind,
        status = 0,
        captured = true,
        process = "moe.rukamori.archivetune",
        thread = "main",
        body = "",
        truncated = false,
        versionName = versionName,
        versionCode = versionCode,
        installedSinceMs = installedSinceMs,
    )
}
