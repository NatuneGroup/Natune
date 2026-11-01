package moe.rukamori.archivetune.desktop.playback

import kotlinx.coroutines.CancellationException
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceCancelledException
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceException
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceLease
import moe.rukamori.archivetune.shared.data.bytesource.RandomAccessByteSource
import moe.rukamori.archivetune.shared.data.bytesource.ScopedCookie
import moe.rukamori.archivetune.shared.data.bytesource.ScopedCookieSource
import moe.rukamori.archivetune.shared.data.bytesource.StreamOrigin
import moe.rukamori.archivetune.shared.data.bytesource.cookiesForLease
import moe.rukamori.archivetune.shared.data.bytesource.toByteSourceAccount
import moe.rukamori.archivetune.shared.data.stream.NativePlaintext
import moe.rukamori.archivetune.shared.data.stream.NativeRouteOutcome
import moe.rukamori.archivetune.shared.data.stream.accountProviderId
import moe.rukamori.archivetune.shared.domain.platform.ANONYMOUS_ACCOUNT_SCOPE
import moe.rukamori.archivetune.shared.domain.platform.AccountAdmission
import moe.rukamori.archivetune.shared.domain.platform.AcceptedAccountReader
import moe.rukamori.archivetune.shared.domain.playback.PreparedPlaintextHandle
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType

/**
 * True when a progressive stream names a personal account. Such a stream must be prepared through
 * [DesktopPlaybackPort.prepareNative] and never played as an ordinary remote locator, because that locator carries no
 * account cookie. A null, blank or anonymous scope is genuinely anonymous and keeps its remote locator.
 */
fun PreparedAudio.Progressive.requiresAdmittedRoute(): Boolean {
    val scope = stream.accountScope
    return !scope.isNullOrBlank() && scope != ANONYMOUS_ACCOUNT_SCOPE
}

/**
 * Admits one personal progressive stream and opens it through its own host transport. Every check runs before the
 * stream reads a byte: the scope and URL are valid, the store and cookie reader are bound, the account is accepted under
 * that scope, and the probe request carries the cookies that the live store returns for that account.
 *
 * A refusal is returned, so the caller falls through its source chain and never plays the same bytes anonymously. Only
 * cancellation is thrown from here. Every admission, store, cookie and transport failure is a refusal, and the transport
 * is closed on each path that does not return a ready handle. The blocking probe runs under the dispatcher that
 * [DesktopPlaybackPort.prepareNative] chooses, so this is never called on the playback thread.
 */
internal suspend fun prepareAdmittedProgressive(
    prepared: PreparedAudio.Progressive,
    accounts: AcceptedAccountReader?,
    cookies: ScopedCookieSource?,
): NativeRouteOutcome {
    val stream = prepared.stream
    val scope = stream.accountScope
    if (!prepared.requiresAdmittedRoute() || scope == null) {
        return NativeRouteOutcome.Refused("Anonymous ${stream.source.name} audio keeps its ordinary remote locator")
    }
    // Deezer's decrypting lease is cookie-free by design, so no Deezer stream is read with account cookies here.
    if (stream.source == AudioSourceType.DEEZER) {
        return NativeRouteOutcome.Refused("Deezer audio is never read with account cookies; the source chain continues")
    }
    val store = accounts ?: return NativeRouteOutcome.Refused("No accepted-account store is bound, so nothing was read")
    // Without the cookie store the stream would be sent with no cookies, which is a silent downgrade to anonymous.
    val cookieStore =
        cookies
            ?: return NativeRouteOutcome.Refused(
                "No cookie store is bound, so the ${stream.source.name} account cannot be read",
            )
    val provider =
        stream.source.accountProviderId()
            ?: return NativeRouteOutcome.Refused("${stream.source.name} has no account slot")
    val origin =
        StreamOrigin.parse(stream.uri)
            ?: return NativeRouteOutcome.Refused("The ${stream.source.name} stream is not a plain HTTP(S) URL")
    // An unreadable store admits nothing, so it refuses in the same way that a missing account does.
    val admission: AccountAdmission? =
        try {
            store.admit(provider, scope)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unreadable: Exception) {
            null
        }
    val admitted =
        admission ?: return NativeRouteOutcome.Refused("No accepted $provider account; sign in again to play it")
    val account = admitted.toByteSourceAccount()
    val lease =
        ByteSourceLease(
            account = account,
            primaryOrigin = origin,
            permittedOrigins = setOf(origin),
            // The query string often carries a signed token, so it never becomes part of the cache key.
            cacheKey = stream.uri.substringBefore('?'),
            expiresAtEpochMs = stream.expiresAtEpochMs,
            lengthBytes = null,
        )
    val liveCookies = LiveCookies()
    val http = HttpRangeSource(lease, stream.uri, stream.headers, liveCookies)
    val opener = HostCiphertextOpener(boundScope = scope, account = account, cookies = cookieStore)
    if (!opener.adopt(http)) {
        // The opener was made a moment ago, so this refusal is a guard rather than an expected path.
        http.close()
        return NativeRouteOutcome.Refused("The ${stream.source.name} transport closed before the stream opened")
    }
    val gate = AdmittedStreamGate(admitted, store, cookieStore, origin, lease, liveCookies, opener)
    return try {
        // The probe is the first open. The gate runs before its request, so that request already carries live cookies.
        gate.beforeRequest()
        val probe = http.probe()
        val length = probe.sizeBytes.takeIf { it >= 0L }
        val view = AdmittedProgressiveSource(http, gate, lease.copy(lengthBytes = length))
        val plaintext =
            NativePlaintext(
                source = view,
                mimeType = stream.mimeType,
                codec = stream.codecs,
                length = length,
                expiresAtEpochMs = stream.expiresAtEpochMs,
            )
        val handle = ProgressivePlaintextHandle(stream.mimeType, stream.codecs, length, stream.expiresAtEpochMs)
        NativeRouteOutcome.Ready(HostPreparedPlaintext(handle, plaintext, opener))
    } catch (refused: ByteSourceException) {
        opener.close()
        NativeRouteOutcome.Refused(refused.message ?: "The ${stream.source.name} stream could not be opened")
    } catch (failure: Throwable) {
        opener.close()
        throw failure
    }
}

