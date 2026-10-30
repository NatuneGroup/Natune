@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.ui.IosHost
import moe.rukamori.archivetune.shared.ui.screens.YearRecapShare
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.writeToFile
import platform.UIKit.UIActivityViewController

/**
 * Shares one recap card through the system share sheet: the card is fitted to the baseline's 1080x1920, written as a
 * PNG under the temporary directory and offered as a file, so Save Image, Files and AirDrop carry a real picture. A
 * failed encode or write is reported once; the user dismissing the sheet is not a failure.
 */
internal fun iosShareRecapCard(graph: IosSessionGraph, host: IosHost, share: YearRecapShare) {
    graph.scope.launch {
        try {
            val bytes = withContext(Dispatchers.Default) { recapPng(share) }
                ?: return@launch graph.report("the recap card could not be encoded")
            val path = NSTemporaryDirectory() + share.fileName
            val data = bytes.usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = bytes.size.convert()) }
            if (!data.writeToFile(path, atomically = true)) return@launch graph.report("the recap card could not be written")
            withContext(Dispatchers.Main) {
                val sheet = UIActivityViewController(
                    activityItems = listOf(NSURL.fileURLWithPath(path)),
                    applicationActivities = null,
                )
                // The popover source view is what iPad requires; on iPhone it is ignored.
                sheet.popoverPresentationController?.sourceView = host.viewController.view
                host.viewController.presentViewController(sheet, animated = true, completion = null)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            graph.report("the recap card was not shared: ${failure::class.simpleName}")
        }
    }
}

private fun recapPng(share: YearRecapShare): ByteArray? {
    val card = Image.makeFromBitmap(share.image.asSkiaBitmap())
    val fitted = if (card.width == share.targetWidth && card.height == share.targetHeight) {
        card
    } else {
        val surface = Surface.makeRasterN32Premul(share.targetWidth, share.targetHeight)
        surface.canvas.drawImageRect(card, Rect.makeWH(share.targetWidth.toFloat(), share.targetHeight.toFloat()))
        surface.makeImageSnapshot()
    }
    return fitted.encodeToData(EncodedImageFormat.PNG)?.bytes?.takeIf { it.isNotEmpty() }
}
