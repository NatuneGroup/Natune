/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.platform.ios

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import moe.rukamori.archivetune.shared.domain.platform.FileHandle
import moe.rukamori.archivetune.shared.domain.platform.PlatformFiles
import platform.Foundation.*
import platform.UIKit.*
import platform.UniformTypeIdentifiers.UTTypeData
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Opaque iOS bookmark handles, not durable filesystem paths. Security scope is held only during
 * coordinated I/O and always released. A stale bookmark requires picking the document again.
 */
class IosPlatformFiles : PlatformFiles {
    private var picker: UIDocumentPickerViewController? = null
    private var pickerDelegate: UIDocumentPickerDelegateProtocol? = null
    private var cancelPicker: (() -> Unit)? = null

    suspend fun pickDocument(presenter: UIViewController): FileHandle? = withContext(Dispatchers.Main) {
        check(picker == null) { "Document picker already active" }
        val controller = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeData), asCopy = false)
        controller.allowsMultipleSelection = false
        picker = controller
        try {
            suspendCancellableCoroutine { continuation ->
                cancelPicker = { continuation.cancel() }
                val delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
                    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
                        if (!continuation.isActive) return
                        val url = didPickDocumentsAtURLs.singleOrNull() as? NSURL
                        try {
                            requireNotNull(url)
                            check(url.startAccessingSecurityScopedResource()) { "Document access unavailable" }
                            val bookmark = try { bookmark(url) } finally { url.stopAccessingSecurityScopedResource() }
                            continuation.resume(bookmark)
                        } catch (_: Exception) {
                            continuation.resumeWithException(IllegalStateException("Document bookmark creation failed"))
                        }
                    }
                    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
                pickerDelegate = delegate
                controller.delegate = delegate
                continuation.invokeOnCancellation {
                    dispatch_async(dispatch_get_main_queue()) { controller.dismissViewControllerAnimated(true, null) }
                }
                presenter.presentViewController(controller, animated = true, completion = null)
            }
        } finally {
            controller.delegate = null
            controller.dismissViewControllerAnimated(true, null)
            pickerDelegate = null
            picker = null
            cancelPicker = null
        }
    }

    fun close() {
        dispatch_async(dispatch_get_main_queue()) { cancelPicker?.invoke() }
    }

    override suspend fun read(file: FileHandle, maxBytes: Long): ByteArray = withContext(Dispatchers.Default) {
        require(maxBytes in 0 until Int.MAX_VALUE.toLong())
        access(file) { url ->
            coordinate(url, writing = false) { target ->
                memScoped {
                    val error = alloc<ObjCObjectVar<NSError?>>()
                    error.value = null
                    val handle = NSFileHandle.fileHandleForReadingFromURL(target, error.ptr)
                        ?: error("Document could not be opened")
                    try {
                        val data = NSMutableData()
                        while (true) {
                            ensureActive()
                            val remaining = maxBytes.toULong() - data.length
                            val chunk = handle.readDataUpToLength(minOf(64_000uL, remaining + 1uL), error.ptr)
                                ?: error("Document read failed")
                            if (chunk.length == 0uL) break
                            check(chunk.length <= remaining) { "Document exceeds requested read limit" }
                            data.appendData(chunk)
                        }
                        if (data.length == 0uL) byteArrayOf() else data.bytes!!.readBytes(data.length.toInt())
                    } finally { handle.closeAndReturnError(null) }
                }
            }
        }
    }

    override suspend fun writeAtomic(file: FileHandle, bytes: ByteArray) = withContext(Dispatchers.Default) {
        access(file) { url ->
            coordinate(url, writing = true) { target ->
                bytes.usePinned {
                    val data = NSData.create(bytes = if (bytes.isEmpty()) null else it.addressOf(0),
                        length = bytes.size.toULong())
                    memScoped {
                        val error = alloc<ObjCObjectVar<NSError?>>()
                        error.value = null
                        check(data.writeToURL(target, options = NSDataWritingAtomic, error = error.ptr)) {
                            "Atomic document replacement failed"
                        }
                    }
                }
            }
        }
    }

    override suspend fun delete(file: FileHandle) = withContext(Dispatchers.Default) {
        access(file) { url ->
            coordinate(url, writing = true, deleting = true) { target ->
                memScoped {
                    val error = alloc<ObjCObjectVar<NSError?>>()
                    error.value = null
                    check(NSFileManager.defaultManager.removeItemAtURL(target, error.ptr)) { "Document deletion failed" }
                }
            }
        }
    }

    private fun bookmark(url: NSURL): FileHandle = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        error.value = null
        // iOS security-scoped document bookmarks do NOT use the macOS-only WithSecurityScope flag.
        val data = url.bookmarkDataWithOptions(NSURLBookmarkCreationMinimalBookmark,
            includingResourceValuesForKeys = null, relativeToURL = null, error = error.ptr)
            ?: error("Document bookmark unavailable")
        FileHandle(PREFIX + data.base64EncodedStringWithOptions(0u))
    }

    private fun <T> access(file: FileHandle, block: (NSURL) -> T): T = memScoped {
        require(file.value.startsWith(PREFIX) && file.value.length <= MAX_BOOKMARK_CHARS)
        val data = NSData.create(base64EncodedString = file.value.removePrefix(PREFIX), options = 0u)
            ?: error("Invalid document bookmark")
        val stale = alloc<BooleanVar>()
        val error = alloc<ObjCObjectVar<NSError?>>()
        stale.value = false
        error.value = null
        val url = NSURL.URLByResolvingBookmarkData(data, options = NSURLBookmarkResolutionWithoutUI,
            relativeToURL = null, bookmarkDataIsStale = stale.ptr, error = error.ptr)
            ?: error("Document access unavailable")
        check(!stale.value) { "Stale document bookmark; choose the document again" }
        check(url.startAccessingSecurityScopedResource()) { "Document access denied" }
        try { block(url) } finally { url.stopAccessingSecurityScopedResource() }
    }

    private fun <T> coordinate(url: NSURL, writing: Boolean, deleting: Boolean = false, block: (NSURL) -> T): T =
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            error.value = null
            var result: Result<T>? = null
            val coordinator = NSFileCoordinator(filePresenter = null)
            if (writing) {
                coordinator.coordinateWritingItemAtURL(url,
                    options = if (deleting) NSFileCoordinatorWritingForDeleting else NSFileCoordinatorWritingForReplacing,
                    error = error.ptr) { target -> result = runCatching { block(target!!) } }
            } else {
                coordinator.coordinateReadingItemAtURL(url, options = 0u,
                    error = error.ptr) { target -> result = runCatching { block(target!!) } }
            }
            check(error.value == null) { "Document coordination failed" }
            requireNotNull(result) { "Document coordination did not run" }.getOrThrow()
        }

    private companion object {
        const val PREFIX = "ios-bookmark:"
        const val MAX_BOOKMARK_CHARS = 1024 * 1024
    }
}
