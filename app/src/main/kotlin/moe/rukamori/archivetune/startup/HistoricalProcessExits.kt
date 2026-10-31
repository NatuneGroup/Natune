package moe.rukamori.archivetune.startup

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi

internal enum class ProcessExitKind { CRASH, NATIVE_CRASH, ANR, INITIALIZATION_FAILURE, LOW_MEMORY }
internal data class HistoricalProcessExit(
    val timestampMs: Long,
    val pid: Int,
    val kind: ProcessExitKind,
    val status: Int,
    val description: String,
    val trace: CappedText?,
    val tombstone: NativeTombstone?,
)
internal data class HistoricalProcessExits(val exits: List<HistoricalProcessExit>, val unavailable: Boolean)

internal fun historicalProcessExits(context: Context, sinceMs: Long): HistoricalProcessExits {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return HistoricalProcessExits(emptyList(), unavailable = true)
    return runCatching { Api30ProcessExits.read(context, sinceMs) }
        .getOrElse { HistoricalProcessExits(emptyList(), unavailable = true) }
}

@RequiresApi(Build.VERSION_CODES.R)
private object Api30ProcessExits {
    fun read(context: Context, sinceMs: Long): HistoricalProcessExits {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return HistoricalProcessExits(emptyList(), unavailable = true)
        val records = manager.getHistoricalProcessExitReasons(context.packageName, 0, 10)
        val exits = records.asSequence()
            .filter { it.processName == context.packageName && it.timestamp > sinceMs }
            .sortedByDescending { it.timestamp }
            .mapNotNull { info -> kind(info.reason)?.let { it to info } }
            .take(3)
            .map { (kind, info) ->
                var trace: CappedText? = null
                var tombstone: NativeTombstone? = null
                if (kind == ProcessExitKind.ANR || kind == ProcessExitKind.NATIVE_CRASH && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching {
                        info.traceInputStream?.use { stream ->
                            if (kind == ProcessExitKind.NATIVE_CRASH) {
                                val capped = stream.readCappedBytes(MAX_NATIVE_BYTES)
                                val decoded = NativeTombstoneDecoder.decode(capped.bytes)
                                tombstone = decoded.copy(incomplete = decoded.incomplete || capped.truncated)
                            } else {
                                trace = stream.readCappedText(MAX_ANR_BYTES)
                            }
                        }
                    }
                }
                HistoricalProcessExit(info.timestamp, info.pid, kind, info.status, info.description.orEmpty(), trace, tombstone)
            }.toList()
        return HistoricalProcessExits(exits, unavailable = false)
    }

    private fun kind(reason: Int): ProcessExitKind? = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> ProcessExitKind.CRASH
        ApplicationExitInfo.REASON_CRASH_NATIVE -> ProcessExitKind.NATIVE_CRASH
        ApplicationExitInfo.REASON_ANR -> ProcessExitKind.ANR
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> ProcessExitKind.INITIALIZATION_FAILURE
        ApplicationExitInfo.REASON_LOW_MEMORY -> ProcessExitKind.LOW_MEMORY
        else -> null
    }

    private const val MAX_NATIVE_BYTES = 256 * 1024
    private const val MAX_ANR_BYTES = 64 * 1024
}
