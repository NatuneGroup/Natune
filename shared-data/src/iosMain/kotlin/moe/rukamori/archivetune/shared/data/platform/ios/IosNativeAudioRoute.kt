package moe.rukamori.archivetune.shared.data.platform.ios

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceAccount
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceException
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceLease
import moe.rukamori.archivetune.shared.data.bytesource.ScopedCookie
import moe.rukamori.archivetune.shared.data.bytesource.ScopedCookieSource
import moe.rukamori.archivetune.shared.data.bytesource.StreamOrigin
import moe.rukamori.archivetune.shared.data.bytesource.cookiesForLease
import moe.rukamori.archivetune.shared.data.bytesource.toByteSourceAccount
import moe.rukamori.archivetune.shared.data.stream.NativePlaintext
import moe.rukamori.archivetune.shared.data.stream.NativePlaintextHandle
import moe.rukamori.archivetune.shared.data.stream.NativePlaybackRoute
import moe.rukamori.archivetune.shared.data.stream.NativeRouteOutcome
import moe.rukamori.archivetune.shared.data.stream.accountProviderId
import moe.rukamori.archivetune.shared.data.stream.hls.HlsPlaylistException
import moe.rukamori.archivetune.shared.data.stream.hls.HlsSegmentException
import moe.rukamori.archivetune.shared.data.stream.hls.HlsSegmentedSource
import moe.rukamori.archivetune.shared.data.stream.hls.isHlsPlaylistUrl
import moe.rukamori.archivetune.shared.domain.platform.ANONYMOUS_ACCOUNT_SCOPE
import moe.rukamori.archivetune.shared.domain.platform.AccountAdmission
import moe.rukamori.archivetune.shared.domain.platform.AcceptedAccount
import moe.rukamori.archivetune.shared.domain.platform.AcceptedAccountReader
import moe.rukamori.archivetune.shared.domain.playback.PreparedPlaintextHandle
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.domain.source.DirectStream
import moe.rukamori.archivetune.shared.domain.source.toPlaybackLocator

/** The one message a failed preparation reports. Transport detail stays out of it, because that detail may name a URL. */
private const val PREPARATION_FAILED = "native preparation failed"

/** The message an admission reports when its account cannot be read. Storage detail stays out of it. */
private const val ACCOUNT_UNREADABLE = "the account could not be read, so nothing was read"

/** The message a request reports when its account is no longer the entry it was admitted under. Nothing is sent. */
private const val ACCOUNT_NO_LONGER_ADMITTED =
    "the account is no longer admitted for this stream, so nothing was read"

/** The lease account of an anonymous segmented stream, matching the anonymous progressive lease. */
private const val PLAYBACK_PROVIDER = "playback"

/** The MIME a segmented view reports before its first payload bytes are classified. */
private const val SEGMENTED_UNKNOWN_MIME = "application/octet-stream"

/**
 * The iOS side of the common native route. [NativePlaybackRoute] makes every decision about scope, admission and
 * preparation, so none of that is repeated here. This class supplies only what iOS must: the transport that reads the
 * ciphertext, bound to one admitted account, and the cookie source that the account's transport uses.
 *
 * A personal scope is admitted here, before any byte is requested, and the transport is bound to exactly that entry.
 * The route is then given the same admission, so its own re-check and this transport name one entry and can never
 * disagree. Every request of the transport, the probe included, rereads that entry and its cookies before it leaves, so a
 * revoked, replaced or unreadable account refuses the request. An anonymous scope gets no admission and no cookie source.
 * A missing scope, an unaccepted account or an unreadable store refuses without reading a byte, and no personal scope is
 * ever read as anonymous.
 *
 * [prepare] makes blocking ciphertext requests, so it runs on the default dispatcher. A refusal or a failure closes the
 * transport it created. A ready outcome hands the transport to the caller, who owns it until the view stops.
 *
 * A personal progressive stream is admitted the same way before its first byte, and its lease is bound to that entry.
 * Every request of the lease, the first one included, asks the store again and is refused unless the same entry is
 * still accepted.
 */
