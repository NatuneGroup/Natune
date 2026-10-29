package moe.rukamori.archivetune.shared.data.platform.ios

import kotlinx.cinterop.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.CoreFoundation.*
import platform.CoreGraphics.CGImageRelease
import platform.Foundation.*
import platform.ImageIO.*
import platform.UIKit.UIImage
import platform.darwin.NSObject
import kotlin.coroutines.resume

private const val ARTWORK_TIMEOUT_MS = 15_000L
private const val MAX_ARTWORK_BYTES = 2 * 1024 * 1024
private const val ARTWORK_EDGE_PIXELS = 512

/** Lock-screen artwork uses the queue's public image URI, never the playback lease or provider credentials. */
@OptIn(ExperimentalForeignApi::class)
internal suspend fun loadIosNowPlayingArtwork(uri: String): UIImage? = withContext(Dispatchers.Default) {
    val url = NSURL.URLWithString(uri)?.takeIf {
        '\u0000' !in uri && it.scheme?.lowercase() in setOf("http", "https") &&
            !it.host.isNullOrBlank() && it.user == null && it.password == null
    } ?: return@withContext null
    try {
        withTimeout(ARTWORK_TIMEOUT_MS) {
            val bytes = fetchArtwork(url) ?: return@withTimeout null
            currentCoroutineContext().ensureActive()
            downsampleArtwork(bytes)
        }
    } catch (_: TimeoutCancellationException) {
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

/** The byte ceiling is enforced before appending each chunk, not after URLSession has buffered an arbitrary body. */
@OptIn(ExperimentalForeignApi::class)
private suspend fun fetchArtwork(url: NSURL): ByteArray? = suspendCancellableCoroutine { continuation ->
    val lock = NSLock()
    val bytes = NSMutableData()
    var done = false
    fun finish(result: ByteArray?) {
        lock.lock()
        try {
            if (done) return
            done = true
        } finally {
            lock.unlock()
        }
        continuation.resume(result)
    }
    val delegate = object : NSObject(), NSURLSessionDataDelegateProtocol {
        override fun URLSession(
            session: NSURLSession,
            dataTask: NSURLSessionDataTask,
            didReceiveResponse: NSURLResponse,
            completionHandler: (NSURLSessionResponseDisposition) -> Unit,
        ) {
            val response = didReceiveResponse as? NSHTTPURLResponse
            if (response?.statusCode != 200L || didReceiveResponse.expectedContentLength > MAX_ARTWORK_BYTES) {
                completionHandler(NSURLSessionResponseCancel)
                finish(null)
            } else {
                completionHandler(NSURLSessionResponseAllow)
            }
        }

        override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveData: NSData) {
            var oversized = false
            lock.lock()
            try {
                if (done) return
                if (didReceiveData.length > MAX_ARTWORK_BYTES.toULong() - bytes.length) oversized = true
                else bytes.appendData(didReceiveData)
            } finally {
                lock.unlock()
            }
            if (oversized) {
                dataTask.cancel()
                finish(null)
            }
        }

        override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
            val result: ByteArray?
            lock.lock()
            try {
                if (done) return
                result = if (didCompleteWithError == null && bytes.length > 0uL) {
                    bytes.bytes?.reinterpret<ByteVar>()?.readBytes(bytes.length.toInt())
                } else null
            } finally {
                lock.unlock()
            }
            finish(result)
        }
    }
    val configuration = NSURLSessionConfiguration.ephemeralSessionConfiguration.apply {
        waitsForConnectivity = false
        timeoutIntervalForRequest = ARTWORK_TIMEOUT_MS / 1_000.0
        timeoutIntervalForResource = ARTWORK_TIMEOUT_MS / 1_000.0
        URLCache = null
        HTTPCookieStorage = null
        HTTPShouldSetCookies = false
        HTTPCookieAcceptPolicy = NSHTTPCookieAcceptPolicy.NSHTTPCookieAcceptPolicyNever
    }
    val queue = NSOperationQueue().apply { maxConcurrentOperationCount = 1 }
    val session = NSURLSession.sessionWithConfiguration(configuration, delegate, queue)
    val task = session.dataTaskWithURL(url)
    continuation.invokeOnCancellation {
        lock.lock()
        try { done = true } finally { lock.unlock() }
        task.cancel()
        session.invalidateAndCancel()
    }
    task.resume()
    session.finishTasksAndInvalidate()
}

/** ImageIO downsamples before full pixel decode, so even a compressed large cover cannot become a full-size UIImage. */
@OptIn(ExperimentalForeignApi::class)
private fun downsampleArtwork(bytes: ByteArray): UIImage? {
    if (bytes.isEmpty()) return null
    val data = bytes.usePinned {
        CFDataCreate(kCFAllocatorDefault, it.addressOf(0).reinterpret(), bytes.size.toLong())
    } ?: return null
    val sourceOptions = CFDictionaryCreateMutable(kCFAllocatorDefault, 0, null, null)
    val thumbnailOptions = CFDictionaryCreateMutable(kCFAllocatorDefault, 0, null, null)
    val edge = memScoped {
        val value = alloc<IntVar>()
        value.value = ARTWORK_EDGE_PIXELS
        CFNumberCreate(kCFAllocatorDefault, kCFNumberIntType, value.ptr)
    }
    try {
        if (sourceOptions == null || thumbnailOptions == null || edge == null) return null
        CFDictionarySetValue(sourceOptions, kCGImageSourceShouldCache, kCFBooleanFalse)
        val source = CGImageSourceCreateWithData(data, sourceOptions) ?: return null
        try {
            CFDictionarySetValue(thumbnailOptions, kCGImageSourceCreateThumbnailFromImageAlways, kCFBooleanTrue)
            CFDictionarySetValue(thumbnailOptions, kCGImageSourceCreateThumbnailWithTransform, kCFBooleanTrue)
            CFDictionarySetValue(thumbnailOptions, kCGImageSourceShouldCacheImmediately, kCFBooleanTrue)
            CFDictionarySetValue(thumbnailOptions, kCGImageSourceThumbnailMaxPixelSize, edge)
            val thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0uL, thumbnailOptions) ?: return null
            return try { UIImage.imageWithCGImage(thumbnail) } finally { CGImageRelease(thumbnail) }
        } finally {
            CFRelease(source)
        }
    } finally {
        edge?.let { CFRelease(it) }
        thumbnailOptions?.let { CFRelease(it) }
        sourceOptions?.let { CFRelease(it) }
        CFRelease(data)
    }
}
