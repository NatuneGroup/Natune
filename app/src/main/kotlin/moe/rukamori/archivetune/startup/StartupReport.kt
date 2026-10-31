package moe.rukamori.archivetune.startup

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
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

/**
 * The launcher's recovery decision. Android's exit history and a captured Java crash are imported into the
 * shared store first, so both are judged together. Only unacknowledged failures of the installed build can
 * interrupt startup; the same records keep listing on the Crash logs page after that.
 */
internal fun previousLaunchReport(context: Context): StartupReport? {
    val store = StartupRecovery.store(context)
    val pending = store.pendingAttempt()
    val last = store.lastAttempt()
    val reviewed = store.acknowledgedThrough()
    val incomplete = previousStartupIncomplete(store.pendingExists(), pending?.pid, liveMainProcessId(context))
    val historyUnavailable = importCrashRecords(context, store)
    val install = currentInstall(context)
    val since = reportSince(last?.timestampMs, reviewed)
    val listed = store.listRecords().filter { it.timestampMs > since }
    val interrupting = listed.filter { it.interruptsStartup(install) }
    if (!incomplete && interrupting.isEmpty()) return null

    val safeMode = incomplete || interrupting.any { it.kind == ProcessExitKind.INITIALIZATION_FAILURE }
    val text = buildString {
        appendLine(context.getString(R.string.startup_report_title))
        appendLine(context.getString(R.string.startup_report_build, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toString()))
        appendLine(context.getString(R.string.startup_report_device, Build.MANUFACTURER, Build.MODEL,
            Build.VERSION.RELEASE, Build.VERSION.SDK_INT.toString(), Build.SUPPORTED_ABIS.joinToString()))
        last?.let {
            appendLine(context.getString(R.string.startup_report_attempt, it.version,
                formatCrashTime(context, it.timestampMs), it.pid.toString()))
        }
        appendLine()
        appendLine(context.getString(if (incomplete) R.string.startup_incomplete_explanation else R.string.startup_previous_exit_explanation))
        if (listed.none { it.captured }) appendLine(context.getString(R.string.startup_report_no_java))
        for (record in listed) {
            appendLine()
            if (record.captured) {
                appendLine(context.getString(R.string.startup_report_java, formatCrashTime(context, record.timestampMs), record.thread))
            } else {
                appendLine(context.getString(R.string.startup_report_exit, formatCrashTime(context, record.timestampMs),
                    context.getString(record.kind.labelRes()), record.pid.toString(), record.status.toString()))
            }
            appendLine(versionLabel(context, record, install))
            if (record.body.isNotBlank()) appendLine(record.body)
            if (record.truncated) appendLine(context.getString(R.string.startup_report_truncated))
        }
        if (historyUnavailable) appendLine(context.getString(R.string.startup_history_unavailable))
        else if (listed.none { !it.captured }) appendLine(context.getString(R.string.startup_history_empty))
    }
    val redacted = DiagnosticRedaction.redact(text)
    val bounded = if (redacted.length > MAX_REPORT_CHARS) {
        val notice = "\n" + context.getString(R.string.startup_report_truncated)
        redacted.take((MAX_REPORT_CHARS - notice.length).coerceAtLeast(0)) + notice.take(MAX_REPORT_CHARS)
    } else redacted
    val through = maxOf(reviewed, pending?.timestampMs ?: 0L, listed.maxOfOrNull { it.timestampMs } ?: 0L)
    return StartupReport(bounded, safeMode, through)
}

internal fun previousStartupIncomplete(pendingExists: Boolean, pendingPid: Int?, livePid: Int?): Boolean =
    pendingExists && (livePid == null || pendingPid != livePid)

internal fun reportSince(lastStartMs: Long?, reviewedThroughMs: Long): Long =
    maxOf(reviewedThroughMs, (lastStartMs ?: 1L) - 1L)

private const val MAX_REPORT_CHARS = 80_000