/**
 * Runs one read of the account store. A failed read becomes a byte-source refusal, so it never escapes as a crash and
 * never reaches the socket. A cancellation keeps propagating unchanged.
 */
private suspend fun <T> readStoreOrRefuse(read: suspend () -> T): T =
    try {
        read()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (unreadable: Exception) {
        throw ByteSourceException("the account store could not be read; nothing is read", unreadable)
    }

/**
 * The cookies that the stream's lease may send, as the last gate read them. A plain list would hold the cookies read at
 * prepare time for every reconnect and seek, because HttpRangeSource reads the collection it is given on each request it
 * makes. The gate replaces the contents before each request, so every request sends the cookies that were live then.
 */
private class LiveCookies : AbstractCollection<ScopedCookie>() {
    @Volatile
    private var current: List<ScopedCookie> = emptyList()

    fun replaceWith(cookies: List<ScopedCookie>) {
        current = cookies
    }

    override val size: Int
        get() = current.size

    override fun iterator(): Iterator<ScopedCookie> = current.iterator()
}

/**
 * Runs before every read of a progressive stream, not only before a request, so an open socket stops at its next read
 * once its owner has gone. A read that reconnects after a seek, a retry or a dropped socket is covered by the same
 * check. It refuses unless the account that admitted the stream is still accepted under the same scope and generation,
 * and then refreshes the cookies that the lease may send from the live store. A signed-out, replaced or re-scoped
 * account therefore sends nothing, and an older cookie is never replayed.
 */
private class AdmittedStreamGate(
    private val admission: AccountAdmission,
    private val accounts: AcceptedAccountReader,
    private val cookies: ScopedCookieSource,
    private val origin: StreamOrigin,
    private val lease: ByteSourceLease,
    private val liveCookies: LiveCookies,
    private val transport: HostCiphertextOpener,
) {
    suspend fun beforeRequest() {
        // A released transport reads nothing and sends nothing, so a stream that the engine let go cannot reconnect.
        if (transport.isClosed) throw ByteSourceCancelledException()
        // The cookies are dropped before the owner is proved again, so a refused check leaves no older cookie live.
        liveCookies.replaceWith(emptyList())
        val current = readStoreOrRefuse { accounts.acceptedAccount(admission.provider, admission.accountScope) }
        if (current != admission.accepted) {
            throw ByteSourceException("the account that admitted this stream is no longer accepted; nothing is read")
        }
        liveCookies.replaceWith(readStoreOrRefuse { cookies.cookiesForLease(lease, origin) })
    }
}

/**
 * The bytes of one admitted progressive stream. Each read passes the gate before it touches the socket, so a stream whose
 * account has gone stops at its next read. A refused or failed read closes the socket, so a stopped stream holds no
 * connection, and the next read that the gate allows opens a new request with fresh cookies.
 */
private class AdmittedProgressiveSource(
    private val http: HttpRangeSource,
    private val gate: AdmittedStreamGate,
    override val lease: ByteSourceLease,
) : RandomAccessByteSource {
    override suspend fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int =
        try {
            gate.beforeRequest()
            http.readAt(position, buffer, offset, length)
        } catch (failure: Throwable) {
            http.close()
            throw failure
        }
}

/**
 * The format facts of one admitted progressive view. The bytes stay behind the view, so the handle names no transport.
 * Account admission and the URL authorization window are independent gates on every read.
 */
private class ProgressivePlaintextHandle(
    override val mimeType: String,
    override val codec: String,
    override val lengthBytes: Long?,
    override val expiresAtEpochMs: Long?,
) : PreparedPlaintextHandle
