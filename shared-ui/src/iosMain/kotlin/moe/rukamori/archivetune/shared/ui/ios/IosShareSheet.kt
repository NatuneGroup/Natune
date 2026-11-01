@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.ui.ios

import kotlin.coroutines.resume
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
 * Writes [text] to a temporary file and shares the file URL, so Save to Files and AirDrop carry a real
 * document. Follows the DiagnosticLogActions export contract: true once the sheet completes, null when the
 * user dismisses it and false when the write itself failed.
 */
internal suspend fun iosExportTextFile(host: IosHost, fileName: String, text: String): Boolean? {
    val path = NSTemporaryDirectory() + fileName
    val written =
        (text as NSString).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    if (!written) return false
    return iosShareFileUrls(host, listOf(NSURL.fileURLWithPath(path)))
}

/**
 * Writes [bytes] to a temporary file and shares the file URL, the binary-file counterpart of [iosExportTextFile]:
 * true once the sheet completes, null when the user dismisses it and false when the write itself failed.
 */
internal suspend fun iosExportDataFile(host: IosHost, fileName: String, bytes: ByteArray): Boolean? {
    val path = NSTemporaryDirectory() + fileName
    val written = bytes.usePinned { pinned ->
        NSData.create(
            bytes = if (bytes.isEmpty()) null else pinned.addressOf(0),
            length = bytes.size.toULong(),
        ).writeToFile(path, atomically = true)
    }
    if (!written) return false
    return iosShareFileUrls(host, listOf(NSURL.fileURLWithPath(path)))
}

/**
 * Shares every file URL in one sheet, so Save to Files or AirDrop carries the documents together. True once the
 * sheet completes, null when the user dismisses it.
 */
internal suspend fun iosShareFileUrls(host: IosHost, urls: List<NSURL>): Boolean? {
    if (urls.isEmpty()) return false
    return withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val sheet = UIActivityViewController(activityItems = urls, applicationActivities = null)
            sheet.completionWithItemsHandler = { _, completed, _, _ ->
                continuation.resume(if (completed) true else null)
            }
            iosPresentShareSheet(host, sheet)
        }
    }
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