internal class IosNativeAudioRoute(
    private val accounts: AcceptedAccountReader,
    private val cookies: ScopedCookieSource,
) {
    /**
     * The result of [prepare]. Only [Ready] and [Streamed] carry audio, and no outcome falls back to a progressive URL.
     */
    sealed interface Outcome {
        /** The handle plays through [transport], which the caller owns and must close when playback stops. */
        class Ready(val handle: PreparedPlaintextHandle, val transport: IosCiphertextOpener) : Outcome

        /**
         * A personal progressive stream, admitted before any byte was requested. The engine reads it through the lease in
         * [remote]. That lease holds no connection until the engine opens it, so dropping it needs no release.
         */
        class Streamed(val remote: IosAdmittedRemote) : Outcome

        /** The occurrence cannot be honoured on this host. Its transport is already closed. */
        class Refused(val reason: String) : Outcome

        /** Preparation failed unexpectedly. Its transport is already closed. */
        class Failed(val reason: String) : Outcome
    }

    suspend fun prepare(prepared: PreparedAudio): Outcome = withContext(Dispatchers.Default) {
        val source: AudioSourceType
        val scope: String?
        when (prepared) {
            is PreparedAudio.Native -> {
                source = prepared.recipe.source
                scope = prepared.recipe.accountScope
            }
            is PreparedAudio.TidalManifest -> {
                source = AudioSourceType.TIDAL
                scope = prepared.accountScope
            }
            is PreparedAudio.Progressive -> return@withContext admitProgressive(prepared.stream)
            is PreparedAudio.DeezerEncrypted -> return@withContext Outcome.Refused("The stream is not native-prepared")
        }
        if (scope.isNullOrBlank()) {
            return@withContext Outcome.Refused("The ${source.name} account scope is missing; nothing was read")
        }
        val provider = source.accountProviderId()
            ?: return@withContext Outcome.Refused("${source.name} has no account slot for a personal scope")
        // Admission is captured before the first byte is requested. Anonymous access names no account, so it has no
        // admission and no cookie source.
        val admission: AccountAdmission? =
            if (scope == ANONYMOUS_ACCOUNT_SCOPE) {
                null
            } else {
                val admitted =
                    try {
                        accounts.admit(provider, scope)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        return@withContext Outcome.Refused(ACCOUNT_UNREADABLE)
                    }
                admitted
                    ?: return@withContext Outcome.Refused("No accepted $provider account; sign in again to play it")
            }
        // The recheck reads the same store and cookie source that the route uses, bound to the admission captured above,
        // so the transport and the route can never name different entries. Anonymous access gets no recheck.
        val transport = IosCiphertextOpener(
            boundScope = scope,
            account = admission?.toByteSourceAccount() ?: ByteSourceAccount(provider = provider, accountId = null),
            recheckFor = admission?.let { captured ->
                { lease: ByteSourceLease -> PersonalCookieRecheck(accounts, cookies, captured, lease) }
            },
        )
        val routeAccounts: AcceptedAccountReader =
            if (admission == null) accounts else CapturedAdmission(accounts, admission)
        val outcome =
            try {
                NativePlaybackRoute.prepare(prepared, transport, routeAccounts)
            } catch (cancelled: CancellationException) {
                transport.close()
                throw cancelled
            } catch (_: Throwable) {
                transport.close()
                return@withContext Outcome.Failed(PREPARATION_FAILED)
            }
        when (outcome) {
            is NativeRouteOutcome.Ready -> Outcome.Ready(outcome.handle, transport)
            is NativeRouteOutcome.Refused -> {
                transport.close()
                Outcome.Refused(outcome.reason)
            }
        }
    }

    /**
     * Admits a personal progressive stream against its account before any byte is requested, and binds its lease to
     * exactly that entry. No cookie is read here: the recheck reads them immediately before each request, the first one
     * included, so a cookie set taken now can never be the one that a request sends. A stream that names no personal
     * scope is refused rather than read as anonymous, because a personal stream read without its account would be a
     * silent downgrade. Reads run on the default dispatcher; the engine opens the lease later on its producer thread.
     */
    private suspend fun admitProgressive(stream: DirectStream): Outcome {
        if (isHlsPlaylistUrl(stream.uri)) return admitSegmented(stream)
        val scope = stream.accountScope
        if (scope == null || scope == ANONYMOUS_ACCOUNT_SCOPE) {
            return Outcome.Refused("The ${stream.source.name} stream names no personal account; nothing was read")
        }
        val provider = stream.source.accountProviderId()
            ?: return Outcome.Refused("${stream.source.name} has no account slot for a personal scope")
        val origin = StreamOrigin.parse(stream.uri)
            ?: return Outcome.Refused("The ${stream.source.name} stream address is not an HTTP(S) URL; nothing was read")
        // The admission is captured before the first byte is requested, and the lease is bound to exactly that entry.
        val admitted: AccountAdmission? =
            try {
                accounts.admit(provider, scope)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return Outcome.Refused(ACCOUNT_UNREADABLE)
            }
        val admission = admitted
            ?: return Outcome.Refused("No accepted $provider account; sign in again to play it")
        val lease = ByteSourceLease(
            account = admission.toByteSourceAccount(),
            primaryOrigin = origin,
            permittedOrigins = setOf(origin),
            // The query string carries a per-request signature, so it never becomes part of the cache key.
            cacheKey = stream.uri.substringBefore('?'),
            expiresAtEpochMs = stream.expiresAtEpochMs,
            lengthBytes = null,
        )
        val recheck = PersonalCookieRecheck(accounts, cookies, admission, lease)
        return Outcome.Streamed(IosAdmittedRemote(stream.toPlaybackLocator(), lease, recheck))
    }

    /**
     * Admits a non-DRM HLS progressive stream — a URL that serves a media playlist — and prepares it into a segmented
     * plaintext view. The engine cannot expand a playlist through its lease, because the lease's protocol whitelist is
     * empty, so the host expands it instead.
     *
     * A personal scope is admitted before any byte is requested, exactly like a personal progressive stream, and the
     * transport binds every request — the playlist document, each segment and each key — to that entry through the
     * same recheck. The playlist origin is the credential anchor: the stream's headers and its cookies never reach a
     * segment or key host that lives elsewhere. An anonymous scope is admitted as anonymous — it carries the stream's
     * headers on the playlist origin only, and no cookie is read.
     */
    private suspend fun admitSegmented(stream: DirectStream): Outcome {
        val scope = stream.accountScope ?: ANONYMOUS_ACCOUNT_SCOPE
        val provider = stream.source.accountProviderId()
        val origin =
            StreamOrigin.parse(stream.uri)
                ?: return Outcome.Refused("The ${stream.source.name} playlist is not an HTTP(S) URL; nothing was read")
        val admission: AccountAdmission?
        if (scope == ANONYMOUS_ACCOUNT_SCOPE) {
            admission = null
        } else {
            if (provider == null) {
                return Outcome.Refused("${stream.source.name} has no account slot for a personal scope")
            }
            admission =
                try {
                    accounts.admit(provider, scope)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    return Outcome.Refused(ACCOUNT_UNREADABLE)
                } ?: return Outcome.Refused("No accepted $provider account; sign in again to play it")
        }
        val account =
            admission?.toByteSourceAccount()
                ?: ByteSourceAccount(provider = provider ?: PLAYBACK_PROVIDER, accountId = null)
        val transport =
            IosCiphertextOpener(
                boundScope = scope,
                account = account,
                recheckFor =
                    admission?.let { captured ->
                        { lease: ByteSourceLease -> PersonalCookieRecheck(accounts, cookies, captured, lease) }
                    },
                headers = stream.headers,
                credentialAnchor = origin,
            )
        val view =
            HlsSegmentedSource(
                lease =
                    ByteSourceLease(
                        account = account,
                        primaryOrigin = origin,
                        permittedOrigins = setOf(origin),
                        // The query string carries a per-request signature, so it never becomes part of the cache key.
                        cacheKey = stream.uri.substringBefore('?'),
                        expiresAtEpochMs = stream.expiresAtEpochMs,
                        lengthBytes = null,
                    ),
                playlistUrl = stream.uri,
                playlistText = null,
                accountScope = scope,
                opener = transport,
            )
        try {
            view.indexOpeningSegments()
        } catch (refusal: HlsPlaylistException) {
            transport.close()
            return Outcome.Refused(refusal.message ?: "the HLS playlist was rejected")
        } catch (refusal: HlsSegmentException) {
            transport.close()
            return Outcome.Refused(refusal.message ?: "the HLS stream was rejected")
        } catch (cancelled: CancellationException) {
            transport.close()
            throw cancelled
        } catch (_: Throwable) {
            transport.close()
            return Outcome.Failed(PREPARATION_FAILED)
        }
        return Outcome.Ready(
            NativePlaintextHandle(
                NativePlaintext(
                    source = view,
                    mimeType = view.sniffedMimeType ?: SEGMENTED_UNKNOWN_MIME,
                    codec = "",
                    // The total is unknown until every segment is indexed, so the engine must not assume a length.
                    length = null,
                    expiresAtEpochMs = stream.expiresAtEpochMs,
                ),
            ),
            transport,
        )
    }
}

