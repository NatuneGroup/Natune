package moe.rukamori.archivetune.desktop.playback

import java.util.concurrent.atomic.AtomicBoolean
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceAccount
import moe.rukamori.archivetune.shared.data.bytesource.ScopedCookieSource
import moe.rukamori.archivetune.shared.data.bytesource.toByteSourceAccount
import moe.rukamori.archivetune.shared.data.stream.NativePlaintext
import moe.rukamori.archivetune.shared.data.stream.NativePlaybackRoute
import moe.rukamori.archivetune.shared.data.stream.NativeRouteOutcome
import moe.rukamori.archivetune.shared.data.stream.accountProviderId
import moe.rukamori.archivetune.shared.data.stream.nativePlaintext
import moe.rukamori.archivetune.shared.domain.platform.ANONYMOUS_ACCOUNT_SCOPE
import moe.rukamori.archivetune.shared.domain.platform.AccountAdmission
import moe.rukamori.archivetune.shared.domain.platform.AcceptedAccount
import moe.rukamori.archivetune.shared.domain.platform.AcceptedAccountReader
import moe.rukamori.archivetune.shared.domain.playback.PreparedPlaintextHandle
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType

/**
 * Prepares one native occurrence through the common [NativePlaybackRoute]. The route owns the decryption and the
 * preparers, so no crypto or preparer is repeated here. This host owns only the transport and the admission that it
 * reads through.
 *
 * A personal account is admitted before the route reads its first byte. The transport then carries that same
 * admission, so its account names the admitted entry scope and the cookies it sends come from that entry alone. The
 * route re-checks the admitted owner on every open. A missing scope, a missing store or an account that is not
 * accepted refuses, and the caller falls through its source chain. A refusal never becomes a progressive URL.
 *
 * The returned handle owns the transport until the engine takes it, or until [HostPreparedPlaintext.release] closes
 * it. Native recipes and TIDAL manifests are decrypted here, and a progressive stream takes its own admitted route. A
 * Deezer stream is refused, because its ciphertext is read only through its own decrypting lease.
 */
internal suspend fun prepareHostPlaintext(
    prepared: PreparedAudio,
    accounts: AcceptedAccountReader?,
    cookies: ScopedCookieSource?,
): NativeRouteOutcome {
    val (source, scope) =
        when (prepared) {
            is PreparedAudio.Native -> prepared.recipe.source to prepared.recipe.accountScope
            is PreparedAudio.TidalManifest -> AudioSourceType.TIDAL to prepared.accountScope
            // A progressive stream has no decryptor, so it is admitted before any scope is read here.
            is PreparedAudio.Progressive -> return prepareAdmittedProgressive(prepared, accounts, cookies)
            is PreparedAudio.DeezerEncrypted ->
                return NativeRouteOutcome.Refused(
                    "Only native recipes and TIDAL manifests use the host plaintext route",
                )
        }
    // A manifest recorded without a scope may be personal, so it is refused rather than read as anonymous.
    if (scope.isNullOrBlank()) {
        return NativeRouteOutcome.Refused("The ${source.name} account scope is missing; nothing was read")
    }
    val store =
        accounts ?: return NativeRouteOutcome.Refused("No accepted-account store is bound, so nothing was read")
    val provider =
        source.accountProviderId() ?: return NativeRouteOutcome.Refused("${source.name} has no account slot")
    // A personal scope without a cookie store would send its requests with no cookies, which is a silent downgrade to
    // anonymous, so it is refused before any admission or request.
    if (scope != ANONYMOUS_ACCOUNT_SCOPE && cookies == null) {
        return NativeRouteOutcome.Refused("No cookie store is bound, so the ${source.name} account cannot be read")
    }
    // The admission is captured before the transport exists, so the cookie account and the route's re-check
    // name one entry.
    val admission: AccountAdmission? =
        if (scope == ANONYMOUS_ACCOUNT_SCOPE) {
            null
        } else {
            store.admit(provider, scope)
                ?: return NativeRouteOutcome.Refused("No accepted $provider account; sign in again to play it")
        }
    val transport =
        HostCiphertextOpener(
            boundScope = scope,
            account = admission?.toByteSourceAccount() ?: ByteSourceAccount(provider = provider, accountId = null),
            // Only an admitted personal account reads cookies. Anonymous access names no account, so it sends none.
            cookies = if (admission == null) null else cookies,
            headers = (prepared as? PreparedAudio.Native)?.stream?.headers
                ?: moe.rukamori.archivetune.shared.domain.playback.PlaybackHeaders.NONE,
            headerOrigin = (prepared as? PreparedAudio.Native)?.stream?.uri
                ?.let(moe.rukamori.archivetune.shared.data.bytesource.StreamOrigin::parse),
            urlAllowed = { url -> (accounts as? moe.rukamori.archivetune.desktop.provider.DesktopSourceAdmissions)
                ?.permitsUrl(scope, url) ?: true },
        )
    val routeAccounts: AcceptedAccountReader =
        if (admission == null) store else PinnedAdmissionReader(store, admission)
    val outcome =
        try {
            NativePlaybackRoute.prepare(prepared, transport, routeAccounts)
        } catch (failure: Throwable) {
            // A stalled transport and a cancelled caller both end here. Either way no socket may outlive the attempt.
            transport.close()
            throw failure
        }
    return when (outcome) {
        is NativeRouteOutcome.Refused -> {
            transport.close()
            outcome
        }
        is NativeRouteOutcome.Ready -> {
            val plaintext = outcome.handle.nativePlaintext()
            if (plaintext == null) {
                transport.close()
                NativeRouteOutcome.Refused("The prepared audio is not a host plaintext view")
            } else {
                NativeRouteOutcome.Ready(HostPreparedPlaintext(outcome.handle, plaintext, transport))
            }
        }
    }
}

