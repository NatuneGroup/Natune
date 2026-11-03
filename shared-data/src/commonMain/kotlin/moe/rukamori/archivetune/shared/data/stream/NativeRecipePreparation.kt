package moe.rukamori.archivetune.shared.data.stream

import moe.rukamori.archivetune.shared.data.bytesource.RandomAccessByteSource
import moe.rukamori.archivetune.shared.data.bytesource.readWholeBounded
import moe.rukamori.archivetune.shared.data.stream.cenc.CencMp4
import moe.rukamori.archivetune.shared.data.stream.hls.HlsSegmentedPreparation
import moe.rukamori.archivetune.shared.data.stream.qmc.QmcDecryptor
import moe.rukamori.archivetune.shared.data.stream.spotify.SpotifyPlayPlaySource
import moe.rukamori.archivetune.shared.domain.platform.AccountAdmission
import moe.rukamori.archivetune.shared.domain.repository.NativeAudioRecipe

/**
 * Opens the lease-bound ciphertext behind a native recipe. The platform supplies the transport, a desktop or iOS range
 * reader, and M7 decides what the bytes mean. Implementations return null when the host cannot open the resource. They
 * must re-check the account generation at open time and never substitute a progressive URL.
 */
interface NativeCiphertextOpener {
    suspend fun open(
        url: String,
        accountScope: String,
    ): RandomAccessByteSource?

    /**
     * Reads a small resource whole — a manifest document or a segment key — under the same scope and lease policy as
     * [open]. The default opens it through [open] and reads sequentially, so a host whose ranged open requires
     * features a small resource may lack must override it. Returns null when the host cannot open the resource or the
     * resource is larger than [maxBytes]. A transport failure throws rather than returning null.
     */
    suspend fun openWhole(
        url: String,
        accountScope: String,
        maxBytes: Int,
    ): ByteArray? = open(url, accountScope)?.readWholeBounded(maxBytes)
}

/**
 * A range-readable plaintext view and the facts the engine needs to configure its decoder. [length] is null when the
 * lease does not state the ciphertext length. The CENC view then still ends where the ciphertext ends, and the engine
 * must treat the stream as unknown-length rather than assume zero.
 */
class NativePlaintext(
    val source: RandomAccessByteSource,
    val mimeType: String,
    val codec: String,
    val length: Long?,
    /**
     * When the authorization behind the view stops, or null when its lease states none. A read after that instant
     * fails, so the host prepares the occurrence again before it.
     */
    val expiresAtEpochMs: Long? = null,
)

sealed interface NativePreparation {
    class Ready(
        val plaintext: NativePlaintext,
    ) : NativePreparation

    /**
     * The recipe cannot be honoured on this host. The caller must fall through its source chain. It must never fall back
     * to a progressive URL, because that would play the protected bytes as if they were plain audio.
     */
    class Refused(
        val reason: String,
    ) : NativePreparation
}

/**
 * Turns a provider's native recipe into a plaintext view. Amazon uses the fragmented-MP4 CENC view and QQ Music uses the
 * QMC container view. Both keep the ciphertext behind its lease, and neither writes a plaintext temp file.
 */
object NativeRecipePreparation {
    suspend fun prepare(
        recipe: NativeAudioRecipe,
        opener: NativeCiphertextOpener,
        admission: AccountAdmission? = null,
    ): NativePreparation =
        when (recipe) {
            is NativeAudioRecipe.AmazonCenc -> prepareAmazon(recipe, opener)
            is NativeAudioRecipe.QqEncrypted -> prepareQq(recipe, opener)
            is NativeAudioRecipe.SpotifyPlayPlay -> prepareSpotify(recipe, opener)
            // A non-DRM HLS media playlist is served by the segmented view: clear and AES-128 identity-keyed
            // segments play, while SAMPLE-AES and non-identity key formats (Apple FairPlay, PlayReady,
            // Widevine-class) stay refused — they need a platform content-decryption module this host lacks.
            is NativeAudioRecipe.AppleHls -> HlsSegmentedPreparation.prepareApple(recipe, opener, admission)
            is NativeAudioRecipe.SegmentedHls -> HlsSegmentedPreparation.prepare(recipe, opener, admission)
        }

    private suspend fun prepareAmazon(
        recipe: NativeAudioRecipe.AmazonCenc,
        opener: NativeCiphertextOpener,
    ): NativePreparation {
        val ciphertext =
            opener.open(recipe.url, recipe.accountScope)
                ?: return NativePreparation.Refused("Amazon ciphertext is unavailable")
        // The key stays inside this call: it is revealed only to the CENC parser, never logged or echoed.
        val plaintext =
            CencMp4.openPlaintext(ciphertext, recipe.key.reveal())
                ?: return NativePreparation.Refused("Amazon CENC structure or key was rejected")
        return NativePreparation.Ready(
            NativePlaintext(
                source = plaintext.source,
                mimeType = AMAZON_PLAINTEXT_MIME,
                codec = plaintext.codec,
                length = ciphertext.lease.lengthBytes,
                expiresAtEpochMs = ciphertext.lease.expiresAtEpochMs,
            ),
        )
    }

    private suspend fun prepareSpotify(
        recipe: NativeAudioRecipe.SpotifyPlayPlay,
        opener: NativeCiphertextOpener,
    ): NativePreparation {
        val ciphertext = opener.open(recipe.url, recipe.accountScope)
            ?: return NativePreparation.Refused("SPOTIFY_CIPHERTEXT_UNAVAILABLE")
        val plaintext = SpotifyPlayPlaySource.open(ciphertext, recipe.key.reveal(), recipe.iv.reveal(), recipe.flac)
            ?: return NativePreparation.Refused("SPOTIFY_PLAYPLAY_REJECTED")
        return NativePreparation.Ready(plaintext)
    }

    private suspend fun prepareQq(
        recipe: NativeAudioRecipe.QqEncrypted,
        opener: NativeCiphertextOpener,
    ): NativePreparation {
        val ciphertext =
            opener.open(recipe.url, recipe.accountScope)
                ?: return NativePreparation.Refused("QQ Music ciphertext is unavailable")
        // A null key is the footer-less case. The decryptor then refuses any container whose footer needs a key it lacks.
        val plaintext =
            QmcDecryptor.openPlaintext(ciphertext, recipe.eKey?.reveal())
                ?: return NativePreparation.Refused("QQ Music container was rejected")
        return NativePreparation.Ready(
            NativePlaintext(
                source = plaintext.source,
                mimeType = plaintext.container.mimeType,
                codec = plaintext.codec,
                length = plaintext.length,
                expiresAtEpochMs = ciphertext.lease.expiresAtEpochMs,
            ),
        )
    }

    /** CENC keeps the fragmented MP4 wrapper, so the view is still MP4 with its sample entry restored. */
    private const val AMAZON_PLAINTEXT_MIME = "audio/mp4"
}
