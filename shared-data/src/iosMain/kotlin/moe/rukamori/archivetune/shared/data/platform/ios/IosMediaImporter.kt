@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.platform.ios

import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.data.localmedia.importedMediaFileName
import platform.Foundation.*
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeData
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

/**
 * Copies one audio document the user picks into this host's import folder. The picker is asked for a copy, so the
 * file it returns is already in the app's own container and no security scope is needed. That copy is copied again
 * under a new name in [root] and then removed. The picked file keeps its own leaf name, reduced to plain file
 * characters and suffixed when the name is taken, so a pick can never write outside [root].
 *
 * A cancelled picker writes nothing and returns null. A failed copy throws before anything is listed, so no import
 * row or library entry names a file that was not written.
 */
class IosMediaImporter(private val root: String) {

    /**
     * Presents the system document picker and imports the chosen file, returning its container path, or null when
     * the picker was cancelled.
     */
    suspend fun pick(presenter: UIViewController): String? {
        val picked = present(presenter) ?: return null
        return withContext(Dispatchers.Default) { copyIntoContainer(picked) }
    }

    /** Resolves the picked leaf name against the folder, copies the file into it and removes the picker's copy. */
    private fun copyIntoContainer(picked: NSURL): String {
        val manager = NSFileManager.defaultManager
        manager.createDirectoryAtPath(root, true, null, null)
        val name = importedMediaFileName(picked.lastPathComponent ?: DEFAULT_IMPORT_NAME) { candidate ->
            manager.fileExistsAtPath("$root/$candidate")
        }
        val destination = NSURL.fileURLWithPath("$root/$name")
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            error.value = null
            check(manager.copyItemAtURL(picked, destination, error.ptr)) { "Document copy failed" }
        }
        manager.removeItemAtURL(picked, null)
        return destination.path ?: "$root/$name"
    }

    /** Presents the picker and suspends until it answers with a document or is cancelled. */
    private suspend fun present(presenter: UIViewController): NSURL? = withContext(Dispatchers.Main) {
        val controller = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeData), asCopy = true)
        controller.allowsMultipleSelection = false
        try {
            suspendCancellableCoroutine<NSURL?> { continuation ->
                val delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
                    override fun documentPicker(
                        controller: UIDocumentPickerViewController,
                        didPickDocumentsAtURLs: List<*>,
                    ) {
                        if (continuation.isActive) continuation.resume(didPickDocumentsAtURLs.singleOrNull() as? NSURL)
                    }

                    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
                controller.delegate = delegate
                // The picker holds its delegate weakly; this handler refers to it, so it lives until the pick ends.
                continuation.invokeOnCancellation {
                    dispatch_async(dispatch_get_main_queue()) { controller.dismissViewControllerAnimated(true, null) }
                    if (controller.delegate === delegate) controller.delegate = null
                }
                presenter.presentViewController(controller, animated = true, completion = null)
            }
        } finally {
            controller.delegate = null
            controller.dismissViewControllerAnimated(true, null)
        }
    }

    private companion object {
        const val DEFAULT_IMPORT_NAME = "imported.media"
    }
}
