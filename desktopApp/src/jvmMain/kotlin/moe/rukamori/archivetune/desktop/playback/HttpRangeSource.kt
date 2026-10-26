/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.desktop.playback

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceCancelledException
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceException
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceLease
import moe.rukamori.archivetune.shared.data.bytesource.LeaseCancellation
import moe.rukamori.archivetune.shared.data.bytesource.LeaseCookiePolicy
import moe.rukamori.archivetune.shared.data.bytesource.LeaseTicket
import moe.rukamori.archivetune.shared.data.bytesource.RandomAccessByteSource
import moe.rukamori.archivetune.shared.data.bytesource.ScopedCookie
import moe.rukamori.archivetune.shared.data.bytesource.StreamOrigin
import moe.rukamori.archivetune.shared.domain.playback.PlaybackHeaders

/**
 * The HTTP(S) bytes of one lease, read through ranged requests. The engine reaches it only through
 * [NativeStreamLease], so FFmpeg's own opener never makes a request for a header-bearing source.
 *
 * Credentials travel only to the primary origin and to same-origin redirects, because
 * [LeaseCookiePolicy.redirect] strips them on every other hop. A redirect the lease does not permit fails.
 * The socket timeouts bound each call, so a call that misses an interrupt still returns soon, and the
 * engine asks again. [interrupt] closes the socket, which wakes a blocked call at once.
 */
