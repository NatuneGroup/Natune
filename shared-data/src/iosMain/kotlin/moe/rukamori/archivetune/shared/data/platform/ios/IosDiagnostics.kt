@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.platform.ios

import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.toKString
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.data.diagnostics.DiagnosticLogFile
import moe.rukamori.archivetune.shared.data.diagnostics.DiagnosticLogFiles
import moe.rukamori.archivetune.shared.domain.diagnostics.CrashLogKind
import moe.rukamori.archivetune.shared.domain.diagnostics.CrashLogRecord
import moe.rukamori.archivetune.shared.domain.diagnostics.CrashReport
import kotlin.native.ReportUnhandledExceptionHook
import kotlin.native.setUnhandledExceptionHook
import platform.Foundation.*
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseeko
import platform.posix.ftello
import platform.posix.fwrite
import platform.posix.getpid
import platform.posix.remove
import platform.posix.truncate
import platform.posix.uname
import platform.posix.utsname
import platform.UIKit.UIDevice

/**
 * The diagnostics directory under Application Support, excluded from device backup: logs and crash reports are
 * redacted, but they describe this device's use of the app and have no place in a backup.
 */
fun iosDiagnosticsDirectory(): String {
    val manager = NSFileManager.defaultManager
    val support = (manager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask).first() as NSURL).path
        ?: error("Application Support is unavailable")
    val root = "$support/diagnostics"
    manager.createDirectoryAtPath("$root/logs", true, null, null)
    NSURL.fileURLWithPath(root, isDirectory = true)
        .setResourceValue(NSNumber.numberWithBool(true), forKey = NSURLIsExcludedFromBackupKey, error = null)
    return root
}

private val diagnosticTimeFormat = NSDateFormatter().apply { dateFormat = "yyyy-MM-dd HH:mm:ss.SSS" }

/** Local wall-clock text for the log viewer and report. NSDateFormatter is safe to share across threads. */
fun iosDiagnosticTime(epochMillis: Long): String =
    diagnosticTimeFormat.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0))

fun iosNowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

/** The rotating log files in one folder. Blocking file calls run on [Dispatchers.Default], never on the main thread. */
class IosDiagnosticLogFiles(private val folder: String) : DiagnosticLogFiles {
    private val manager = NSFileManager.defaultManager

    override suspend fun list(): List<DiagnosticLogFile> = withContext(Dispatchers.Default) {
        val names = memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            error.value = null
            manager.contentsOfDirectoryAtPath(folder, error.ptr)
        } ?: return@withContext emptyList()
        names.filterIsInstance<String>().mapNotNull { name ->
            val attributes = memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                error.value = null
                manager.attributesOfItemAtPath("$folder/$name", error.ptr)
            } ?: return@mapNotNull null
            if (attributes[NSFileType] != NSFileTypeRegular) return@mapNotNull null
            DiagnosticLogFile(name, (attributes[NSFileSize] as? NSNumber)?.longLongValue ?: 0L)
        }
    }

    override suspend fun append(name: String, text: String): Boolean = withContext(Dispatchers.Default) {
        val bytes = text.encodeToByteArray()
        if (bytes.isEmpty()) return@withContext true
        val output = fopen(fileOf(name), "ab") ?: return@withContext false
        val written = bytes.usePinned { fwrite(it.addressOf(0), 1uL, bytes.size.toULong(), output) }
        val closed = fclose(output) == 0
        written == bytes.size.toULong() && closed
    }

    override suspend fun readTail(name: String, maxBytes: Long): ByteArray = withContext(Dispatchers.Default) {
        val input = fopen(fileOf(name), "rb") ?: return@withContext ByteArray(0)
        try {
            if (fseeko(input, 0, SEEK_END) != 0) return@withContext ByteArray(0)
            val length = ftello(input)
            val take = minOf(length, maxBytes).coerceAtLeast(0L)
            if (take == 0L || fseeko(input, -take, SEEK_END) != 0) return@withContext ByteArray(0)
            val bytes = ByteArray(take.toInt())
            val read = bytes.usePinned { fread(it.addressOf(0), 1uL, take.toULong(), input) }
            if (read.toLong() == take) bytes else bytes.copyOf(read.toInt())
        } finally {
            fclose(input)
        }
    }

    override suspend fun delete(name: String) {
        withContext(Dispatchers.Default) { remove(fileOf(name)) }
    }

    private fun fileOf(name: String): String {
        require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\u0000' !in name) {
            "Log file name must be a plain file name"
        }
        return "$folder/$name"
    }
}

