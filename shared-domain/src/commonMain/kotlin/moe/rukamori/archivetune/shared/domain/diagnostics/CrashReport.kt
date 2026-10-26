package moe.rukamori.archivetune.shared.domain.diagnostics

import moe.rukamori.archivetune.shared.domain.feedback.DiagnosticRedaction

/**
 * A crash captured by the dying process and shown on the next launch, or at once when the process survived it. The
 * stack trace is redacted when it is captured, so neither the screen nor a report ever holds the raw text.
 */
data class CrashReport(
    val capturedAtMillis: Long,
    /** Local wall-clock text of the capture, written by the host that captured it. */
    val timestampText: String,
    val appVersion: String,
    val packageName: String,
    val deviceInfo: List<Pair<String, String>>,
    val stackTrace: String,
) {
    /** The first line of the trace, as the crash screen's summary shows it. */
    val preview: String
        get() = stackTrace.lineSequence().firstOrNull()?.take(PREVIEW_CHARS)?.takeIf { it.isNotBlank() } ?: UNKNOWN_ERROR

    /** The text Copy and Share hand out, in the layout of the Android crash report. */
    fun reportText(appName: String): String = buildString {
        appendLine("$appName crash report")
        if (timestampText.isNotBlank()) appendLine("Time: $timestampText")
        if (appVersion.isNotBlank()) appendLine("App: $appVersion")
        appendLine("Package: $packageName")
        appendLine()
        appendLine("Device")
        deviceInfo.forEach { (key, value) -> appendLine("$key: $value") }
        appendLine()
        appendLine()
        appendLine("Stack trace")
        appendLine(stackTrace)
    }

    /** The stored crash body the feedback review expects, after the shared report header. */
    fun feedbackBody(header: String): String = buildString {
        appendLine(header)
        appendLine()
        appendLine("Kind: crash")
        appendLine("Captured: $timestampText")
        appendLine()
        appendLine("Stack trace (redacted):")
        append(stackTrace.take(FEEDBACK_STACK_CHARS))
    }

    companion object {
        const val PREVIEW_CHARS = 100
        const val STACK_CHARS = 24_000
        const val FEEDBACK_STACK_CHARS = 12_000
        const val UNKNOWN_ERROR = "Unknown error"
        private const val MAGIC = "natune-crash 1"

        /** Redacted before the cut: cutting first could leave part of a secret or address past the patterns. */
        fun redactedStack(raw: String): String = DiagnosticRedaction.redact(raw.trim()).take(STACK_CHARS)

        /** Plain text, so the encoder needs nothing beyond string building on a failing thread. */
        fun encode(report: CrashReport): String = buildString {
            appendLine(MAGIC)
            appendLine("captured=${report.capturedAtMillis}")
            appendLine("time=${report.timestampText.oneLine()}")
            appendLine("version=${report.appVersion.oneLine()}")
            appendLine("package=${report.packageName.oneLine()}")
            report.deviceInfo.forEach { (key, value) -> appendLine("device.${key.oneLine().replace('=', ' ')}=${value.oneLine()}") }
            appendLine()
            append(report.stackTrace)
        }

        fun decode(text: String): CrashReport? {
            val lines = text.lines()
            if (lines.firstOrNull() != MAGIC) return null
            val separator = lines.indexOf("").takeIf { it > 0 } ?: return null
            val fields = LinkedHashMap<String, String>()
            val device = ArrayList<Pair<String, String>>()
            lines.subList(1, separator).forEach { line ->
                val index = line.indexOf('=')
                if (index <= 0) return@forEach
                val key = line.substring(0, index)
                val value = line.substring(index + 1)
                if (key.startsWith("device.")) device += key.removePrefix("device.") to value else fields[key] = value
            }
            val stack = lines.drop(separator + 1).joinToString("\n").trim()
            if (stack.isEmpty()) return null
            return CrashReport(
                capturedAtMillis = fields["captured"]?.toLongOrNull() ?: 0L,
                timestampText = fields["time"].orEmpty(),
                appVersion = fields["version"].orEmpty(),
                packageName = fields["package"].orEmpty(),
                deviceInfo = device,
                stackTrace = stack,
            )
        }

        private fun String.oneLine(): String = replace('\r', ' ').replace('\n', ' ')
    }
}