internal class HttpRangeSource(
    override val lease: ByteSourceLease,
    private val url: String,
    private val headers: PlaybackHeaders,
    private val cookies: Collection<ScopedCookie>,
) : RandomAccessByteSource {
    private val cancellation = LeaseCancellation()

    /** Read by [interrupt] from the command thread, so it is volatile and interrupt never waits for the producer. */
    @Volatile private var connection: HttpURLConnection? = null

    /** Set by [interrupt]. The next call drops a stream that the interrupt may have broken. */
    @Volatile private var staleStream = false

    // Confined to the producer thread, which is the only thread that opens or reads the stream.
    private var resolvedUrl = url
    private var stream: InputStream? = null
    private var streamPosition = 0L
    private var sizeBytes = -1L
    private var seekable = false

    /** The total length (-1 when the server did not report one) and whether ranged reads are honoured. */
    class Probe(val sizeBytes: Long, val seekable: Boolean)

    /** Opens the resource at offset 0 and keeps that response for the first read. */
    suspend fun probe(): Probe {
        val ticket = cancellation.issueTicket()
        try {
            checkNotExpired()
            dropStaleStream()
            val opened = openAt(0L, ticket, probing = true)
            if (opened == null) {
                sizeBytes = 0L
                seekable = false
            } else {
                sizeBytes = opened.totalBytes
                seekable = opened.partial
                stream = opened.input
                streamPosition = 0L
            }
            ticket.throwIfCancelled()
            return Probe(sizeBytes, seekable)
        } catch (failure: Throwable) {
            throw failureOf(failure, ticket)
        }
    }

    override suspend fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        val ticket = cancellation.issueTicket()
        try {
            checkNotExpired()
            dropStaleStream()
            if (length == 0) return 0
            if (sizeBytes >= 0 && position >= sizeBytes) {
                dropConnection()
                return RandomAccessByteSource.END_OF_STREAM
            }
            if (stream == null || streamPosition != position) {
                if (position > 0 && !seekable) throw ByteSourceException("the source cannot seek")
                dropConnection()
                val opened = openAt(position, ticket, probing = false)
                if (opened == null) return RandomAccessByteSource.END_OF_STREAM
                stream = opened.input
                streamPosition = position
                if (sizeBytes < 0) sizeBytes = opened.totalBytes
            }
            val input = checkNotNull(stream)
            val read = input.read(buffer, offset, length)
            ticket.throwIfCancelled()
            if (read < 0) {
                dropConnection()
                if (sizeBytes >= 0 && position < sizeBytes) {
                    throw ByteSourceException("the source ended before its declared length")
                }
                return RandomAccessByteSource.END_OF_STREAM
            }
            streamPosition += read
            return read
        } catch (io: IOException) {
            // Closing a socket is how an interrupt wakes a read, so a stale generation reports a cancellation.
            // Any other socket error or timeout is retried: the engine gives up after its starvation limit.
            if (!ticket.isCurrent) throw failureOf(io, ticket)
            dropConnection()
            return 0
        } catch (failure: Throwable) {
            throw failureOf(failure, ticket)
        }
    }

    /**
     * Runs on the command thread while the engine mutex is held. It flags the stream, cancels pending calls and closes
     * the socket, so it never waits and never calls into the engine.
     */
    fun interrupt() {
        staleStream = true
        cancellation.cancelAll()
        connection?.disconnect()
    }

    /** Frees the socket. The engine calls this exactly once, after it has finished with the lease. */
    fun close() {
        cancellation.cancelAll()
        dropConnection()
    }

    private class Opened(val input: InputStream, val totalBytes: Long, val partial: Boolean)

    /**
     * Requests [position] onward and follows permitted redirects. Returns null when the position lies past the end
     * (HTTP 416). Each hop is checked against the lease before its request leaves.
     */
    private fun openAt(position: Long, ticket: LeaseTicket, probing: Boolean): Opened? {
        var current = resolvedUrl
        var credentialed = true
        var redirects = 0
        while (true) {
            ticket.throwIfCancelled()
            val origin = StreamOrigin.parse(current) ?: throw ByteSourceException("the source is not an HTTP(S) URL")
            if (origin !in lease.permittedOrigins) {
                throw ByteSourceException("the source origin is not permitted by its lease")
            }
            val http = URL(current).openConnection() as HttpURLConnection
            connection = http
            http.instanceFollowRedirects = false
            http.useCaches = false
            http.requestMethod = "GET"
            http.connectTimeout = CONNECT_TIMEOUT_MS
            http.readTimeout = if (probing) PROBE_READ_TIMEOUT_MS else BODY_READ_TIMEOUT_MS
            if (credentialed) {
                headers.reveal().forEach { (name, value) -> http.setRequestProperty(name, value) }
                val cookie = LeaseCookiePolicy.cookiesFor(lease, origin, cookies)
                    .joinToString("; ") { "${it.name}=${it.value}" }
                if (cookie.isNotEmpty()) http.setRequestProperty("Cookie", cookie)
            }
            // Set last so a caller's header cannot override the range the engine depends on.
            http.setRequestProperty("Accept-Encoding", "identity")
            http.setRequestProperty("Range", "bytes=$position-")

            val code = http.responseCode
            if (code in REDIRECT_CODES) {
                val location = http.getHeaderField("Location")
                    ?: throw ByteSourceException("the source redirected without a location")
                dropConnection()
                redirects++
                if (redirects > MAX_REDIRECTS) throw ByteSourceException("the source redirected too often")
                val next = URI(current).resolve(location).toString()
                val nextOrigin = StreamOrigin.parse(next)
                    ?: throw ByteSourceException("the redirect target is not an HTTP(S) URL")
                val decision = LeaseCookiePolicy.redirect(lease, origin, nextOrigin)
                if (!decision.allowed) throw ByteSourceException("the lease refuses this redirect")
                credentialed = decision.carriesCredentials
                current = next
                continue
            }
            if (code == HTTP_RANGE_NOT_SATISFIABLE) {
                dropConnection()
                return null
            }
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                dropConnection()
                throw ByteSourceException("the source answered HTTP $code")
            }
            resolvedUrl = current
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                val total = parseContentRangeTotal(http.getHeaderField("Content-Range"), position)
                return Opened(http.inputStream, total, partial = true)
            }
            // A full answer to a request for later bytes means the server ignored the range, so the source
            // cannot be read from there. Reporting it as a failure is better than silently decoding from byte zero.
            if (position != 0L) throw ByteSourceException("the source ignored the requested byte range")
            return Opened(http.inputStream, http.contentLengthLong, partial = false)
        }
    }

    /** The total length from `Content-Range: bytes first-last/total`, or -1 when the total is unknown. */
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

    private fun checkNotExpired() {
        val expiresAt = lease.expiresAtEpochMs ?: return
        if (System.currentTimeMillis() >= expiresAt) throw ByteSourceException("the stream lease has expired")
    }

    private fun dropStaleStream() {
        if (staleStream) {
            staleStream = false
            dropConnection()
        }
    }

    private fun dropConnection() {
        val input = stream
        stream = null
        if (input != null) runCatching { input.close() }
        connection?.disconnect()
        connection = null
    }

    /** Drops the socket and names the outcome: a cancellation when the call's generation was interrupted. */
    private fun failureOf(cause: Throwable, ticket: LeaseTicket): Throwable {
        dropConnection()
        if (!ticket.isCurrent) return ByteSourceCancelledException()
        return if (cause is ByteSourceException) cause else ByteSourceException("the byte source failed", cause)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000

        /** The probe is the one call the engine cannot retry, so it waits longer than a body read. */
        const val PROBE_READ_TIMEOUT_MS = 10_000

        /** Short enough that a seek or stop is not held for long when an interrupt lands just before a read. */
        const val BODY_READ_TIMEOUT_MS = 3_000
        const val MAX_REDIRECTS = 5
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        const val UNKNOWN_LENGTH = -1L
        const val BYTES_PREFIX = "bytes "
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
