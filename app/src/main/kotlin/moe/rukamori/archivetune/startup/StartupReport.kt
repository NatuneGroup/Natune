package moe.rukamori.archivetune.startup

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import java.text.DateFormat
import java.util.Date
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.logcat.DiagnosticRedaction

internal data class StartupReport(val text: String, val safeMode: Boolean, val reviewedThroughMs: Long)

internal fun liveMainProcessId(context: Context): Int? = runCatching {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    manager?.runningAppProcesses?.firstOrNull {
        it.processName == context.packageName && it.uid == Process.myUid()
    }?.pid
}.getOrNull()

internal fun previousLaunchReport(context: Context): StartupReport? {
    val store = StartupRecovery.store(context)
    val pending = store.pendingAttempt()
    val last = store.lastAttempt()
    val reviewed = store.acknowledgedThrough()
    val incomplete = previousStartupIncomplete(store.pendingExists(), pending?.pid, liveMainProcessId(context))
    val since = reportSince(last?.timestampMs, reviewed)
    val java = store.javaCrash()?.takeIf { it.timestampMs > since }
    val history = historicalProcessExits(context, since)
    if (!incomplete && java == null && history.exits.isEmpty()) return null

    val safeMode = incomplete || history.exits.any { it.kind == ProcessExitKind.INITIALIZATION_FAILURE }
    val text = buildString {
        appendLine(context.getString(R.string.startup_report_title))
        appendLine(context.getString(R.string.startup_report_build, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toString()))
        appendLine(context.getString(R.string.startup_report_device, Build.MANUFACTURER, Build.MODEL,
            Build.VERSION.RELEASE, Build.VERSION.SDK_INT.toString(), Build.SUPPORTED_ABIS.joinToString()))
        last?.let {
            appendLine(context.getString(R.string.startup_report_attempt, it.version, formatTime(it.timestampMs), it.pid.toString()))
        }
        appendLine()
        appendLine(context.getString(if (incomplete) R.string.startup_incomplete_explanation else R.string.startup_previous_exit_explanation))
        appendLine()
        if (java != null) {
            appendLine(context.getString(R.string.startup_report_java, formatTime(java.timestampMs), java.thread))
            appendLine(java.stack)
            if (java.truncated) appendLine(context.getString(R.string.startup_report_truncated))
        } else {
            appendLine(context.getString(R.string.startup_report_no_java))
        }
        for (exit in history.exits) {
            appendLine()
            appendLine(context.getString(R.string.startup_report_exit, formatTime(exit.timestampMs),
                context.getString(exit.kind.label()), exit.pid.toString(), exit.status.toString()))
            if (exit.description.isNotBlank()) appendLine(exit.description)
            if (exit.kind == ProcessExitKind.LOW_MEMORY) appendLine(context.getString(R.string.startup_low_memory_note))
            exit.trace?.let {
                appendLine(it.text)
                if (it.truncated) appendLine(context.getString(R.string.startup_report_truncated))
            }
            exit.tombstone?.let { appendNativeTombstone(context, it) }
            if (exit.kind in setOf(ProcessExitKind.NATIVE_CRASH, ProcessExitKind.ANR) && exit.trace == null && exit.tombstone == null) {
                appendLine(context.getString(R.string.startup_trace_unavailable))
            }
        }
        if (history.unavailable) appendLine(context.getString(R.string.startup_history_unavailable))
        else if (history.exits.isEmpty()) appendLine(context.getString(R.string.startup_history_empty))
    }
    val redacted = DiagnosticRedaction.redact(text)
    val bounded = if (redacted.length > MAX_REPORT_CHARS) {
        val notice = "\n" + context.getString(R.string.startup_report_truncated)
        redacted.take((MAX_REPORT_CHARS - notice.length).coerceAtLeast(0)) + notice.take(MAX_REPORT_CHARS)
    } else redacted
    val through = maxOf(reviewed, pending?.timestampMs ?: 0L, java?.timestampMs ?: 0L,
        history.exits.maxOfOrNull { it.timestampMs } ?: 0L)
    return StartupReport(bounded, safeMode, through)
}

internal fun previousStartupIncomplete(pendingExists: Boolean, pendingPid: Int?, livePid: Int?): Boolean =
    pendingExists && (livePid == null || pendingPid != livePid)

internal fun reportSince(lastStartMs: Long?, reviewedThroughMs: Long): Long =
    maxOf(reviewedThroughMs, (lastStartMs ?: 1L) - 1L)

private fun formatTime(timestampMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(timestampMs))

private fun ProcessExitKind.label(): Int = when (this) {
    ProcessExitKind.CRASH -> R.string.startup_exit_java
    ProcessExitKind.NATIVE_CRASH -> R.string.startup_exit_native
    ProcessExitKind.ANR -> R.string.startup_exit_anr
    ProcessExitKind.INITIALIZATION_FAILURE -> R.string.startup_exit_initialization
    ProcessExitKind.LOW_MEMORY -> R.string.startup_exit_low_memory
}

private fun StringBuilder.appendNativeTombstone(context: Context, tombstone: NativeTombstone) {
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
    if (tombstone.incomplete) appendLine(context.getString(R.string.startup_report_truncated))
}

private const val MAX_REPORT_CHARS = 80_000
