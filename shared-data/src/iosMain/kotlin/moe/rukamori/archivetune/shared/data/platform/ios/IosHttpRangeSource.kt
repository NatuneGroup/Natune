@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.platform.ios

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceException
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceLease
import moe.rukamori.archivetune.shared.data.bytesource.LeaseCancellation
import moe.rukamori.archivetune.shared.data.bytesource.LeaseCookiePolicy
import moe.rukamori.archivetune.shared.data.bytesource.LeaseTicket
import moe.rukamori.archivetune.shared.data.bytesource.RandomAccessByteSource
import moe.rukamori.archivetune.shared.data.bytesource.ScopedCookie
import moe.rukamori.archivetune.shared.data.bytesource.StreamOrigin
import moe.rukamori.archivetune.shared.domain.playback.PlaybackHeaders
import moe.rukamori.archivetune.shared.domain.playback.SystemMonotonicClock
import platform.Foundation.*
import platform.darwin.NSObject
import platform.posix.memcpy
import platform.posix.usleep

/** A probe waits for its response head longer than a body read, because the engine cannot retry a failed probe. */
private const val PROBE_TIMEOUT_MS = 10_000L

/** Connect, first byte and idle time for one body request. A longer silence fails it, and the next read reopens it. */
private const val BODY_TIMEOUT_MS = 10_000L

/**
 * Longest wait for bytes in one read before it answers RETRY. The engine asks again after each RETRY, and its starvation
 * limit counts elapsed time, so a short slice keeps seeks and stops prompt without shortening the retry window.
 */
private const val BODY_STALL_BUDGET_MS = 500L

/** Slack past a request timeout, so the platform reports its own failure before our own wait gives up. */
private const val HEAD_SLACK_MS = 2_000L

private const val MAX_REDIRECTS = 5
private const val HTTP_OK = 200L
private const val HTTP_PARTIAL = 206L
private const val HTTP_RANGE_NOT_SATISFIABLE = 416L
private val REDIRECT_CODES = setOf(301L, 302L, 303L, 307L, 308L)
private const val UNKNOWN_LENGTH = -1L
private const val BYTES_PREFIX = "bytes "
private const val NANOS_PER_MS = 1_000_000L

/** Received but unread bytes above HIGH pause the connection until the reader drains them below LOW. */
private const val HIGH_WATER_BYTES = 4L * 1024 * 1024
private const val LOW_WATER_BYTES = 1L * 1024 * 1024

/** Each blocked wait rechecks its ticket this often, so an interrupt is noticed within one interval. */
private val POLL_INTERVAL_MICROS: UInt = 2_000u

/** A stalled or dropped connection. The engine may retry it, unlike a refusal by the origin. */
private class TransientTransferException(message: String) : Exception(message)

/** The status and headers that decide the next step, captured before any body byte is consumed. */
private class ResponseHead(
    val statusCode: Long,
    val fields: Map<*, *>,
    val expectedLength: Long,
)

private inline fun <T> NSLock.locked(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}

/**
 * One ranged response on its own ephemeral session, so each delegate callback belongs to this transfer alone. Redirects
 * are refused here, and the owning source follows each permitted hop itself, so the lease decides every request that
 * leaves. Received bytes queue until the reader takes them, and the task pauses above [HIGH_WATER_BYTES]. That bounds
 * memory while the engine reads at playback speed.
 */
private class RangeTransfer : NSObject(), NSURLSessionDataDelegateProtocol {
    private val lock = NSLock()
    private var task: NSURLSessionDataTask? = null
    private var head: ResponseHead? = null
    private val chunks = ArrayDeque<ByteArray>()
    private var headOffset = 0
    private var buffered = 0L
    private var paused = false
    private var finished = false
    private var broken = false
    private var cancelled = false

    /** True once the connection failed rather than ended. The reader then reopens at its own position. */
    val isBroken: Boolean
        get() = lock.locked { broken }

    /** Starts [request] on a fresh session. The session invalidates itself once its one task completes. */
    fun start(request: NSURLRequest, timeoutMs: Long) {
        val configuration = NSURLSessionConfiguration.ephemeralSessionConfiguration
        configuration.allowsCellularAccess = true
        configuration.timeoutIntervalForRequest = timeoutMs.toDouble() / 1000.0
        configuration.waitsForConnectivity = false
        // Cookies come only from the lease policy, never from a platform jar that could replay them across origins.
        configuration.URLCache = null
        configuration.HTTPCookieStorage = null
        configuration.HTTPCookieAcceptPolicy = NSHTTPCookieAcceptPolicy.NSHTTPCookieAcceptPolicyNever
        // The SDK request timeout setter is gone, so the timeout moves to this session, which serves one request.
        val queue = NSOperationQueue().apply { maxConcurrentOperationCount = 1 }
        val session = NSURLSession.sessionWithConfiguration(configuration, this, queue)
        val created = session.dataTaskWithRequest(request)
        // A cancel that raced ahead of start still stops the request, so no task ever runs unowned.
        val live = lock.locked {
            task = created
            !cancelled
        }
        if (live) created.resume() else created.cancel()
        session.finishTasksAndInvalidate()
    }

