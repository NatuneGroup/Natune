package moe.rukamori.archivetune.desktop.playback

import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceAccount
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceCancelledException
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceLease
import moe.rukamori.archivetune.shared.data.bytesource.GuardedByteSource
import moe.rukamori.archivetune.shared.data.bytesource.LeaseCancellation
import moe.rukamori.archivetune.shared.data.bytesource.RandomAccessByteSource
import moe.rukamori.archivetune.shared.data.bytesource.ScopedCookieSource
import moe.rukamori.archivetune.shared.data.bytesource.StreamOrigin
import moe.rukamori.archivetune.shared.data.bytesource.cookiesForLease
import moe.rukamori.archivetune.shared.data.stream.NativeCiphertextOpener
import moe.rukamori.archivetune.shared.domain.playback.PlaybackHeaders
import moe.rukamori.archivetune.shared.domain.source.StreamUrlExpiry

/**
 * The host transport for the ciphertext of one native recipe: a ranged HTTP(S) source for each URL that the shared
 * decryptors open. The shared code decides what the bytes mean, and this class only moves them.
 *
 * It serves one prepared stream under one account scope. A request with another scope is refused, so a recipe never
 * reads with cookies that belong to a different account. [account] names the account the cookies are stored under, and
 * [cookies] supplies them. Pass the admitted account with its entry scope, as
 * `AccountAdmission.toByteSourceAccount()` gives it: the store matches cookies by that scope, so an identity-only
 * account reads none. An anonymous stream, such as TIDAL segments, passes null and sends no cookie. The account
 * generation is checked by the shared admitted opener that wraps this one, so a host opener cannot skip that check.
 *
 * Each source's lease permits only the origin of its own URL, so a redirect or a decryptor cannot leave that host. The
 * opener keeps every source it hands out, so [interrupt] and [close] reach all of them. After [close] no source can be
 * opened, and a source already handed out refuses its next read. A socket source reconnects on its next read, so
 * without that refusal a finished stream could still start new requests.
 */
internal class HostCiphertextOpener(
    private val boundScope: String,
    private val account: ByteSourceAccount,
    private val cookies: ScopedCookieSource?,
) : NativeCiphertextOpener {
    private val lock = Any()
    private val sources = ArrayList<HttpRangeSource>()
    private val cancellation = LeaseCancellation()
    private var closed = false

    override suspend fun open(
        url: String,
        accountScope: String,
    ): RandomAccessByteSource? {
        if (accountScope != boundScope) return null
        val origin = StreamOrigin.parse(url) ?: return null
        val lease =
            ByteSourceLease(
                account = account,
                primaryOrigin = origin,
                permittedOrigins = setOf(origin),
                // The query string often carries a signed token, so it never becomes part of the cache key.
                cacheKey = url.substringBefore('?'),
                // The shared DASH view can impose a stronger window; the transport must never extend the URL's.
                expiresAtEpochMs = StreamUrlExpiry.fromUrl(url),
                lengthBytes = null,
            )
        val stored = cookies?.cookiesForLease(lease, origin) ?: emptyList()
        val http = HttpRangeSource(lease, url, PlaybackHeaders.NONE, stored)
        if (!register(http)) return null
        // The first response states the length. The shared TIDAL reader needs it on the lease before it can index a
        // raw segment. The probe keeps that response for the first read, so the probe adds no request of its own.
        val probed = http.probe()
        return ProbedCiphertextSource(this, http, lease.copy(lengthBytes = probed.sizeBytes.takeIf { it >= 0L }))
    }

    /**
     * Wraps [source] so that a read still in flight when [interrupt] or [close] runs is discarded instead of reaching the
     * decoder. The ticket is issued here, so only a cancellation after this call reaches the wrapped view.
     */
    fun guard(source: RandomAccessByteSource): RandomAccessByteSource =
        GuardedByteSource(source, cancellation.issueTicket())

    /**
     * Cancels every outstanding read and breaks each socket. It runs on the command thread while the engine mutex is held,
     * so it copies the sources under the lock and interrupts them outside it, and it never waits for the producer thread.
     */
    fun interrupt() {
        val live =
            synchronized(lock) {
                cancellation.cancelAll()
                sources.toList()
            }
        live.forEach { it.interrupt() }
    }

    /** Frees every source. A second call does nothing, and a source opened after this call is refused. */
    fun close() {
        val live =
            synchronized(lock) {
                if (closed) {
                    emptyList<HttpRangeSource>()
                } else {
                    closed = true
                    cancellation.cancelAll()
                    sources.toList().also { sources.clear() }
                }
            }
        live.forEach { it.close() }
    }

    /** True once [close] has run. A closed opener refuses every new source and every read of a source it handed out. */
    val isClosed: Boolean
        get() = synchronized(lock) { closed }

    /**
     * Tracks a source that the caller opened itself, so [interrupt] and [close] reach it. The progressive route probes
     * its own transport before the engine owns the stream, so that transport is adopted here rather than opened by
     * [open]. Returns false after [close], and the caller then closes its source.
     */
    internal fun adopt(source: HttpRangeSource): Boolean = register(source)

    private fun register(source: HttpRangeSource): Boolean =
        synchronized(lock) {
            if (closed) {
                false
            } else {
                sources += source
                true
            }
        }
}

/**
 * A host source that states its length on its lease. The length comes from the first response, so the lease is copied
 * after the probe. Reads go to the socket source that the opener is tracking, so an interrupt reaches them too.
 */
private class ProbedCiphertextSource(
    private val owner: HostCiphertextOpener,
    private val http: HttpRangeSource,
    override val lease: ByteSourceLease,
) : RandomAccessByteSource {
    override suspend fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        // A released opener refuses the read rather than letting the socket source reconnect on its own.
        if (owner.isClosed) throw ByteSourceCancelledException()
        return http.readAt(position, buffer, offset, length)
    }
}
