package moe.rukamori.archivetune.shared.data.platform.ios

import kotlin.time.Clock
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceAccount
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceException
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceLease
import moe.rukamori.archivetune.shared.data.bytesource.GuardedByteSource
import moe.rukamori.archivetune.shared.data.bytesource.LeaseCancellation
import moe.rukamori.archivetune.shared.data.bytesource.RandomAccessByteSource
import moe.rukamori.archivetune.shared.data.bytesource.StreamOrigin
import moe.rukamori.archivetune.shared.data.bytesource.readWholeBounded
import moe.rukamori.archivetune.shared.data.stream.NativeCiphertextOpener
import moe.rukamori.archivetune.shared.data.stream.NativePlaintext
import moe.rukamori.archivetune.shared.domain.playback.PlaybackHeaders
import moe.rukamori.archivetune.shared.domain.source.StreamUrlExpiry
import platform.Foundation.NSLock

/** The engine's unknown length. A view that states no length is read to its end without the engine assuming one. */
private const val UNKNOWN_LENGTH = -1L

/**
 * The host transport for the ciphertext of one native recipe or TIDAL manifest, read through ranged HTTP(S) requests. The
 * shared decryptors decide what the bytes mean, and this class only moves them.
 *
 * It is bound to one account scope and one account. A request for another scope is refused. A personal transport passes
 * [recheckFor], which binds every recheck to the admission that the route captured. Each request of every source, the
 * probe and each retry, range, seek and redirect hop included, rereads that entry and its cookies for its own origin
 * immediately before it leaves. No cookie is captured ahead of a request, so a cookie reaches only the account that read it
 * and only its origin. An anonymous stream passes null: it has no account to recheck and sends no cookie. The admitted
 * opener that wraps this one also checks the account generation before each open, so a caller of this class cannot skip
 * that check.
 *
 * Every source keeps its own lease, which permits only the origin of its own URL, so a redirect or a decryptor cannot leave
 * that host. Each source is tracked, so [interrupt] and [close] reach every request the stream has open. After [close] no
 * source can be opened.
 *
 * Opening probes the resource for its length with a blocking request, so an open must run on a worker or the producer
 * thread, never on the main thread.
 */