/**
 * Crash capture for the iOS app: the Kotlin unhandled-exception hook and, through the Swift app, the Objective-C
 * uncaught-exception handler both write one redacted [CrashReport] next to the log. The dying thread only formats text
 * and writes one file with POSIX calls; nothing is shown or sent. The next launch reads the report and offers it.
 */
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
object IosCrashCapture {
    private const val REPORT_FILE = "crash_report.txt"

    /** The plain record the Objective-C exception handler writes with POSIX calls only, on a descriptor it pre-opens. */
    private const val NATIVE_REPORT_FILE = "native_exception.txt"
    private var reportPath: String? = null
    private var nativeReportPath: String? = null
    private var appVersion = ""
    private var packageName = ""
    private var recordStore: IosCrashLogStore? = null

    /** Installed once per process. The hook chains to an earlier one, after which the runtime ends the process. */
    fun install(directory: String, appVersion: String, packageName: String) {
        if (reportPath != null) return
        this.appVersion = appVersion
        this.packageName = packageName
        reportPath = "$directory/$REPORT_FILE"
        nativeReportPath = "$directory/$NATIVE_REPORT_FILE"
        recordStore = IosCrashLogStore("$directory/records")
        var previous: ReportUnhandledExceptionHook? = null
        previous = setUnhandledExceptionHook { error ->
            capture(error.stackTraceToString(), CrashLogKind.KOTLIN)
            previous?.invoke(error)
        }
    }

    /**
     * The Kotlin side capture of a reported, non-fatal Objective-C exception. The fatal uncaught-exception handler must
     * never call it: a dying process may not run the Kotlin runtime, allocate or open a file.
     */
    fun captureNSException(name: String, reason: String?, callStack: List<String>) {
        capture(buildString {
            append(name)
            if (!reason.isNullOrBlank()) append(": ").append(reason)
            callStack.forEach { frame -> append("\n\tat ").append(frame) }
        }, CrashLogKind.NS)
    }

    /** Where the app shell's fatal handler writes with POSIX calls; null until [install] has run. */
    fun nativeRecordPath(): String? = nativeReportPath

