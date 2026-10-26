/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.stream

import kotlin.time.Clock
import moe.rukamori.archivetune.shared.data.stream.dash.TidalDashPreparation
import moe.rukamori.archivetune.shared.domain.platform.ANONYMOUS_ACCOUNT_SCOPE
import moe.rukamori.archivetune.shared.domain.platform.AcceptedAccountReader
import moe.rukamori.archivetune.shared.domain.platform.AccountAdmission
import moe.rukamori.archivetune.shared.domain.playback.PreparedPlaintextHandle
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType

/**
 * The route's decision for one prepared occurrence: a handle that the host plays through its own lease, or the
 * reason the occurrence was refused. A refusal sends the caller down its source chain. It never means a progressive
 * URL.
 */
sealed interface NativeRouteOutcome {
    class Ready(
        val handle: PreparedPlaintextHandle,
    ) : NativeRouteOutcome

    class Refused(
        val reason: String,
    ) : NativeRouteOutcome
}

/**
 * The one common route from a prepared native occurrence to a playable plaintext handle. A personal account is
 * admitted before any byte is read, and the host's ciphertext opener is wrapped so that every open re-checks that
 * account. The existing preparers then do the decryption. Hosts supply only the opener and the account reader, so no
 * host repeats the admission and none can open a personal account anonymously.
 */
object NativePlaybackRoute {
    /**
     * Prepares [prepared] for playback. A missing account scope, a personal scope with no accepted account, or a
     * recipe the host cannot honour is a [NativeRouteOutcome.Refused]. Only the genuinely anonymous scope is opened
     * without admission, because it has no account whose generation could change.
     */
    suspend fun prepare(
        prepared: PreparedAudio,
        opener: NativeCiphertextOpener,
        accounts: AcceptedAccountReader,
        nowEpochMs: Long = Clock.System.now().toEpochMilliseconds(),
    ): NativeRouteOutcome {
        return when (prepared) {
            is PreparedAudio.Native -> {
                val recipe = prepared.recipe
                admitAndPrepare(recipe.source, recipe.accountScope, opener, accounts) { host, _ ->
                    NativeRecipePreparation.prepare(recipe, host)
                }
            }
            is PreparedAudio.TidalManifest -> {
                // A manifest with no recorded scope may be personal, so it is refused rather than read as anonymous.
                val scope = prepared.accountScope
                    ?: return NativeRouteOutcome.Refused("The TIDAL manifest has no account scope; prepare it again")
                admitAndPrepare(AudioSourceType.TIDAL, scope, opener, accounts) { host, admission ->
                    TidalDashPreparation.prepare(prepared, host, admission, nowEpochMs)
                }
            }
            is PreparedAudio.Progressive, is PreparedAudio.DeezerEncrypted ->
                NativeRouteOutcome.Refused("Only native recipes and TIDAL manifests use the host plaintext route")
        }
    }

    private suspend fun admitAndPrepare(
        source: AudioSourceType,
        accountScope: String?,
        opener: NativeCiphertextOpener,
        accounts: AcceptedAccountReader,
        preparer: suspend (host: NativeCiphertextOpener, admission: AccountAdmission?) -> NativePreparation,
    ): NativeRouteOutcome {
        if (accountScope.isNullOrBlank()) {
            return NativeRouteOutcome.Refused("The ${source.name} account scope is missing; nothing was read")
        }
        // The preparer receives the admission as well as the opener, so a lease names the same entry that the opener
        // re-checks. Anonymous access has no admission and therefore names no account.
        val admission: AccountAdmission?
        val host: NativeCiphertextOpener
        if (accountScope == ANONYMOUS_ACCOUNT_SCOPE) {
            admission = null
            host = opener
        } else {
            val provider = source.accountProviderId()
                ?: return NativeRouteOutcome.Refused("${source.name} has no account slot for a personal scope")
            // Admission is captured before the first byte is read. A missing account refuses here and never
            // falls back to anonymous access.
            val captured = accounts.admit(provider, accountScope)
                ?: return NativeRouteOutcome.Refused("No accepted $provider account; sign in again to play it")
            admission = captured
            host = AdmittedCiphertextOpener(captured, accounts, opener)
        }
        return when (val outcome = preparer(host, admission)) {
            is NativePreparation.Ready -> NativeRouteOutcome.Ready(NativePlaintextHandle(outcome.plaintext))
            is NativePreparation.Refused -> NativeRouteOutcome.Refused(outcome.reason)
        }
    }
}

/**
 * The provider id whose account slot holds a personal scope for this source, or null when the source has no account
 * slot. Hosts admit a personal stream through this id before they read its cookies, so the mapping lives in one
 * place.
 */
fun AudioSourceType.accountProviderId(): String? =
    when (this) {
        AudioSourceType.TIDAL -> ContentProvider.TIDAL.id
        AudioSourceType.QOBUZ -> ContentProvider.QOBUZ.id
        AudioSourceType.DEEZER -> ContentProvider.DEEZER.id
        AudioSourceType.APPLE -> ContentProvider.APPLE.id
        AudioSourceType.AMAZON -> ContentProvider.AMAZON.id
        AudioSourceType.QQ -> ContentProvider.QQ.id
        // The streaming account shares the catalog provider id; its SPOTIFY_STREAMING scope tells them apart.
        AudioSourceType.SPOTIFY -> ContentProvider.SPOTIFY_CATALOG.id
        else -> null
    }

/**
 * The handle that [NativePlaybackRoute] returns. It wraps the view that a preparer
 * opened and exposes no bytes itself, so a host reaches them only through [nativePlaintext].
 */
internal class NativePlaintextHandle(
    private val plaintext: NativePlaintext,
) : PreparedPlaintextHandle {
    override val mimeType: String get() = plaintext.mimeType
    override val codec: String get() = plaintext.codec
    override val lengthBytes: Long? get() = plaintext.length
    override val expiresAtEpochMs: Long? get() = plaintext.expiresAtEpochMs

    fun view(): NativePlaintext = plaintext

    override fun toString(): String = "NativePlaintextHandle(mimeType=$mimeType, codec=$codec)"
}

/**
 * The plaintext view behind a handle that [NativePlaybackRoute] prepared, or null for a handle of any other
 * kind. The host reads [NativePlaintext.source] through its own lease, configured with the handle's MIME type
 * and codec.
 */
fun PreparedPlaintextHandle.nativePlaintext(): NativePlaintext? = (this as? NativePlaintextHandle)?.view()
