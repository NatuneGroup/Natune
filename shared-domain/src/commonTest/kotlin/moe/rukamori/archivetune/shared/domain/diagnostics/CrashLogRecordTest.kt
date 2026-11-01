package moe.rukamori.archivetune.shared.domain.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrashLogRecordTest {
    private fun record(
        timestampMs: Long = 1_700_000_000_000L,
        pid: Int = 4242,
        kind: CrashLogKind = CrashLogKind.JAVA,
        status: Int = 1,
        process: String = "moe.rukamori.archivetune",
        thread: String = "main",
        body: String = "FATAL EXCEPTION\njava.lang.IllegalStateException: broken",
        truncated: Boolean = false,
        versionName: String = "1.2.3",
    ) = CrashLogRecord(timestampMs, pid, kind, status, process, thread, body, truncated, versionName)

    @Test
    fun encodedRecordDecodesToTheSameFields() {
        val record = record(body = "first line\nsecond line\n\tat app.Main.run(Main.kt:7)")

        val decoded = decodeCrashLogRecord(encodeCrashLogRecord(record))

        assertEquals(record, decoded)
        assertEquals(record.id, decoded?.id)
    }

    @Test
    fun recordIdsAreStableFileSafeNames() {
        assertEquals("1700000000000-4242-java", record().id)
        assertEquals("1700000000000-4242-ns", record(kind = CrashLogKind.NS).id)
        assertEquals("1700000000000-4242-anr", record(kind = CrashLogKind.ANR).id)
    }

    @Test
    fun headerFieldsKeepASingleLine() {
        val record = record(process = "app\nmoe.rukamori", thread = "worker\rthread", versionName = "1\n2")

        val decoded = decodeCrashLogRecord(encodeCrashLogRecord(record))

        assertEquals("app moe.rukamori", decoded?.process)
        assertEquals("worker thread", decoded?.thread)
        assertEquals("1 2", decoded?.versionName)
    }

    @Test
    fun textThatIsNotARecordDecodesToNull() {
        assertNull(decodeCrashLogRecord(""))
        assertNull(decodeCrashLogRecord("crash-report\nno fields"))
        assertNull(decodeCrashLogRecord("natune-crash-record-v1\ntimestampMs=abc\npid=1\nkind=JAVA\n---\nbody"))
        assertNull(decodeCrashLogRecord("natune-crash-record-v1\ntimestampMs=1\npid=1\nkind=NOT_A_KIND\n---\nbody"))
        assertNull(decodeCrashLogRecord("natune-crash-record-v2\ntimestampMs=1\npid=1\nkind=JAVA\n---\nbody"))
    }

    @Test
    fun recordFileNamesRoundTripAndTimestampSorts() {
        val name = crashLogRecordFileName(record().id)

        assertEquals("crash-record-1700000000000-4242-java.txt", name)
        assertTrue(isCrashLogRecordFileName(name))
        assertFalse(isCrashLogRecordFileName("crash_report.txt"))
        assertFalse(isCrashLogRecordFileName("$name.tmp"))
        assertEquals(1_700_000_000_000L, crashLogRecordFileTimestamp(name))
        assertNull(crashLogRecordFileTimestamp("crash-record-abc.txt"))
    }

    @Test
    fun recordIdsRejectWhatNavigationMustNotPassToAFileName() {
        assertTrue(isValidCrashLogRecordId("1700000000000-4242-java"))
        assertFalse(isValidCrashLogRecordId(""))
        assertFalse(isValidCrashLogRecordId("../crash"))
        assertFalse(isValidCrashLogRecordId("a/b"))
        assertFalse(isValidCrashLogRecordId("a.b"))
        assertFalse(isValidCrashLogRecordId("UPPER"))
        assertFalse(isValidCrashLogRecordId("x".repeat(65)))
    }

    @Test
    fun reportsOfOneDeathInsideAMinuteCountOnce() {
        val record = record(timestampMs = 1_000_000L, pid = 7, kind = CrashLogKind.JAVA)

        assertTrue(record.isSameEventAs(pid = 7, kind = CrashLogKind.JAVA, timestampMs = 1_040_000L))
        assertTrue(record.isSameEventAs(pid = 0, kind = CrashLogKind.JAVA, timestampMs = 999_000L))
        assertFalse(record.isSameEventAs(pid = 8, kind = CrashLogKind.JAVA, timestampMs = 1_061_000L))
        assertFalse(record.isSameEventAs(pid = 7, kind = CrashLogKind.NS, timestampMs = 1_000_000L))
    }

    @Test
    fun anOversizedBodyIsBoundedAndFlagged() {
        val record = record(body = "x".repeat(MAX_CRASH_LOG_RECORD_CHARS + 100))

        val encoded = encodeCrashLogRecord(record)
        val decoded = decodeCrashLogRecord(encoded)

        assertTrue(decoded!!.truncated)
        assertEquals(MAX_CRASH_LOG_RECORD_CHARS, decoded.body.length)
    }

    @Test
    fun anExplicitlyTruncatedRecordKeepsItsFlag() {
        val decoded = decodeCrashLogRecord(encodeCrashLogRecord(record(body = "half", truncated = true)))

        assertTrue(decoded!!.truncated)
        assertEquals("half", decoded.body)
    }
}
