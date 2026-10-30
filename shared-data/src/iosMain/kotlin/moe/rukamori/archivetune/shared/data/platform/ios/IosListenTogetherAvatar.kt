@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.platform.ios

import kotlin.coroutines.resume
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherEnvelope
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.*
import platform.UIKit.*
import platform.darwin.NSObject

/**
 * The member's custom Listen Together picture, kept the way Android keeps it: one downscaled JPEG of at most
 * [MAX_DIMENSION] pixels a side, at quality 70, in the app's own storage. The room protocol carries only the avatar
 * index, and the chat relay carries the bytes, so a picture over [ListenTogetherEnvelope.MAX_AVATAR_BYTES] is never
 * stored, because every receiving client would drop it.
 */
object IosListenTogetherAvatar {
    private const val FILE_NAME = "listen_together_avatar.jpg"
    private const val MAX_DIMENSION = 192.0
    private val JPEG_QUALITIES = listOf(0.7, 0.55, 0.4)

    private fun path(): String {
        val manager = NSFileManager.defaultManager
        val support = (manager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask).first() as NSURL).path
            ?: error("Application Support is unavailable")
        manager.createDirectoryAtPath(support, true, null, null)
        return "$support/$FILE_NAME"
    }

    /** Lets the member pick one photo. Null when they cancel or the picker cannot be shown. */
    suspend fun pick(presenter: UIViewController): UIImage? = withContext(Dispatchers.Main) {
        if (!UIImagePickerController.isSourceTypeAvailable(
                UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypePhotoLibrary,
            )
        ) {
            return@withContext null
        }
        suspendCancellableCoroutine { continuation ->
            val picker = UIImagePickerController()
            picker.sourceType = UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypePhotoLibrary
            // The system crop gives a square, which is how every client draws the picture.
            picker.allowsEditing = true
            // The picker holds its delegate weakly; the cancellation handler below refers to it, so it lives as long as the pick.
            val delegate = object : NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {
                override fun imagePickerController(
                    picker: UIImagePickerController,
                    didFinishPickingMediaWithInfo: Map<Any?, *>,
                ) {
                    val image = (didFinishPickingMediaWithInfo[UIImagePickerControllerEditedImage] as? UIImage)
                        ?: (didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage)
                    picker.dismissViewControllerAnimated(true, null)
                    if (continuation.isActive) continuation.resume(image)
                }

                override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
                    picker.dismissViewControllerAnimated(true, null)
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            picker.delegate = delegate
            continuation.invokeOnCancellation {
                picker.dismissViewControllerAnimated(false, null)
                if (picker.delegate === delegate) picker.delegate = null
            }
            presenter.presentViewController(picker, animated = true, completion = null)
        }
    }

    /**
     * Downscales [image] and stores it as the custom picture. False when it cannot be drawn, encoded small enough, or
     * written; the stored picture is then unchanged.
     */
    suspend fun save(image: UIImage): Boolean = withContext(Dispatchers.Default) {
        val bytes = encode(image) ?: return@withContext false
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        data.writeToFile(path(), atomically = true)
    }

    /** The stored picture's bytes, or null when there is none or it is outside the room's size limit. */
    suspend fun load(): ByteArray? = withContext(Dispatchers.Default) {
        val data = NSData.dataWithContentsOfFile(path()) ?: return@withContext null
        val length = data.length.toInt()
        if (length <= 0 || length > ListenTogetherEnvelope.MAX_AVATAR_BYTES) return@withContext null
        data.bytes?.readBytes(length)
    }

    private fun encode(image: UIImage): ByteArray? {
        val (width, height) = image.size.useContents { width to height }
        if (width <= 0.0 || height <= 0.0) return null
        val scale = min(1.0, MAX_DIMENSION / max(width, height))
        val targetWidth = max(1.0, floor(width * scale))
        val targetHeight = max(1.0, floor(height * scale))
        // The renderer draws at the screen's scale by default, which would triple the stored pixels.
        val format = UIGraphicsImageRendererFormat.defaultFormat().apply { this.scale = 1.0 }
        val renderer = UIGraphicsImageRenderer(size = CGSizeMake(targetWidth, targetHeight), format = format)
        val scaled = renderer.imageWithActions { _ ->
            image.drawInRect(CGRectMake(0.0, 0.0, targetWidth, targetHeight))
        }
        for (quality in JPEG_QUALITIES) {
            val data = UIImageJPEGRepresentation(scaled, quality) ?: continue
            val length = data.length.toInt()
            if (length in 1..ListenTogetherEnvelope.MAX_AVATAR_BYTES) return data.bytes?.readBytes(length)
        }
        return null
    }
}
