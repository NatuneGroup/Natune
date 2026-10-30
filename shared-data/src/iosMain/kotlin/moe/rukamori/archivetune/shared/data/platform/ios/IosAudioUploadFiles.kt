@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.platform.ios

import kotlin.coroutines.resume
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.data.provider.YouTubeUploadContent
import platform.Foundation.*
import platform.UIKit.*
import platform.UniformTypeIdentifiers.UTTypeAudio
import platform.darwin.NSObject
import platform.posix.memcpy

/** One audio file picked for upload: a private copy the app may read and must delete once the transfer ends. */
class IosPickedAudioFile internal constructor(val name: String, val path: String, val length: Long) {
    /** Removes the private copy. A copy that is already gone is fine. */
    fun delete() {
        NSFileManager.defaultManager.removeItemAtPath(path, null)
    }
}

/**
 * Lets the user pick one audio file from Files. The picker copies it into the app's own storage, so the transfer reads
 * a plain file with its real name and size, and no security scope outlives the pick. Null when the user cancels or the
 * copy has no readable size.
 */
suspend fun iosPickAudioFile(presenter: UIViewController): IosPickedAudioFile? = withContext(Dispatchers.Main) {
    val controller = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeAudio), asCopy = true)
    controller.allowsMultipleSelection = false
    try {
        suspendCancellableCoroutine { continuation ->
            val delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
                override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
                    if (continuation.isActive) continuation.resume(pickedFile(didPickDocumentsAtURLs.singleOrNull() as? NSURL))
                }

                override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            controller.delegate = delegate
            // The picker holds its delegate weakly; this handler refers to it, so it lives as long as the pick.
            continuation.invokeOnCancellation { if (controller.delegate === delegate) controller.delegate = null }
            presenter.presentViewController(controller, animated = true, completion = null)
        }
    } finally {
        controller.delegate = null
        controller.dismissViewControllerAnimated(true, null)
    }
}

private fun pickedFile(url: NSURL?): IosPickedAudioFile? {
    val path = url?.path ?: return null
    val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, null) ?: return null
    val size = (attributes[NSFileSize] as? NSNumber)?.longLongValue ?: return null
    return IosPickedAudioFile(name = url.lastPathComponent.orEmpty(), path = path, length = size)
}

/** The picked file as a forward-only upload body. [close] releases the file handle; it never deletes the file. */
class IosFileUploadContent(file: IosPickedAudioFile) : YouTubeUploadContent {
    override val length: Long = file.length
    private val handle: NSFileHandle =
        NSFileHandle.fileHandleForReadingAtPath(file.path) ?: throw IllegalStateException("the picked file cannot be opened")

    override suspend fun read(buffer: ByteArray): Int = withContext(Dispatchers.Default) {
        if (buffer.isEmpty()) return@withContext 0
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            error.value = null
            val chunk = handle.readDataUpToLength(buffer.size.toULong(), error.ptr)
                ?: throw IllegalStateException("the picked file could not be read")
            val count = chunk.length.toInt()
            if (count == 0) return@memScoped -1
            buffer.usePinned { pinned -> memcpy(pinned.addressOf(0), chunk.bytes, chunk.length) }
            count
        }
    }

    fun close() {
        handle.closeAndReturnError(null)
    }
}
