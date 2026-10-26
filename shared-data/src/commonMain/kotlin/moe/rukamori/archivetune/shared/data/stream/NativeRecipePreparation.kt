package moe.rukamori.archivetune.shared.data.stream

import moe.rukamori.archivetune.shared.data.bytesource.RandomAccessByteSource
import moe.rukamori.archivetune.shared.data.stream.cenc.CencMp4
import moe.rukamori.archivetune.shared.data.stream.qmc.QmcDecryptor
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
    ): NativePreparation =
        when (recipe) {
            is NativeAudioRecipe.AmazonCenc -> prepareAmazon(recipe, opener)
            is NativeAudioRecipe.QqEncrypted -> prepareQq(recipe, opener)
            // Apple's HLS keys are released to a platform content-decryption module. That module is not portable and
            // is a pending owner decision, so this refuses instead of pretending to decrypt.
            is NativeAudioRecipe.AppleHls ->
                NativePreparation.Refused("Apple HLS needs a platform content-decryption module")
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
