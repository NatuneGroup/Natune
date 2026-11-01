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

    private fun install(
        installedSinceMs: Long = 1_000L,
        versionCode: Long = 100L,
        versionName: String = "N20261101",
    ) = InstallWindow(installedSinceMs, versionCode, versionName)

    private fun crashRecord(
        timestampMs: Long,
        versionCode: Long,
        versionName: String = "N20261101",
        installedSinceMs: Long = 0L,
    ) = CrashRecord(
        timestampMs = timestampMs,
        pid = 10,
        kind = ProcessExitKind.CRASH,
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
