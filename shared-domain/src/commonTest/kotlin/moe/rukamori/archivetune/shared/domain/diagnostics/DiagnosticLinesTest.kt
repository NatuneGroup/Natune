package moe.rukamori.archivetune.shared.domain.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DiagnosticLinesTest {
    @Test
    fun multiLineCallFoldsBackIntoOneRecord() {
        val text = DiagnosticLines.encode(1_000L, 7L, DiagnosticLevel.ERROR, "Player", "first\nsecond") +
            DiagnosticLines.marker("--------- session") +
            DiagnosticLines.encode(1_001L, 7L, DiagnosticLevel.INFO, "Queue", "next")

        val records = DiagnosticLines.records(text)

        assertEquals(listOf("first\nsecond", "next"), records.map { it.message })
        assertEquals(DiagnosticLevel.ERROR, records.first().level)
        assertEquals("Player", records.first().tag)
        assertEquals(records.size, records.map { it.id }.toSet().size)
    }

    @Test
    fun clearedRecordsStayHiddenFromTheViewer() {
        val text = DiagnosticLines.encode(1_000L, 1L, DiagnosticLevel.INFO, "A", "old") +
            DiagnosticLines.encode(2_000L, 1L, DiagnosticLevel.INFO, "A", "new")

        assertEquals(listOf("new"), DiagnosticLines.records(text, notBefore = 1_500L).map { it.message })
    }

    @Test
    fun linesAreRedactedWhenEncoded() {
        val line = DiagnosticLines.encode(1L, 1L, DiagnosticLevel.WARNING, "Net", "Authorization: Bearer abc.def /home/someone/file")

        assertFalse("abc.def" in line)
        assertFalse("someone" in line)
    }

    @Test
    fun repeatsThatDifferOnlyInNumbersCollapse() {
        val text = (1..4).joinToString("") { attempt ->
            DiagnosticLines.encode(attempt.toLong(), 1L, DiagnosticLevel.WARNING, "Retry", "attempt $attempt failed")
        }

        val report = DiagnosticLines.collapseRepeats(text) { "t$it" }

        assertTrue("t1 W/Retry: attempt 1 failed" in report)
        assertTrue("repeated 3× more, last at t4" in report)
        assertFalse("attempt 2 failed" in report)
    }

    @Test
    fun levelAndQueryFilterTheViewer() {
        val records = listOf(
            DiagnosticRecord("1", 1L, DiagnosticLevel.INFO, "Queue", "loaded"),
            DiagnosticRecord("2", 2L, DiagnosticLevel.ERROR, "Player", "decode failed"),
        )

        assertEquals(listOf("2"), filterDiagnosticRecords(records, "decode", DiagnosticLevel.entries.toSet()).map { it.id })
        assertEquals(listOf("1"), filterDiagnosticRecords(records, "", setOf(DiagnosticLevel.INFO)).map { it.id })
    }

    @Test
    fun crashReportSurvivesItsStoredForm() {
        val report = CrashReport(
            capturedAtMillis = 42L,
            timestampText = "2026-10-26 10:00:00",
            appVersion = "15.1.0",
            packageName = "moe.rukamori.archivetune",
            deviceInfo = listOf("OS version" to "Linux 6.1", "Hardware" to "amd64"),
            stackTrace = CrashReport.redactedStack("java.lang.IllegalStateException: boom\n\tat /home/someone/App.kt:1"),
        )

        val decoded = assertNotNull(CrashReport.decode(CrashReport.encode(report)))

        assertEquals(report, decoded)
        assertEquals("java.lang.IllegalStateException: boom", decoded.preview)
        assertFalse("someone" in decoded.stackTrace)
        assertTrue(decoded.reportText("Natune").startsWith("Natune crash report\nTime: 2026-10-26 10:00:00\nApp: 15.1.0"))
    }
}
