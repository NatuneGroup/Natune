package moe.rukamori.archivetune.shared.domain.diagnostics

import kotlin.math.abs

const val MAX_CRASH_LOG_RECORDS = 20
const val MAX_CRASH_LOG_RECORD_CHARS = 24_000
const val MAX_CRASH_LOG_RECORD_BYTES = MAX_CRASH_LOG_RECORD_CHARS * 4 + 4_096

/**
 * What died. The names are stable file format; the hosts pick the closest kind, so a Kotlin/Native
 * failure is [KOTLIN], an Objective-C exception is [NS] and a JVM uncaught exception is [JAVA].
 */
enum class CrashLogKind { JAVA, KOTLIN, NS, NATIVE, ANR, OTHER }

/**
 * One saved crash record, the shared-host counterpart of the Android startup record store. Every
 * host capture writes one bounded file per death instead of only the newest report, so the Crash
 * logs page can list them. [body] is already redacted by the caller.
 */
data class CrashLogRecord(
    val timestampMs: Long,
    val pid: Int,
    val kind: CrashLogKind,
    val status: Int,
    val process: String,
    val thread: String,
    val body: String,
    val truncated: Boolean,
    val versionName: String,
) {
    val id: String get() = "$timestampMs-$pid-${kind.name.lowercase().replace('_', '-')}"
}

private const val RECORD_HEADER = "natune-crash-record-v1"
private const val BODY_SEPARATOR = "\n---\n"
private const val RECORD_PREFIX = "crash-record-"
private const val RECORD_SUFFIX = ".txt"

/** Two reports of the same death inside a minute count once, as on Android. */
private const val SAME_EVENT_WINDOW_MS = 60_000L

/** A report that captured no pid (0) is indistinguishable, so pid matching stays open on both sides. */
fun CrashLogRecord.isSameEventAs(pid: Int, kind: CrashLogKind, timestampMs: Long): Boolean =
    this.kind == kind && (this.pid == pid || this.pid == 0 || pid == 0) &&
        abs(this.timestampMs - timestampMs) <= SAME_EVENT_WINDOW_MS

/** The on-disk form: single-line fields, then the body after its separator, bounded at encode. */
fun encodeCrashLogRecord(record: CrashLogRecord): String {
    val header = listOf(
        RECORD_HEADER,
        "timestampMs=${record.timestampMs}",
        "pid=${record.pid}",
        "kind=${record.kind.name}",
        "status=${record.status}",
        "process=${singleLine(record.process, 128)}",
        "thread=${singleLine(record.thread, 128)}",
        "versionName=${singleLine(record.versionName, 128)}",
        "truncated=${record.truncated || record.body.length > MAX_CRASH_LOG_RECORD_CHARS}",
    )
    return header.joinToString("\n") + BODY_SEPARATOR + record.body.take(MAX_CRASH_LOG_RECORD_CHARS)
}

fun decodeCrashLogRecord(text: String): CrashLogRecord? {
    val split = text.indexOf(BODY_SEPARATOR)
    if (split < 0) return null
    val lines = text.substring(0, split).split('\n')
    if (lines.firstOrNull() != RECORD_HEADER) return null
    val fields = lines.drop(1).mapNotNull { line ->
        val separator = line.indexOf('=')
        if (separator > 0) line.substring(0, separator) to line.substring(separator + 1) else null
    }.toMap()
    val timestamp = fields["timestampMs"]?.toLongOrNull() ?: return null
    val pid = fields["pid"]?.toIntOrNull() ?: return null
    val kind = CrashLogKind.entries.firstOrNull { it.name == fields["kind"] } ?: return null
    return CrashLogRecord(
        timestampMs = timestamp,
        pid = pid,
        kind = kind,
        status = fields["status"]?.toIntOrNull() ?: 0,
        process = fields["process"].orEmpty(),
        thread = fields["thread"].orEmpty(),
        body = text.substring(split + BODY_SEPARATOR.length),
        truncated = fields["truncated"] == "true",
        versionName = fields["versionName"].orEmpty(),
    )
}

fun crashLogRecordFileName(id: String): String = RECORD_PREFIX + id + RECORD_SUFFIX

fun isCrashLogRecordFileName(name: String): Boolean =
    name.startsWith(RECORD_PREFIX) && name.endsWith(RECORD_SUFFIX)

/** Pruning sorts by the timestamp in the name, so a file that cannot be parsed is pruned first. */
fun crashLogRecordFileTimestamp(name: String): Long? =
    if (isCrashLogRecordFileName(name)) {
        name.removePrefix(RECORD_PREFIX).substringBefore('-').toLongOrNull()
    } else {
        null
    }

/** Ids come from navigation arguments, so only the characters [encodeCrashLogRecord] produces may name a file. */
fun isValidCrashLogRecordId(id: String): Boolean =
    id.isNotEmpty() && id.length <= 64 && id.all { it.isDigit() || it in 'a'..'z' || it == '-' }

/**
 * The bounded per-host store of crash records. [save] is a plain call because a capture runs on a
 * dying thread; it writes one file atomically and prunes to the newest [MAX_CRASH_LOG_RECORDS].
 * The suspend reads run on the implementation's worker, never the caller's main thread.
 */
interface CrashLogStore {
    /** One atomic write plus pruning; false when the record could not be saved. */
    fun save(record: CrashLogRecord): Boolean

    /** Newest first. */
    suspend fun list(): List<CrashLogRecord>

    suspend fun read(id: String): CrashLogRecord?

    /** False while records remain on disk. */
    suspend fun clear(): Boolean
}

private fun singleLine(value: String, maxChars: Int): String =
    value.replace('\r', ' ').replace('\n', ' ').take(maxChars)