    /** Cancels the task. Safe from any thread, and it never waits for the delegate queue. */
    fun cancel() {
        var current: NSURLSessionDataTask? = null
        lock.locked {
            cancelled = true
            current = task
        }
        current?.cancel()
    }

    /** Blocks until the response head arrives. A missing head is transient, and a stale ticket throws its cancellation. */
    fun awaitHead(ticket: LeaseTicket, budgetMs: Long): ResponseHead {
        val deadline = SystemMonotonicClock.nowNanos() + budgetMs * NANOS_PER_MS
        while (true) {
            ticket.throwIfCancelled()
            var ready: ResponseHead? = null
            var ended = false
            lock.locked {
                ready = head
                ended = finished
            }
            ready?.let { return it }
            if (ended) throw TransientTransferException("the source connection ended before its response")
            if (SystemMonotonicClock.nowNanos() >= deadline) {
                cancel()
                throw TransientTransferException("the source did not answer in time")
            }
            usleep(POLL_INTERVAL_MICROS)
        }
    }

    /**
     * Copies buffered bytes into [out]. Returns the count, [RandomAccessByteSource.END_OF_STREAM] at a clean end, or 0
     * when nothing arrived within the stall budget or the connection broke. [isBroken] tells those two apart.
     */
    fun readInto(out: ByteArray, offset: Int, length: Int, ticket: LeaseTicket): Int {
        val deadline = SystemMonotonicClock.nowNanos() + BODY_STALL_BUDGET_MS * NANOS_PER_MS
        while (true) {
            ticket.throwIfCancelled()
            var copied = 0
            var ended = false
            var endedBroken = false
            var resumeTask: NSURLSessionDataTask? = null
            lock.locked {
                while (copied < length && chunks.isNotEmpty()) {
                    val first = chunks.first()
                    val count = minOf(length - copied, first.size - headOffset)
                    first.copyInto(out, offset + copied, headOffset, headOffset + count)
                    copied += count
                    headOffset += count
                    buffered -= count
                    if (headOffset == first.size) {
                        chunks.removeFirst()
                        headOffset = 0
                    }
                }
                if (paused && buffered < LOW_WATER_BYTES) {
                    paused = false
                    resumeTask = task
                }
                ended = finished && chunks.isEmpty()
                endedBroken = broken
            }
            // Resumed outside the lock: a platform call never runs while a delegate callback could be waiting on it.
            resumeTask?.resume()
            if (copied > 0) return copied
            if (ended) return if (endedBroken) 0 else RandomAccessByteSource.END_OF_STREAM
            if (SystemMonotonicClock.nowNanos() >= deadline) return 0
            usleep(POLL_INTERVAL_MICROS)
        }
    }

    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveResponse: NSURLResponse,
        completionHandler: (NSURLSessionResponseDisposition) -> Unit,
    ) {
        val response = didReceiveResponse as? NSHTTPURLResponse
        lock.locked {
            if (head == null) head = responseHead(response, didReceiveResponse.expectedContentLength)
        }
        completionHandler(NSURLSessionResponseAllow)
    }

    override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveData: NSData) {
        val bytes = didReceiveData.toByteArray()
        if (bytes.isEmpty()) return
        var pause: NSURLSessionDataTask? = null
        lock.locked {
            if (cancelled || finished) return
            chunks.addLast(bytes)
            buffered += bytes.size
            if (!paused && buffered >= HIGH_WATER_BYTES) {
                paused = true
                pause = task
            }
        }
        pause?.suspend()
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        willPerformHTTPRedirection: NSHTTPURLResponse,
        newRequest: NSURLRequest,
        completionHandler: (NSURLRequest?) -> Unit,
    ) {
        lock.locked {
            if (head == null) {
                head = responseHead(willPerformHTTPRedirection, willPerformHTTPRedirection.expectedContentLength)
            }
        }
        // Refused on purpose: the 3xx answer becomes the response, and the owning source decides the next hop.
        completionHandler(null)
    }

    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
        lock.locked {
            if (didCompleteWithError != null && !cancelled) broken = true
            finished = true
        }
    }
}