/**
 * One prepared plaintext view with the host transport that it reads through. The engine takes both through a single
 * lease, so the transport is never shared with a second deck. The handle delegates to [view], so the MIME type, codec,
 * length and expiry are exactly the ones the shared route reports. The native load call takes no format hint, so the
 * engine probes the decrypted bytes itself, and the MIME type and codec stay facts on the handle rather than inputs.
 *
 * A handle is single-use. [claim] hands it to one lease, and [release] closes the transport of a handle that is never
 * loaded. Every path that does not reach a lease closes the transport, so a refused handle keeps no socket open.
 */
internal class HostPreparedPlaintext(
    private val view: PreparedPlaintextHandle,
    private val plaintext: NativePlaintext,
    private val transport: HostCiphertextOpener,
) : PreparedPlaintextHandle by view {
    private val taken = AtomicBoolean(false)

    /**
     * The engine lease for this view, or null when the handle was already taken or released, or has expired. An expired
     * handle is spent and its transport closes here, so the occurrence must be prepared again. A handle that was taken
     * earlier keeps its transport, because the lease that took it owns that transport.
     */
    fun claim(nowEpochMs: Long = System.currentTimeMillis()): NativeStreamLease? {
        if (!taken.compareAndSet(false, true)) return null
        val expiresAt = plaintext.expiresAtEpochMs
        if (expiresAt != null && nowEpochMs >= expiresAt) {
            transport.close()
            return null
        }
        return NativeStreamLease.forNativePlaintext(plaintext, transport)
    }

    /** Closes the transport of a handle that will not be loaded. A taken handle is left to its lease. */
    fun release() {
        if (taken.compareAndSet(false, true)) transport.close()
    }
}

/**
 * Offers the route the admission that the host captured, so the route wraps the transport with the entry whose identity
 * and scope the transport's account already names. The reads still go to the store, so each re-check on every open sees
 * the live account. A different provider or scope is refused rather than admitted, so the route can never open another
 * entry.
 */
private class PinnedAdmissionReader(
    private val store: AcceptedAccountReader,
    private val pinned: AccountAdmission,
) : AcceptedAccountReader {
    override suspend fun acceptedAccount(provider: String, accountScope: String): AcceptedAccount? =
        store.acceptedAccount(provider, accountScope)

    override suspend fun admit(provider: String, accountScope: String): AccountAdmission? =
        pinned.takeIf { it.provider == provider && it.accountScope == accountScope }
}
