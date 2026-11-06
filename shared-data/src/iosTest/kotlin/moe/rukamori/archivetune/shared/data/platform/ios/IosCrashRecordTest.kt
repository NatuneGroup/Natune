package moe.rukamori.archivetune.shared.data.platform.ios

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosCrashRecordTest {
    @Test
    fun theFatalHandlerRecordBecomesAReport() {
        // The shape the Swift handler writes: epoch seconds, then the exception name and reason, truncated.
        val report = assertNotNull(
            parseNativeIosCrashRecord(
                "1700000000\nObjective-C uncaught exception: NSInternalInconsistencyException\nstate was invalid\n",
                appVersion = "1.2.3",
                packageName = "moe.rukamori.archivetune",
            ),
        )
        assertEquals(1_700_000_000_000L, report.capturedAtMillis)
        assertEquals("1.2.3", report.appVersion)
        assertEquals("moe.rukamori.archivetune", report.packageName)
        assertTrue(report.stackTrace.startsWith("Objective-C uncaught exception"))
        assertTrue(report.preview.isNotBlank())
    }

    @Test
    fun aRecordWithNoTimeKeepsItsText() {
        val report = assertNotNull(parseNativeIosCrashRecord("no time here", "1.2.3", "pkg"))
        assertEquals(0L, report.capturedAtMillis)
        assertEquals("", report.timestampText)
        assertTrue(report.stackTrace.contains("no time here"))
    }

    @Test
    fun aClearedRecordIsNotAReport() {
        assertNull(parseNativeIosCrashRecord("", "1.2.3", "pkg"))
        assertNull(parseNativeIosCrashRecord("\n\n", "1.2.3", "pkg"))
    }
}