private fun responseHead(response: NSHTTPURLResponse?, expectedLength: Long): ResponseHead = ResponseHead(
    statusCode = response?.statusCode ?: 0L,
    fields = (response?.allHeaderFields as? Map<*, *>) ?: emptyMap<Any?, Any?>(),
    expectedLength = expectedLength,
)

/** Header lookup is case-insensitive: HTTP/2 peers send lowercase names that a plain map lookup would miss. */
private fun headerValue(fields: Map<*, *>, name: String): String? =
    fields.entries.firstOrNull { (it.key as? String)?.equals(name, ignoreCase = true) == true }?.value as? String

private fun resolveLocation(location: String, base: String): String? =
    NSURL.URLWithString(location, NSURL.URLWithString(base))?.absoluteString

/** The total length from `Content-Range: bytes first-last/total`, or [UNKNOWN_LENGTH] when the server gives `*`. */
private fun parseContentRangeTotal(value: String?, position: Long): Long {
    val spec = value?.trim()?.takeIf { it.startsWith(BYTES_PREFIX) }?.removePrefix(BYTES_PREFIX)?.trim()
        ?: throw ByteSourceException("the partial response has no byte range")
    val slash = spec.indexOf('/')
    if (slash < 0) throw ByteSourceException("the partial response has a malformed byte range")
    val first = spec.substring(0, slash).substringBefore('-').toLongOrNull()
        ?: throw ByteSourceException("the partial response has a malformed byte range")
    if (first != position) throw ByteSourceException("the partial response starts at the wrong byte")
    val total = spec.substring(slash + 1)
    if (total == "*") return UNKNOWN_LENGTH
    return total.toLongOrNull() ?: throw ByteSourceException("the partial response has a malformed length")
}

private fun NSData.toByteArray(): ByteArray {
    val count = length.toInt()
    val out = ByteArray(count)
    if (count > 0) out.usePinned { memcpy(it.addressOf(0), this@toByteArray.bytes, length) }
    return out
}

/**
 * Reads the cookies for one hop of a personal lease immediately before that hop's request leaves. It runs before every
 * request, the first open included, so the read must still name the admitted entry, and a throw refuses the request
 * before anything is sent. It runs on the producer thread, which may block on the account store, and never on the main
 * thread.
 */
internal fun interface LeaseCookieRecheck {
    fun cookiesFor(origin: StreamOrigin): Collection<ScopedCookie>
}

/**
 * The HTTP(S) bytes of one lease, read through ranged requests on NSURLSession. The engine reaches it only through
 * [IosStreamLease], so the engine's own opener never sends a request for a header-bearing source. The policy mirrors
 * the desktop HttpRangeSource: credentials go only to the primary origin and to same-origin redirects, a redirect the
 * lease does not permit fails, and an interrupt cancels the in-flight request without calling back into the engine.
 * A personal lease reads its account again through [recheck] before every hop, so the first open is checked too.
 */
