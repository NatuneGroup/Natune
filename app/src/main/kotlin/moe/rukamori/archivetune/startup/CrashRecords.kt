package moe.rukamori.archivetune.startup

import kotlin.math.abs

internal const val UNKNOWN_VERSION_CODE = -1L
internal const val MAX_CRASH_RECORDS = 20
internal const val MAX_RECORD_CHARS = 24_000
internal const val MAX_RECORD_BYTES = MAX_RECORD_CHARS * 4 + 4_096

/** Android can report a death that the handler already captured a few milliseconds earlier. */
private const val SAME_EVENT_WINDOW_MS = 60_000L

private const val RECORD_HEADER = "archivetune-crash-record-v1"
private const val BODY_SEPARATOR = "\n---\n"
private const val RECORD_PREFIX = "crash-record-"
private const val RECORD_SUFFIX = ".txt"

/**
 * One saved crash, native crash, ANR or exit reason. Captured Java crashes and imported Android
 * exits share this shape so the recovery report and the Crash logs page read one bounded store.
 */
internal data class CrashRecord(
    val timestampMs: Long,
    val pid: Int,
    val kind: ProcessExitKind,
    val status: Int,
    val captured: Boolean,
    val process: String,
    val thread: String,
    val body: String,
    val truncated: Boolean,
    val versionName: String,
    val versionCode: Long,
    val installedSinceMs: Long = 0L,
) {
    val id: String get() = "$timestampMs-$pid-${kind.name.lowercase().replace('_', '-')}"
}

/** The installed build and the time Android installed it. Nothing is persisted for this; it is read at each use. */
internal data class InstallWindow(val installedSinceMs: Long, val versionCode: Long, val versionName: String)

/** Version, install and process identity captured at the moment of a crash; empty when the caller cannot know it. */
internal data class CrashBuild(
    val versionName: String,
    val versionCode: Long,
    val process: String,
    val pid: Int,
    val installedSinceMs: Long = 0L,
) {
    companion object {
        val UNKNOWN = CrashBuild(versionName = "", versionCode = UNKNOWN_VERSION_CODE, process = "", pid = 0)
    }
}

/**
 * A record from before the installed build is still listed, labelled as an earlier version. Records
 * stamped with a version code are judged on the full build/install identity, never the wall clock:
 * an equal code still counts as earlier when the version name differs or both install stamps are
 * known and differ. Only records without a code fall back to comparing the install time.
 */
internal fun CrashRecord.isFromEarlierVersion(install: InstallWindow): Boolean {
    if (versionCode == UNKNOWN_VERSION_CODE) return timestampMs < install.installedSinceMs
    if (versionCode != install.versionCode) return versionCode < install.versionCode
    if (versionName.isNotEmpty() && versionName != install.versionName) return true
    return installedSinceMs != 0L && install.installedSinceMs != 0L &&
        installedSinceMs != install.installedSinceMs
}

/** Only failures of the installed build interrupt startup. Memory-pressure kills are listed for diagnosis. */
internal fun CrashRecord.interruptsStartup(install: InstallWindow): Boolean =
    kind != ProcessExitKind.LOW_MEMORY && !isFromEarlierVersion(install)

/** A captured Java crash and the Android exit for that same death share a kind; a legacy record has no pid. */
internal fun CrashRecord.isSameEventAs(pid: Int, kind: ProcessExitKind, timestampMs: Long): Boolean =
    this.kind == kind &&
        (this.pid == pid || this.pid == 0) &&
        abs(this.timestampMs - timestampMs) <= SAME_EVENT_WINDOW_MS

internal fun encodeCrashRecord(record: CrashRecord): String {
    val header = listOf(
        RECORD_HEADER,
        "timestampMs=${record.timestampMs}",
        "pid=${record.pid}",
        "kind=${record.kind.name}",
        "status=${record.status}",
        "captured=${record.captured}",
        "process=${singleLine(record.process, 128)}",
        "thread=${singleLine(record.thread, 128)}",
        "versionName=${singleLine(record.versionName, 128)}",
        "versionCode=${record.versionCode}",
        "installedSinceMs=${record.installedSinceMs}",
        "truncated=${record.truncated || record.body.length > MAX_RECORD_CHARS}",
    )
    return header.joinToString("\n") + BODY_SEPARATOR + record.body.take(MAX_RECORD_CHARS)
}

internal fun decodeCrashRecord(text: String): CrashRecord? {
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
    val kind = ProcessExitKind.entries.firstOrNull { it.name == fields["kind"] } ?: return null
    return CrashRecord(
        timestampMs = timestamp,
        pid = pid,
        kind = kind,
        status = fields["status"]?.toIntOrNull() ?: 0,
        captured = fields["captured"] == "true",
        process = fields["process"].orEmpty(),
        thread = fields["thread"].orEmpty(),
        body = text.substring(split + BODY_SEPARATOR.length),
        truncated = fields["truncated"] == "true",
        versionName = fields["versionName"].orEmpty(),
        versionCode = fields["versionCode"]?.toLongOrNull() ?: UNKNOWN_VERSION_CODE,
        installedSinceMs = fields["installedSinceMs"]?.toLongOrNull() ?: 0L,
    )
}

internal fun crashRecordFileName(id: String): String = RECORD_PREFIX + id + RECORD_SUFFIX

internal fun isCrashRecordFileName(name: String): Boolean =
    name.startsWith(RECORD_PREFIX) && name.endsWith(RECORD_SUFFIX)

/** Pruning sorts by the timestamp in the name, so a file that cannot be parsed is pruned first. */
internal fun crashRecordFileTimestamp(name: String): Long? =
    if (isCrashRecordFileName(name)) name.removePrefix(RECORD_PREFIX).substringBefore('-').toLongOrNull() else null

/** Ids come from navigation arguments, so only the characters [encodeCrashRecord] produces may name a file. */
internal fun isValidCrashRecordId(id: String): Boolean =
    id.isNotEmpty() && id.length <= 64 && id.all { it.isDigit() || it in 'a'..'z' || it == '-' }

private fun singleLine(value: String, maxChars: Int): String =
    value.replace('\r', ' ').replace('\n', ' ').take(maxChars)
