package moe.rukamori.archivetune.shared.data.stream.qmc

import kotlinx.coroutines.delay
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceException
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceLease
import moe.rukamori.archivetune.shared.data.bytesource.RandomAccessByteSource

/**
 * QQ Music protected containers (QMC1 and QMC2) as a random-access plaintext view. Port of the Android QmcDecryptor,
 * reshaped from a whole-file decrypt into a view: the ciphertext stays behind its lease and each read XORs only the bytes
 * it returns, so a lossless track is never staged as a plaintext file. Every keystream here depends only on absolute
 * byte position, which is what makes ranged reads exact.
 */
object QmcDecryptor {
    private const val MIN_CONTAINER_BYTES = 16
    private const val SNIFF_WINDOW_BYTES = 256
    private const val LONG_KEY_BYTES = 300

    /**
     * Opens [ciphertext] as the plain container it wraps. The footer decides where the audio ends and whether the key is
     * in the file at all. When it is not, [disclosedKey] (the key the service handed out with the resource) is what makes
     * the track playable. Without it the container is refused rather than run through a cipher that cannot be right.
     *
     * Returns null when the stream is too small or of unknown length, when its footer is unrecognised or carries no
     * usable key, or when no key turns the head into a container signature. A partial or unverified payload is never
     * returned. I/O failures and cancellation propagate unchanged.
     */
    suspend fun openPlaintext(
        ciphertext: RandomAccessByteSource,
        disclosedKey: String? = null,
    ): QmcPlaintext? {
        val total = ciphertext.lease.lengthBytes ?: return null
        if (total < MIN_CONTAINER_BYTES || total > Int.MAX_VALUE) return null
        val footer = QmcEkey.findFooter(ciphertext, total) ?: return null
        val disclosed = disclosedKey?.trim()?.takeIf { it.isNotEmpty() }
        // A footer key is tried first, then the disclosed one. The second matters when the tail of a footer-less stream
        // happens to read as a length word: that "key" is audio bytes, and the disclosed one is the real key.
        val keyTexts = listOfNotNull(footer.keyText, disclosed).distinct()
        val keyed = keyTexts.isNotEmpty()

        val plainLength: Long
        if (keyed) {
            if (footer.audioLength < MIN_CONTAINER_BYTES || footer.audioLength > total) return null
            plainLength = footer.audioLength
        } else {
            if (footer.kind != QmcEkey.FooterKind.NONE) return null
            plainLength = total
        }

        val prefixLength = minOf(plainLength, SNIFF_WINDOW_BYTES.toLong()).toInt()
        val prefix = readExactly(ciphertext, 0L, prefixLength) ?: return null
        val candidate: Candidate? =
            if (keyed) {
                keyTexts.firstNotNullOfOrNull { keyText ->
                    val key = QmcEkey.derive(keyText) ?: return@firstNotNullOfOrNull null
                    candidateFor(cipherFor(key), prefix)
                }
            } else {
                candidateFor(QmcStaticCipher, prefix)
            }
        val chosen = candidate ?: return null
        return QmcPlaintext(
            source = QmcPlaintextSource(ciphertext, chosen.cipher, plainLength),
            container = chosen.container,
            codec = qmcCodecFor(chosen.container, chosen.plainPrefix),
            length = plainLength,
        )
    }

    private class Candidate(
        val cipher: QmcStreamCipher,
        val plainPrefix: ByteArray,
        val container: QmcContainer,
    )

    /**
     * Decrypts a copy of [prefix] with [cipher] and keeps the pairing only when the result starts with a container
     * signature. A wrong key costs a few bytes here, never a pass over the whole file.
     */
    private fun candidateFor(
        cipher: QmcStreamCipher,
        prefix: ByteArray,
    ): Candidate? {
        val plain = prefix.copyOf()
        cipher.decrypt(0, plain, 0, plain.size)
        val container = QmcContainer.sniff(plain) ?: return null
        return Candidate(cipher, plain, container)
    }

    private fun cipherFor(key: ByteArray): QmcStreamCipher =
        if (key.size > LONG_KEY_BYTES) QmcRc4Cipher(key) else QmcMapCipher(key)
}

/**
 * A decrypted QQ Music container as a range-readable view, with the container and codec a decoder needs. [length] is the
 * plaintext length, which for a footer-carrying file is shorter than the ciphertext.
 */
class QmcPlaintext internal constructor(
    val source: RandomAccessByteSource,
    val container: QmcContainer,
    val codec: String,
    val length: Long,
)

/**
 * The plaintext view itself. Reads go to the ciphertext behind its lease, and only the returned bytes are transformed,
 * so no read ever depends on bytes outside the request.
 */
internal class QmcPlaintextSource(
    private val ciphertext: RandomAccessByteSource,
    private val cipher: QmcStreamCipher,
    private val plainLength: Long,
) : RandomAccessByteSource {
    /** Same resource and account, but the length the view actually exposes. */
    override val lease: ByteSourceLease = ciphertext.lease.copy(lengthBytes = plainLength)

    override suspend fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (position < 0L) throw ByteSourceException("QQ Music read before the start of the container")
        if (position >= plainLength) return RandomAccessByteSource.END_OF_STREAM
        if (length == 0) return 0
        val wanted = minOf(length.toLong(), plainLength - position).toInt()
        val read = ciphertext.readAt(position, buffer, offset, wanted)
        // END_OF_STREAM and the zero-byte "not yet" answer both pass through unchanged.
        if (read <= 0) return read
        cipher.decrypt(position.toInt(), buffer, offset, read)
        return read
    }
}

/**
 * Reads exactly [length] bytes at [position], or null when the stream ends first. A zero-byte read means "not yet", so
 * it is polled for a bounded time; a stream that stays silent past that is a failure, never a clean end.
 */
internal suspend fun readExactly(
    source: RandomAccessByteSource,
    position: Long,
    length: Int,
): ByteArray? {
    val out = ByteArray(length)
    var got = 0
    var idlePolls = 0
    while (got < length) {
        val read = source.readAt(position + got, out, got, length - got)
        if (read == RandomAccessByteSource.END_OF_STREAM) return null
        if (read > 0) {
            got += read
            idlePolls = 0
            continue
        }
        idlePolls++
        if (idlePolls > MAX_IDLE_POLLS) throw ByteSourceException("QQ Music source stalled at byte $position")
        delay(IDLE_POLL_MILLIS)
    }
    return out
}

private const val MAX_IDLE_POLLS = 400
private const val IDLE_POLL_MILLIS = 25L