internal class IosHttpRangeSource(
    override val lease: ByteSourceLease,
    private val url: String,
    private val headers: PlaybackHeaders,
    // Fixed cookies for a lease without a recheck. A personal lease reads its cookies through the recheck instead.
    private val cookies: Collection<ScopedCookie> = emptyList(),
    // Null for every anonymous or cookie-free lease. A personal lease passes one, so every hop reads it first.
    private val recheck: LeaseCookieRecheck? = null,
) : RandomAccessByteSource, IosLeaseSource {
    private val cancellation = LeaseCancellation()
    private val stateLock = NSLock()

    // Guarded by stateLock. [transfer] is the connection the producer reads, and interrupt reads it without waiting.
    private var transfer: RangeTransfer? = null
    private var staleTransfer = false
    private var closed = false

    // Confined to the producer thread, which is the only thread that opens or reads the stream.
    private var resolvedUrl = url
    private var streamPosition = 0L
    private var sizeBytes = UNKNOWN_LENGTH
    private var seekable = false

    /** The total length (-1 when the server did not report one) and whether ranged reads are honoured. */
    class Probe(val sizeBytes: Long, val seekable: Boolean)

    /** The response that feeds reads, with the length it declares. */
    private class Opened(val transfer: RangeTransfer, val totalBytes: Long, val partial: Boolean)

    /** Opens the resource at offset zero and keeps that response for the first read. Producer thread only. */
    override fun probe(): Probe {
        checkOpen()
        val ticket = cancellation.issueTicket()
        dropStaleTransfer()
        checkNotExpired()
        try {
            val opened = openAt(0L, ticket, probing = true)
            if (opened == null) {
                sizeBytes = 0L
                seekable = false
                dropTransfer()
            } else {
                sizeBytes = opened.totalBytes
                seekable = opened.partial
                streamPosition = 0L
                replaceTransfer(opened.transfer)
            }
            ticket.throwIfCancelled()
        } catch (failure: Throwable) {
            dropTransfer()
            throw failure
        }
        return Probe(sizeBytes, seekable)
    }

    /**
     * Drops the response that [probe] kept for the first read. A caller that needs only the length, such as the TIDAL
     * index, would otherwise hold one paused connection per indexed resource until the stream ends. The next read reopens
     * at the stream position, which costs one more request. Producer thread only.
     */
    fun releaseProbedResponse() = dropTransfer()

    /** Suspending entry for the common contract. The body never suspends, so [readBlocking] serves the producer thread. */
    override suspend fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
        readBlocking(position, buffer, offset, length)

    /**
     * Reads without suspending, for the engine's producer thread. It waits at most [BODY_STALL_BUDGET_MS] for bytes
     * and returns 0 when none arrived, which the caller reports as AE_IO_RETRY. It must never run on the main thread.
     */
    override fun readBlocking(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        checkOpen()
        if (position < 0L) throw ByteSourceException("the read position is negative")
        if (offset < 0 || length < 0 || offset > buffer.size - length) {
            throw ByteSourceException("the read window lies outside the buffer")
        }
        val ticket = cancellation.issueTicket()
        dropStaleTransfer()
        checkNotExpired()
        if (length == 0) return 0
        if (sizeBytes >= 0 && position >= sizeBytes) {
            dropTransfer()
            return RandomAccessByteSource.END_OF_STREAM
        }
        try {
            val active: RangeTransfer = stateLock.locked { transfer }?.takeIf { streamPosition == position }
                ?: run {
                    if (position > 0 && !seekable) throw ByteSourceException("the source cannot seek")
                    dropTransfer()
                    val opened = openAt(position, ticket, probing = false)
                        ?: return RandomAccessByteSource.END_OF_STREAM
                    replaceTransfer(opened.transfer)
                    streamPosition = position
                    if (sizeBytes < 0) sizeBytes = opened.totalBytes
                    opened.transfer
                }
            val read = active.readInto(buffer, offset, length, ticket)
            // Bytes that arrived for a stale generation are discarded, never handed to the engine.
            ticket.throwIfCancelled()
            return when {
                read > 0 -> {
                    streamPosition += read
                    read
                }
                read == RandomAccessByteSource.END_OF_STREAM -> {
                    dropTransfer()
                    if (sizeBytes >= 0 && streamPosition < sizeBytes) {
                        throw ByteSourceException("the source ended before its declared length")
                    }
                    RandomAccessByteSource.END_OF_STREAM
                }
                else -> {
                    // No bytes yet: the caller answers RETRY. A broken connection is reopened at streamPosition next time.
                    if (active.isBroken) dropTransfer()
                    0
                }
            }
        } catch (transient: TransientTransferException) {
            dropTransfer()
            return 0
        } catch (failure: Throwable) {
            dropTransfer()
            throw failure
        }
    }

    /**
     * Runs on the command thread while the engine mutex is held. It flags the transfer, cancels the generation and
     * cancels the request. It never waits for the producer and never calls into the engine.
     */
    override fun interrupt() {
        val current = stateLock.locked {
            staleTransfer = true
            transfer
        }
        cancellation.cancelAll()
        current?.cancel()
    }

    /** Frees the connection. The engine calls this once, after it has finished with the lease. */
    override fun close() {
        stateLock.locked { closed = true }
        cancellation.cancelAll()
        dropTransfer()
    }

    /**
     * Requests [position] onward and follows permitted redirects, checking each hop against the lease before its request
     * leaves. Returns null when the position lies past the end (HTTP 416). Producer thread only.
     */
    private fun openAt(position: Long, ticket: LeaseTicket, probing: Boolean): Opened? {
        var current = resolvedUrl
        var origin = StreamOrigin.parse(current) ?: throw ByteSourceException("the source is not an HTTP(S) URL")
        // Credentials start only on the primary origin, so a resolved URL elsewhere never receives them by default.
        var credentialed = origin == lease.primaryOrigin
        var redirects = 0
        while (true) {
            ticket.throwIfCancelled()
            if (origin !in lease.permittedOrigins) {
                throw ByteSourceException("the source origin is not permitted by its lease")
            }
            val hop = RangeTransfer()
            var kept = false
            try {
                val stored = cookiesForHop(origin, credentialed)
                // A cancel that arrived during the account read stops the request before it leaves the device.
                ticket.throwIfCancelled()
                hop.start(buildRequest(current, origin, position, credentialed, stored, probing), if (probing) PROBE_TIMEOUT_MS else BODY_TIMEOUT_MS)
                val head = hop.awaitHead(ticket, if (probing) PROBE_TIMEOUT_MS + HEAD_SLACK_MS else BODY_TIMEOUT_MS + HEAD_SLACK_MS)
                val code = head.statusCode
                when {
                    code in REDIRECT_CODES -> {
                        val location = headerValue(head.fields, "Location")
                            ?: throw ByteSourceException("the source redirected without a location")
                        redirects++
                        if (redirects > MAX_REDIRECTS) throw ByteSourceException("the source redirected too often")
                        val next = resolveLocation(location, current)
                            ?: throw ByteSourceException("the redirect target is not an HTTP(S) URL")
                        val nextOrigin = StreamOrigin.parse(next)
                            ?: throw ByteSourceException("the redirect target is not an HTTP(S) URL")
                        val decision = LeaseCookiePolicy.redirect(lease, origin, nextOrigin)
                        if (!decision.allowed) throw ByteSourceException("the lease refuses this redirect")
                        credentialed = decision.carriesCredentials
                        current = next
                        origin = nextOrigin
                    }
                    code == HTTP_RANGE_NOT_SATISFIABLE -> return null
                    code != HTTP_OK && code != HTTP_PARTIAL ->
                        throw ByteSourceException("the source answered HTTP $code")
                    code == HTTP_PARTIAL -> {
                        val total = parseContentRangeTotal(headerValue(head.fields, "Content-Range"), position)
                        resolvedUrl = current
                        kept = true
                        return Opened(hop, total, partial = true)
                    }
                    // A full answer to a later position means the server ignored the range. Failing beats decoding from byte zero.
                    position != 0L -> throw ByteSourceException("the source ignored the requested byte range")
                    else -> {
                        resolvedUrl = current
                        kept = true
                        return Opened(hop, head.expectedLength, partial = false)
                    }
                }
            } finally {
                if (!kept) hop.cancel()
            }
        }
    }

    /**
     * The cookies that one hop may carry. A personal lease reads its account before every hop, the first open included
     * and a hop that carries no cookies, so a sign-out, a replacement or an unreadable store refuses the request before it
     * is sent. A lease without a recheck carries its fixed list, and only on a hop that may carry credentials.
     */
    private fun cookiesForHop(origin: StreamOrigin, credentialed: Boolean): Collection<ScopedCookie> {
        val checked = recheck?.cookiesFor(origin)
        return when {
            !credentialed -> emptyList()
            checked != null -> checked
            else -> cookies
        }
    }

    private fun buildRequest(
        target: String,
        origin: StreamOrigin,
        position: Long,
        credentialed: Boolean,
        stored: Collection<ScopedCookie>,
        probing: Boolean,
    ): NSURLRequest {
        val nsUrl = NSURL.URLWithString(target) ?: throw ByteSourceException("the source is not an HTTP(S) URL")
        val request = NSMutableURLRequest(nsUrl)
        request.HTTPMethod = "GET"
        request.HTTPShouldHandleCookies = false
        if (credentialed) {
            headers.reveal().forEach { (name, value) -> request.setValue(value, forHTTPHeaderField = name) }
            val cookie = LeaseCookiePolicy.cookiesFor(lease, origin, stored)
                .joinToString("; ") { "${it.name}=${it.value}" }
            if (cookie.isNotEmpty()) request.setValue(cookie, forHTTPHeaderField = "Cookie")
        }
        // Set last so a caller's header cannot override the range that the engine depends on.
        request.setValue("identity", forHTTPHeaderField = "Accept-Encoding")
        request.setValue("bytes=$position-", forHTTPHeaderField = "Range")
        return request
    }

    private fun checkOpen() {
        if (stateLock.locked { closed }) throw ByteSourceException("the byte source is closed")
    }

    private fun checkNotExpired() {
        val expiresAt = lease.expiresAtEpochMs ?: return
        if (NSDate().timeIntervalSince1970 * 1000.0 >= expiresAt.toDouble()) {
            throw ByteSourceException("the stream lease has expired")
        }
    }

    private fun dropStaleTransfer() {
        val stale = stateLock.locked {
            val was = staleTransfer
            staleTransfer = false
            was
        }
        if (stale) dropTransfer()
    }

    private fun dropTransfer() = replaceTransfer(null)

    private fun replaceTransfer(next: RangeTransfer?) {
        val previous = stateLock.locked {
            val old = transfer
            transfer = next
            old
        }
        previous?.cancel()
    }
}