/**
 * Hands the route the admission that the transport is already bound to, so the route's per-open re-check and the
 * transport name the same entry. Every other read goes to the store unchanged.
 */
private class CapturedAdmission(
    private val store: AcceptedAccountReader,
    private val captured: AccountAdmission,
) : AcceptedAccountReader {
    override suspend fun acceptedAccount(
        provider: String,
        accountScope: String,
    ): AcceptedAccount? = store.acceptedAccount(provider, accountScope)

    override suspend fun admit(
        provider: String,
        accountScope: String,
    ): AccountAdmission? = captured.takeIf { provider == it.provider && accountScope == it.accountScope }
}

/**
 * Reads the store again before every request of a personal lease, progressive or ciphertext, the first one included.
 * The store must still accept the entry that was admitted: a signed-out, replaced, re-generated or re-scoped account
 * refuses the read, so the request is never sent without its credentials. An unreadable store refuses the same way, and
 * its failure carries no storage detail. The read blocks the thread that issues the request: the engine's producer thread
 * for progressive audio and for ciphertext reopened during playback, and a worker for the first ciphertext probe. The
 * store must not dispatch to the main thread, so the wait cannot stall the command loop.
 */
private class PersonalCookieRecheck(
    private val accounts: AcceptedAccountReader,
    private val cookies: ScopedCookieSource,
    private val admitted: AccountAdmission,
    private val lease: ByteSourceLease,
) : LeaseCookieRecheck {
    override fun cookiesFor(origin: StreamOrigin): Collection<ScopedCookie> = runBlocking {
        val current =
            try {
                accounts.admit(admitted.provider, admitted.accountScope)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                throw ByteSourceException(ACCOUNT_UNREADABLE)
            }
        if (current != admitted) throw ByteSourceException(ACCOUNT_NO_LONGER_ADMITTED)
        try {
            cookies.cookiesForLease(lease, origin)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            throw ByteSourceException(ACCOUNT_UNREADABLE)
        }
    }
}
