/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.platform.ios

import kotlinx.coroutines.*
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.KuromojiJapaneseTokenizerFactory
import platform.Foundation.*
import platform.darwin.NSObject
import platform.posix.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Foreground ephemeral data task, not a background URLSession/download watcher. The file writer is
 * guarded with cancellation by one NSLock; callbacks arriving after completion cannot touch staging.
 * The byte ceiling is checked BEFORE each write. Native exceptions/URLs never become UI messages.
 */
internal suspend fun downloadJapanesePack(destination: NSURL, progress: (Long, Long) -> Unit) =
    withContext(Dispatchers.Default) {
        withTimeout(180_000) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val output = fopen(requireNotNull(destination.path), "wb")
                    ?: error("Dictionary staging unavailable")
                val lock = NSLock()
                var done = false
                var downloaded = 0L
                var total = -1L
                var task: NSURLSessionDataTask? = null
                fun finish(failure: Exception?) {
                    var result = failure
                    lock.lock()
                    try {
                        if (done) return
                        done = true
                        if (result == null && (downloaded != KuromojiJapaneseTokenizerFactory.ARCHIVE_SIZE_BYTES.toLong() ||
                                fflush(output) != 0 || fsync(fileno(output)) != 0)) {
                            result = IllegalStateException("Incomplete dictionary transfer")
                        }
                        if (fclose(output) != 0 && result == null) result = IllegalStateException("Dictionary close failed")
                    } finally { lock.unlock() }
                    if (result == null) continuation.resume(Unit)
                    else {
                        task?.cancel()
                        continuation.resumeWithException(requireNotNull(result))
                    }
                }
                val delegate = object : NSObject(), NSURLSessionDataDelegateProtocol {
                    override fun URLSession(
                        session: NSURLSession, dataTask: NSURLSessionDataTask,
                        didReceiveResponse: NSURLResponse, completionHandler: (NSURLSessionResponseDisposition) -> Unit,
                    ) {
                        val http = didReceiveResponse as? NSHTTPURLResponse
                        total = didReceiveResponse.expectedContentLength
                        if (http?.statusCode != 200L ||
                            (total >= 0 && total != KuromojiJapaneseTokenizerFactory.ARCHIVE_SIZE_BYTES.toLong())) {
                            completionHandler(NSURLSessionResponseCancel)
                            finish(IllegalStateException("Dictionary response rejected"))
                        } else completionHandler(NSURLSessionResponseAllow)
                    }

                    override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveData: NSData) {
                        var failure: Exception? = null
                        lock.lock()
                        try {
                            if (done) return
                            check(didReceiveData.length <= KuromojiJapaneseTokenizerFactory.ARCHIVE_SIZE_BYTES.toULong() - downloaded.toULong())
                            val count = fwrite(didReceiveData.bytes, 1uL, didReceiveData.length, output)
                            check(count == didReceiveData.length) { "Dictionary write failed" }
                            downloaded += count.toLong()
                            progress(downloaded, total)
                        } catch (_: Exception) {
                            failure = IllegalStateException("Dictionary transfer failed")
                        } finally { lock.unlock() }
                        failure?.let(::finish)
                    }

                    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
                        finish(if (didCompleteWithError == null) null else IllegalStateException("Dictionary transfer failed"))
                    }
                }
                val configuration = NSURLSessionConfiguration.ephemeralSessionConfiguration
                configuration.allowsCellularAccess = true
                configuration.waitsForConnectivity = false
                configuration.timeoutIntervalForRequest = 15.0
                configuration.timeoutIntervalForResource = 180.0
                configuration.URLCache = null
                configuration.HTTPCookieStorage = null
                configuration.HTTPCookieAcceptPolicy = NSHTTPCookieAcceptPolicyNever
                val queue = NSOperationQueue().apply { maxConcurrentOperationCount = 1 }
                val session = NSURLSession.sessionWithConfiguration(configuration, delegate, queue)
                task = session.dataTaskWithURL(requireNotNull(NSURL.URLWithString(KuromojiJapaneseTokenizerFactory.ARCHIVE_URL)))
                continuation.invokeOnCancellation {
                    // Close synchronously before suspension unwinds and manager removes staging.
                    lock.lock()
                    try {
                        if (!done) { done = true; fclose(output) }
                    } finally { lock.unlock() }
                    task?.cancel()
                    session.invalidateAndCancel()
                }
                task?.resume()
                // finishTasksAndInvalidate waits asynchronously for the one resumed task.
                session.finishTasksAndInvalidate()
            }
        }
    }
