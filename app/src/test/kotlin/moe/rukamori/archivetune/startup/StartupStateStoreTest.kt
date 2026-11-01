package moe.rukamori.archivetune.startup

import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StartupStateStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun pendingSurvivesProcessRecreationAndOnlyItsOwnHealthyCallbackCanClearIt() {
        val directory = temporary.newFolder()
        val first = StartupStateStore(directory)
        val attempt = StartupAttempt(100L, 10, "previous-build")
        first.begin(attempt)
        val nextProcess = StartupStateStore(directory)
        assertTrue(nextProcess.pendingExists())
        assertEquals(attempt, nextProcess.pendingAttempt())
        assertFalse(nextProcess.markHealthy(attempt.copy(pid = 11)))
        assertTrue(nextProcess.pendingExists())
        assertTrue(nextProcess.markHealthy(attempt))
        assertFalse(nextProcess.pendingExists())
        assertEquals(attempt, nextProcess.lastAttempt())
    }

    @Test fun retryClearsOnlyTheSentinelAndKeepsTheJavaEvidence() {
        val store = StartupStateStore(temporary.newFolder())
        store.begin(StartupAttempt(100L, 10, "build"))
        store.captureJava(101L, "main", IllegalStateException("fixture failure"))
        assertTrue(store.clearPending())
        assertFalse(store.pendingExists())
        assertNotNull(store.javaCrash())
        assertEquals(101L, store.javaCrash()?.timestampMs)
        assertEquals("main", store.javaCrash()?.thread)
        assertTrue(store.javaCrash()!!.stack.contains("IllegalStateException: fixture failure"))
        assertFalse(store.javaCrash()!!.truncated)
    }

    @Test fun backgroundRestartCannotHideAnUnreviewedStartupFailure() {
        val directory = temporary.newFolder()
        val failed = StartupAttempt(100L, 10, "N20261101")
        StartupStateStore(directory).begin(failed)
        val restarted = StartupStateStore(directory)
        val later = StartupAttempt(200L, 11, "N20261101")
        restarted.begin(later)
        assertEquals(failed, restarted.pendingAttempt())
        assertEquals(failed, restarted.lastAttempt())
        assertFalse(restarted.markHealthy(later))
        assertTrue(restarted.pendingExists())
    }

    @Test fun aHealthyNewerBuildRetiresAnEarlierBuildsPendingAttempt() {
        val directory = temporary.newFolder()
        StartupStateStore(directory).begin(StartupAttempt(100L, 10, "N20261031"))
        val restarted = StartupStateStore(directory)
        val current = StartupAttempt(200L, 11, "N20261101")
        restarted.begin(current)
        assertTrue(restarted.markHealthy(current))
        assertFalse(restarted.pendingExists())
    }

    @Test fun aHealthyLaunchRetiresAnUnreadablePendingMarker() {
        val directory = temporary.newFolder()
        File(directory, "startup_pending").writeText("partial")
        val restarted = StartupStateStore(directory)
        assertTrue(restarted.markHealthy(StartupAttempt(200L, 11, "N20261101")))
        assertFalse(restarted.pendingExists())
    }

    @Test fun aMarkerRoundTripsItsInstallIdentity() {
        val directory = temporary.newFolder()
        val attempt = StartupAttempt(100L, 10, "N20261101", versionCode = 100L, installedSinceMs = 1_000L)
        StartupStateStore(directory).begin(attempt)
        assertEquals(attempt, StartupStateStore(directory).pendingAttempt())
        assertEquals(attempt, StartupStateStore(directory).lastAttempt())
    }

    @Test fun aLegacyThreeLineMarkerStillParses() {
        val directory = temporary.newFolder()
        File(directory, "startup_pending").writeText("100\n10\nN20261031")
        val attempt = StartupStateStore(directory).pendingAttempt()!!
        assertEquals(100L, attempt.timestampMs)
        assertEquals(10, attempt.pid)
        assertEquals("N20261031", attempt.version)
        assertEquals(UNKNOWN_VERSION_CODE, attempt.versionCode)
        assertEquals(0L, attempt.installedSinceMs)
    }

    @Test fun aHealthyProcessCannotRetireAnotherProcesssMarkerOfTheSameInstall() {
        val directory = temporary.newFolder()
        StartupStateStore(directory)
            .begin(StartupAttempt(100L, 10, "N20261101", versionCode = 100L, installedSinceMs = 1_000L))
        val restarted = StartupStateStore(directory)
        val sameInstall = StartupAttempt(200L, 11, "N20261101", versionCode = 100L, installedSinceMs = 1_000L)
        assertFalse(restarted.markHealthy(sameInstall))
        assertTrue(restarted.pendingExists())
    }

    @Test fun aHealthyLaunchRetiresAMarkerFromAnotherInstall() {
        val directory = temporary.newFolder()
        StartupStateStore(directory)
            .begin(StartupAttempt(100L, 10, "N20261101", versionCode = 100L, installedSinceMs = 500L))
        val restarted = StartupStateStore(directory)
        val current = StartupAttempt(200L, 11, "N20261101", versionCode = 100L, installedSinceMs = 1_000L)
        assertTrue(restarted.markHealthy(current))
        assertFalse(restarted.pendingExists())
    }

    @Test fun javaCaptureIsBoundedAndContainsTheTimestampEvenWithALargeMessage() {
        val directory = temporary.newFolder()
        val store = StartupStateStore(directory)
        store.captureJava(123L, "main", RuntimeException("x".repeat(100_000)))
        val report = store.javaCrash()!!
        assertEquals(123L, report.timestampMs)
        assertTrue(report.stack.startsWith("java.lang.RuntimeException"))
        assertTrue(report.truncated)
        assertTrue(directory.listFiles()!!.filter { it.isFile }.sumOf { it.length() } <= StartupStateStore.MAX_JAVA_CHARS * 4L)
    }

    @Test fun acknowledgementDoesNotForgetANewerReviewedExit() {
        val store = StartupStateStore(temporary.newFolder())
        assertEquals(0L, store.acknowledgedThrough())
        store.acknowledgeThrough(200L)
        store.acknowledgeThrough(100L)
        assertEquals(200L, store.acknowledgedThrough())
    }

    @Test fun interruptedMarkerRemainsDetectableWithoutAParseableBody() {
        val directory = temporary.newFolder()
        File(directory, "startup_pending").writeText("partial")
        val store = StartupStateStore(directory)
        assertTrue(store.pendingExists())
        assertNull(store.pendingAttempt())
    }

    @Test fun traceReadsNeverConsumeMoreThanTheBudget() {
        val stream = ByteArrayInputStream("abcdefgh".toByteArray())
        val capped = stream.readCappedText(4)
        assertEquals("abcd", capped.text)
        assertTrue(capped.truncated)
        assertEquals(4, stream.available())
        val complete = ByteArrayInputStream("ok".toByteArray()).readCappedText(4)
        assertEquals("ok", complete.text)
        assertFalse(complete.truncated)
    }
}