    fun stored(): CrashReport? {
        val path = reportPath ?: return null
        val text = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)
        text?.let { CrashReport.decode(it) }?.let { return it }
        return storedNative()
    }

    /** False while the report is still on disk, so it is offered again next launch. */
    fun clear(): Boolean {
        val path = reportPath ?: return true
        val cleared = !NSFileManager.defaultManager.fileExistsAtPath(path) || remove(path) == 0
        // The fatal handler's descriptor is opened once at launch, so its file is emptied here and never unlinked: a
        // deleted inode would take the next record with it.
        val native = nativeReportPath
        val nativeCleared = native == null || !NSFileManager.defaultManager.fileExistsAtPath(native) ||
            truncate(native, 0L) == 0
        return cleared && nativeCleared
    }

    /**
     * Classifies the fatal handler's plain record into the shape the crash screen reads. The record starts with the
     * capture time in epoch seconds on its own line and then the text the handler could copy safely; the call stack is
     * not part of it, because reading it allocates in a process whose runtime state is already undefined.
     */
    private fun storedNative(): CrashReport? {
        val path = nativeReportPath ?: return null
        val raw = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) ?: return null
        return parseNativeIosCrashRecord(raw, appVersion, packageName)
    }

    private fun capture(rawTrace: String, kind: CrashLogKind) {
        val path = reportPath ?: return
        try {
            val now = NSDate()
            val capturedAt = (now.timeIntervalSince1970 * 1000.0).toLong()
            val report = CrashReport(
                capturedAtMillis = capturedAt,
                timestampText = NSDateFormatter().apply { dateFormat = "yyyy-MM-dd HH:mm:ss" }.stringFromDate(now),
                appVersion = appVersion,
                packageName = packageName,
                deviceInfo = iosCrashDeviceInfo(),
                stackTrace = CrashReport.redactedStack(rawTrace),
            )
            val bytes = CrashReport.encode(report).encodeToByteArray()
            val output = fopen(path, "wb") ?: return
            bytes.usePinned { fwrite(it.addressOf(0), 1uL, bytes.size.toULong(), output) }
            fclose(output)
            recordStore?.save(
                CrashLogRecord(
                    timestampMs = capturedAt,
                    pid = getpid(),
                    kind = kind,
                    status = 0,
                    process = packageName,
                    thread = NSThread.currentThread.name ?: "",
                    body = report.stackTrace,
                    truncated = false,
                    versionName = appVersion,
                )
            )
        } catch (_: Throwable) {
            // Nothing can be reported from a crashing process; the original failure still ends it.
        }
    }
}

/**
 * Classifies the fatal handler's plain record into the report the crash screen reads. Its first line is the capture time
 * in epoch seconds; the rest is what the handler could copy with POSIX calls alone, so it carries no frame list. An empty
 * or cleared record is not a report.
 */
internal fun parseNativeIosCrashRecord(raw: String, appVersion: String, packageName: String): CrashReport? {
    if (raw.isBlank()) return null
    val header = raw.substringBefore('\n')
    val capturedSeconds = header.trim().takeWhile { it.isDigit() }.toLongOrNull() ?: 0L
    val body = raw.substringAfter('\n', "").trim().ifEmpty { raw.trim() }
    return CrashReport(
        capturedAtMillis = capturedSeconds * 1_000L,
        timestampText = if (capturedSeconds > 0L) iosDiagnosticTime(capturedSeconds * 1_000L) else "",
        appVersion = appVersion,
        packageName = packageName,
        deviceInfo = iosCrashDeviceInfo(),
        stackTrace = CrashReport.redactedStack(body),
    )
}

/** The rows of the crash screen's Device info card, under the labels the Android crash screen uses. */
fun iosCrashDeviceInfo(): List<Pair<String, String>> {
    val device = UIDevice.currentDevice
    val machine = memScoped {
        val info = alloc<utsname>()
        if (uname(info.ptr) == 0) info.machine.toKString() else ""
    }
    return listOf(
        "OS version" to "${device.systemName} ${device.systemVersion}",
        "Phone name" to device.name.ifBlank { "-" },
        "Model" to device.model.ifBlank { "-" },
        "Manufacturer" to "Apple",
        "Device" to machine.ifBlank { "-" },
    )
}

/**
 * The full diagnostic report the log page exports, in the same v2 shape the desktop writes: identity and device rows,
 * then the rotating process log. The caller redacts it before it leaves the app.
 */
suspend fun iosDiagnosticReport(store: moe.rukamori.archivetune.shared.domain.diagnostics.DiagnosticLogStore): String =
    buildString {
        appendLine("Natune diagnostic report v2")
        appendLine("Version: ${iosApplicationVersion()}")
        appendLine("Variant: ios")
        iosCrashDeviceInfo().forEach { (key, value) -> appendLine("$key: $value") }
        appendLine("Captured: ${iosDiagnosticTime(iosNowMillis())}")
        appendLine()
        appendLine("Process log (host diagnostics; earlier sessions first, newest last):")
        append(store.readForExport())
    }
