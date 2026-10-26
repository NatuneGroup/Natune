package moe.rukamori.archivetune.shared.data.platform.ios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSURL
import platform.Foundation.dataUsingEncoding
import platform.Foundation.writeToFile
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController

/**
 * The one UIActivityViewController presenter for share and export actions. It answers true when the user completed
 * a destination (Mail, Messages, Save to Files — the iOS export path), null when they dismissed the sheet, and false
 * when the sheet could not be shown or the share failed.
 */
@OptIn(ExperimentalForeignApi::class)
class IosShareSheet {
    suspend fun share(presenter: UIViewController, items: List<Any?>): Boolean? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Boolean?> { continuation ->
                val sheet = UIActivityViewController(activityItems = items, applicationActivities = null)
                var resumed = false
                val finish: (Boolean?) -> Unit = { result ->
                    if (!resumed) {
                        resumed = true
                        (continuation as CancellableContinuation<Boolean?>).resumeWith(Result.success(result))
                    }
                }
                sheet.completionWithItemsHandler = { _, completed, _, error ->
                    finish(if (error != null) false else if (completed) true else null)
                }
                // iPad requires a popover anchor or the present throws; an arrowless anchor on the host view centers it.
                sheet.popoverPresentationController?.let { popover ->
                    popover.sourceView = presenter.view
                    popover.sourceRect = presenter.view.bounds
                    popover.permittedArrowDirections = 0uL
                }
                presenter.presentViewController(sheet, animated = true, completion = null)
                continuation.invokeOnCancellation {
                    sheet.dismissViewControllerAnimated(false, null)
                }
            }
        }
}

/**
 * Writes [text] to a fresh file in the app's temporary directory — the share sheet reads it at share time and Save to
 * Files copies it out, so it needs no durable home. Returns the file path, or null when the write failed.
 */
@OptIn(ExperimentalForeignApi::class)
fun iosWriteShareFile(name: String, text: String): String? {
    val folder = platform.Foundation.NSTemporaryDirectory()
    val path = "$folder$name"
    val data = (text as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return null
    val written = data.writeToFile(path, atomically = true)
    return if (written) path else null
}

/** The file URL the share sheet hands to destinations for [path]. */
fun iosShareFileUrl(path: String): NSURL = NSURL.fileURLWithPath(path)
