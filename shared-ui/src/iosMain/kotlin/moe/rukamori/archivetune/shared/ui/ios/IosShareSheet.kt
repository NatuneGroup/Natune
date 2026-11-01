@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.ui.ios

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.data.platform.ios.iosApplicationVersion
import moe.rukamori.archivetune.shared.data.platform.ios.iosCrashDeviceInfo
import moe.rukamori.archivetune.shared.data.platform.ios.iosDiagnosticTime
import moe.rukamori.archivetune.shared.data.platform.ios.iosNowMillis
import moe.rukamori.archivetune.shared.ui.IosHost
import platform.Foundation.*
import platform.UIKit.*

/**
 * Presents the system share sheet over the host's root controller. The popover source view is what iPad
 * requires; on iPhone it is ignored.
 */
private fun iosPresentShareSheet(host: IosHost, sheet: UIActivityViewController) {
    sheet.popoverPresentationController?.sourceView = host.viewController.view
    host.viewController.presentViewController(sheet, animated = true, completion = null)
}

/** Shares the log text itself, the Android Share-equivalent of the diagnostics page. */
internal fun iosShareText(host: IosHost, text: String) {
    iosPresentShareSheet(host, UIActivityViewController(activityItems = listOf(text), applicationActivities = null))
}

/**
 * Writes [text] to a request-owned temporary file and shares the file URL, so Save to Files and AirDrop carry a
 * real document. Follows the DiagnosticLogActions export contract: true once the sheet completes, null when the
 * user dismisses it and false when the write itself failed. The file and its directory go away with the sheet,
 * however it ends.
 */
internal suspend fun iosExportTextFile(host: IosHost, fileName: String, text: String): Boolean? {
    val directory = iosShareScratchDirectory()
    val path = "$directory/$fileName"
    val written =
        (text as NSString).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    if (!written) {
        NSFileManager.defaultManager.removeItemAtPath(directory, null)
        return false
    }
    return iosShareFileUrls(host, listOf(NSURL.fileURLWithPath(path)), cleanup = directory)
}

/**
 * Writes [bytes] to a request-owned temporary file and shares the file URL, the binary-file counterpart of
 * [iosExportTextFile]: true once the sheet completes, null when the user dismisses it and false when the write
 * itself failed. A backup archive can carry account values in the open, so nothing stays in shared temp.
 */
internal suspend fun iosExportDataFile(host: IosHost, fileName: String, bytes: ByteArray): Boolean? {
    val directory = iosShareScratchDirectory()
    val path = "$directory/$fileName"
    val written = bytes.usePinned { pinned ->
        NSData.create(
            bytes = if (bytes.isEmpty()) null else pinned.addressOf(0),
            length = bytes.size.toULong(),
        ).writeToFile(path, atomically = true)
    }
    if (!written) {
        NSFileManager.defaultManager.removeItemAtPath(directory, null)
        return false
    }
    return iosShareFileUrls(host, listOf(NSURL.fileURLWithPath(path)), cleanup = directory)
}

/**
 * Shares every file URL in one sheet, so Save to Files or AirDrop carries the documents together. True once the
 * sheet completes, null when the user dismisses it. [cleanup] names a request-owned directory deleted when the
 * sheet resolves, is dismissed or fails, so shared documents never linger in shared temp.
 */
internal suspend fun iosShareFileUrls(host: IosHost, urls: List<NSURL>, cleanup: String? = null): Boolean? {
    if (urls.isEmpty()) return false
    return withContext(Dispatchers.Main) {
        try {
            suspendCancellableCoroutine { continuation ->
                val sheet = UIActivityViewController(activityItems = urls, applicationActivities = null)
                sheet.completionWithItemsHandler = { _, completed, _, _ ->
                    continuation.resume(if (completed) true else null)
                }
                try {
                    iosPresentShareSheet(host, sheet)
                } catch (failure: Throwable) {
                    continuation.resumeWithException(failure)
                }
            }
        } finally {
            cleanup?.let { NSFileManager.defaultManager.removeItemAtPath(it, null) }
        }
    }
}

/** A fresh request-owned scratch directory beside the system temp root; the caller owns its lifetime. */
private fun iosShareScratchDirectory(): String {
    val directory = NSTemporaryDirectory() + "share-" + NSUUID().UUIDString
    NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)
    return directory
}

/** The report an export writes: the same identity header the desktop report leads with, then the rolling log. */
internal suspend fun iosDiagnosticReport(host: IosHost): String =
    buildString {
        appendLine("Natune diagnostic report v1")
        appendLine("version: ${iosApplicationVersion()}")
        for ((label, value) in iosCrashDeviceInfo()) {
            appendLine("device.${label.lowercase().replace(' ', '_')}=$value")
        }
        appendLine("captured: ${iosDiagnosticTime(iosNowMillis())}")
        appendLine()
        append(host.diagnostics?.readForExport().orEmpty())
    }
