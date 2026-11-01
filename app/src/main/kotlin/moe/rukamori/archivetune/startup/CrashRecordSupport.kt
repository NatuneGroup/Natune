package moe.rukamori.archivetune.startup

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.R

internal fun currentInstall(context: Context): InstallWindow {
    val installedSince = runCatching { packageInfo(context).lastUpdateTime }.getOrDefault(0L)
    return InstallWindow(
        installedSinceMs = installedSince,
        versionCode = BuildConfig.VERSION_CODE.toLong(),
        versionName = BuildConfig.VERSION_NAME,
    )
}

@Suppress("DEPRECATION")
private fun packageInfo(context: Context): PackageInfo =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }

/**
 * Moves Android exit history and a legacy single-slot Java crash into the shared store. Each exit is
 * stored once: a watermark keeps later launches from reading history that was already imported or
 * cleared. Returns true when this device cannot read exit history at all.
 */
internal fun importCrashRecords(context: Context, store: StartupStateStore): Boolean {
    val install = currentInstall(context)
    importLegacyJavaCrash(context, store)
    val watermark = store.exitsImportedThrough()
    val history = historicalProcessExits(context, watermark)
    if (history.unavailable) return true
    if (history.exits.isEmpty()) return false
    var existing = store.listRecords()
    var imported = watermark
    for (exit in history.exits.sortedBy { it.timestampMs }) {
        if (existing.none { it.isSameEventAs(exit.pid, exit.kind, exit.timestampMs) }) {
            val record = exitRecord(context, exit, install)
            if (!store.saveRecord(record)) break
            existing = existing + record
        }
        imported = maxOf(imported, exit.timestampMs)
    }
    if (imported > watermark) store.markExitsImportedThrough(imported)
    return false
}

private fun importLegacyJavaCrash(context: Context, store: StartupStateStore) {
    val legacy = store.javaCrash() ?: return
    val present = store.listRecords().any { it.kind == ProcessExitKind.CRASH && it.timestampMs == legacy.timestampMs }
    val saved = present || store.saveRecord(
        CrashRecord(
            timestampMs = legacy.timestampMs,
            pid = 0,
            kind = ProcessExitKind.CRASH,
            status = 0,
            captured = true,
            process = context.packageName,
            thread = legacy.thread,
            body = legacy.stack,
            truncated = legacy.truncated,
            versionName = "",
            versionCode = UNKNOWN_VERSION_CODE,
        ),
    )
    if (saved) store.removeJavaCrash()
}

private fun exitRecord(context: Context, exit: HistoricalProcessExit, install: InstallWindow): CrashRecord {
    // An exit before the install time came from an earlier build whose version name is no longer knowable.
    val earlier = exit.timestampMs < install.installedSinceMs
    return CrashRecord(
        timestampMs = exit.timestampMs,
        pid = exit.pid,
        kind = exit.kind,
        status = exit.status,
        captured = false,
        process = context.packageName,
        thread = "",
        body = exitBody(context, exit),
        truncated = exit.trace?.truncated == true || exit.tombstone?.incomplete == true,
        versionName = if (earlier) "" else BuildConfig.VERSION_NAME,
        versionCode = if (earlier) UNKNOWN_VERSION_CODE else install.versionCode,
        installedSinceMs = if (earlier) 0L else install.installedSinceMs,
    )
}

private fun exitBody(context: Context, exit: HistoricalProcessExit): String = buildString {
    if (exit.description.isNotBlank()) appendLine(exit.description)
    if (exit.kind == ProcessExitKind.LOW_MEMORY) appendLine(context.getString(R.string.startup_low_memory_note))
    exit.trace?.let { appendLine(it.text) }
    exit.tombstone?.let { append(renderNativeTombstone(context, it)) }
    val traceExpected = exit.kind == ProcessExitKind.NATIVE_CRASH || exit.kind == ProcessExitKind.ANR
    if (traceExpected && exit.trace == null && exit.tombstone == null) {
        appendLine(context.getString(R.string.startup_trace_unavailable))
    }
}.trimEnd()

internal fun renderNativeTombstone(context: Context, tombstone: NativeTombstone): String = buildString {
    appendLine(context.getString(R.string.startup_native_summary, tombstone.fingerprint, tombstone.timestamp,
        tombstone.pid.toString(), tombstone.tid.toString()))
    tombstone.signal?.let {
        appendLine(context.getString(R.string.startup_native_signal, it.name, it.number.toString(), it.codeName, it.code.toString()))
    }
    if (tombstone.abortMessage.isNotBlank()) appendLine(context.getString(R.string.startup_native_abort, tombstone.abortMessage))
    tombstone.causes.filter { it.isNotBlank() }.forEach { appendLine(it) }
    for (thread in tombstone.threads) {
        appendLine(context.getString(R.string.startup_native_thread, thread.id.toString(), thread.name))
        thread.frames.forEachIndexed { index, frame ->
            appendLine(context.getString(R.string.startup_native_frame, index.toString(), frame.pc.toULong().toString(16),
                frame.file, frame.function, frame.offset.toULong().toString(), frame.buildId))
        }
    }
}.trimEnd()

/** Device locale with the system 12/24-hour preference; the pattern comes from the locale, not a fixed format. */
internal fun formatCrashTime(context: Context, timestampMs: Long): String {
    val locale = context.resources.configuration.locales[0]
    val skeleton = if (DateFormat.is24HourFormat(context)) "yMMMdHms" else "yMMMdhms"
    return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(Date(timestampMs))
}

internal fun versionLabel(context: Context, record: CrashRecord, install: InstallWindow): String {
    val version = if (record.versionName.isNotBlank()) {
        context.getString(R.string.crash_record_version, record.versionName, record.versionCode.toString())
    } else {
        context.getString(R.string.crash_record_version_unknown)
    }
    return if (record.isFromEarlierVersion(install)) {
        context.getString(R.string.crash_record_earlier_version, version)
    } else {
        version
    }
}

internal fun ProcessExitKind.labelRes(): Int = when (this) {
    ProcessExitKind.CRASH -> R.string.startup_exit_java
    ProcessExitKind.NATIVE_CRASH -> R.string.startup_exit_native
    ProcessExitKind.ANR -> R.string.startup_exit_anr
    ProcessExitKind.INITIALIZATION_FAILURE -> R.string.startup_exit_initialization
    ProcessExitKind.LOW_MEMORY -> R.string.startup_exit_low_memory
}