internal class IosCiphertextOpener(
    private val boundScope: String,
    private val account: ByteSourceAccount,
    // Builds the recheck for one lease. Null only for genuine anonymous access, which sends no cookie. A personal transport
    // binds every recheck to its captured admission, so no request is sent for an entry that is no longer accepted.
    private val recheckFor: ((ByteSourceLease) -> LeaseCookieRecheck)?,
    /**
     * Request headers every source of this transport carries. They travel only on the credential anchor's origin, as the
     * lease policy applies them: a segment host that is not the anchor never sees them. Default is none.
     */
    private val headers: PlaybackHeaders = PlaybackHeaders.NONE,
    /**
     * The origin that request headers and cookies belong to, such as the playlist origin of a segmented stream whose
     * segments live on other hosts. Null keeps the per-resource policy: each source's own origin is its credential
     * anchor, which is what the native recipes want, because their cookies are scoped per resource origin.
     */
    private val credentialAnchor: StreamOrigin? = null,
) : NativeCiphertextOpener {
    private val stateLock = NSLock()
    private val cancellation = LeaseCancellation()
    private val sources = ArrayList<IosHttpRangeSource>()
    private var closed = false

    override suspend fun open(
        url: String,
        accountScope: String,
    ): RandomAccessByteSource? {
        if (accountScope != boundScope) return null
        val origin = StreamOrigin.parse(url) ?: return null
        val lease = leaseFor(url, origin)
        // No cookie is read here. The recheck reads the admitted account before the probe and before every later request,
        // so a cookie taken at open could only be stale by the time a request used it.
        val http = IosHttpRangeSource(lease, url, headers, recheck = recheckFor?.invoke(lease))
        if (!register(http)) {
            // The opener is closed, so this source is refused and released rather than left unowned.
            http.close()
            return null
        }
        // The first response states the length. The shared TIDAL reader needs that length on the lease before it can index a
        // raw segment. A transport failure propagates rather than becoming a refusal, so a stalled host is not reported as a
        // recipe the host cannot serve. The request is released on both paths, so a failed open leaves no connection behind.
        val probed =
            try {
                http.probe()
            } catch (failure: Throwable) {
                forget(http)
                http.close()
                throw failure
            }
        if (!probed.seekable) {
            // The shared decryptors read at arbitrary offsets to index the container, so a host that answers the whole
            // body without ranges cannot serve them. An empty resource is refused on the same path.
            forget(http)
            http.close()
            return null
        }
        // The length is known, so the response kept for the first read is released now. The TIDAL index opens every segment
        // before any is read, and a kept response would hold one paused connection per segment until the stream ends.
        http.releaseProbedResponse()
        return ProbedCiphertext(http, lease.copy(lengthBytes = probed.sizeBytes.takeIf { it >= 0L }))
    }

    /**
     * Reads a small resource whole — a manifest document or a segment key — under the same scope, headers and lease
     * policy as [open]. Unlike [open] it does not require range support: the resource is read sequentially from its
     * first response, which a manifest or key host can always serve. Returns null when the resource is unavailable or
     * larger than [maxBytes]; a transport failure propagates rather than becoming a refusal.
     */
    override suspend fun openWhole(
        url: String,
        accountScope: String,
        maxBytes: Int,
    ): ByteArray? {
        if (accountScope != boundScope) return null
        val origin = StreamOrigin.parse(url) ?: return null
        val lease = leaseFor(url, origin)
        val http = IosHttpRangeSource(lease, url, headers, recheck = recheckFor?.invoke(lease))
        if (!register(http)) {
            http.close()
            return null
        }
        try {
            // The probe keeps its response at position zero, so the sequential read below reuses that one request.
            http.probe()
            val bytes = guard(http).readWholeBounded(maxBytes)
            forget(http)
            http.close()
            return bytes
        } catch (failure: Throwable) {
            forget(http)
            http.close()
            throw failure
        }
    }

    /**
     * The lease for one resource of this transport. Without a credential anchor each resource's own origin is the
     * credential origin, as the native recipes use it. With one, credentials attach only to that origin, which is the
     * policy a header-bearing segmented stream needs: the playlist's headers and cookies never reach a segment host.
     */
    private fun leaseFor(
        url: String,
        origin: StreamOrigin,
    ): ByteSourceLease {
        val anchor = credentialAnchor ?: origin
        return ByteSourceLease(
            account = account,
            primaryOrigin = anchor,
            permittedOrigins = setOf(anchor, origin),
            // The query string carries a per-request signature, so it never becomes part of the cache key.
            cacheKey = url.substringBefore('?'),
            expiresAtEpochMs = StreamUrlExpiry.fromUrl(url),
            lengthBytes = null,
        )
    }

    /** Guards [source] so a read in flight during [interrupt] or [close] is discarded, not handed to the decoder. */
    fun guard(source: RandomAccessByteSource): RandomAccessByteSource =
        GuardedByteSource(source, cancellation.issueTicket())

    /**
     * Cancels every outstanding read and breaks each request. It runs on the command thread while the engine mutex is held,
     * so it copies the sources under the lock, interrupts them outside it, and never waits for the producer thread.
     */
    fun interrupt() {
        val live =
            stateLock.holding {
                cancellation.cancelAll()
                sources.toList()
            }
        live.forEach { it.interrupt() }
    }

    /** Frees every request. A second call does nothing, and a source opened after this call is refused. */
    fun close() {
        val live =
            stateLock.holding {
                if (closed) {
                    emptyList<IosHttpRangeSource>()
                } else {
                    closed = true
                    cancellation.cancelAll()
                    sources.toList().also { sources.clear() }
                }
            }
        live.forEach { it.close() }
    }

    private fun register(source: IosHttpRangeSource): Boolean =
        stateLock.holding {
            if (closed) {
                false
            } else {
                sources += source
                true
            }
        }

    private fun forget(source: IosHttpRangeSource) {
        stateLock.holding { sources.remove(source) }
    }
}

/** A ciphertext source whose lease states the length that the probe learned. Its reads go to the request the opener tracks. */
private class ProbedCiphertext(
    private val http: IosHttpRangeSource,
    override val lease: ByteSourceLease,
) : RandomAccessByteSource {
    override suspend fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = http.readAt(position, buffer, offset, length)
}

/**
 * The engine's view of one prepared native plaintext stream. Its reads go through [IosCiphertextOpener.guard], and its
 * interrupt and close reach every ciphertext request the view has open. Like the Deezer view, it blocks the producer thread
 * and must never run on the main thread.
 */
internal class IosPlaintextSource(
    private val host: IosCiphertextOpener,
    private val plaintext: NativePlaintext,
) : IosLeaseSource {
    private val sizeBytes = plaintext.length ?: UNKNOWN_LENGTH

    override fun probe(): IosHttpRangeSource.Probe {
        checkNotExpired()
        return IosHttpRangeSource.Probe(sizeBytes, seekable = true)
    }

    // The guard is taken per read. A ticket fixed at construction would turn stale at the first interrupt and refuse every
    // later read, so a view that was interrupted once could never play again.
    override fun readBlocking(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        checkNotExpired()
        return runBlocking { host.guard(plaintext.source).readAt(position, buffer, offset, length) }
    }

    override fun interrupt() = host.interrupt()

    override fun close() = host.close()

    // iOS HTTP leases carry no expiry, so the authorization stated by the preparer is enforced here. A read after it
    // fails, which the lease reports to the engine as a failed read rather than as bytes the host no longer allows.
    private fun checkNotExpired() {
        val expiresAt = plaintext.expiresAtEpochMs ?: return
        if (Clock.System.now().toEpochMilliseconds() >= expiresAt) {
            throw ByteSourceException("the prepared authorization has expired; prepare the audio again")
        }
    }
}

private inline fun <T> NSLock.holding(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
