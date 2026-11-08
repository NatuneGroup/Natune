@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.ui.IosHost
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.UIKit.UIActivityViewController
import platform.UIKit.popoverPresentationController

internal fun iosShareLyricsCard(graph: IosSessionGraph, host: IosHost, card: ImageBitmap) {
    graph.scope.launch {
        require(card.width > 0 && card.height > 0 && card.width.toLong() * card.height <= 8_000_000L)
        val png = iosArtworkPng(card) ?: error("Cannot encode lyrics card")
        val path = NSTemporaryDirectory() + "lyrics-${NSUUID().UUIDString}.png"
        iosWriteBytes(path, png)
        val sheet = UIActivityViewController(listOf(NSURL.fileURLWithPath(path)), null)
        sheet.popoverPresentationController?.sourceView = host.viewController.view
        host.viewController.presentViewController(sheet, true, null)
    }
}
