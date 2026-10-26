/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.data.stream.deezer

import moe.rukamori.archivetune.shared.data.bytesource.Md5
import moe.rukamori.archivetune.shared.data.bytesource.RandomAccessByteSource
import moe.rukamori.archivetune.shared.data.bytesource.readUpTo
import moe.rukamori.archivetune.shared.data.stream.blowfish.Blowfish

/**
 * Deezer CDN stream decryption. Every third 2048-byte chunk is Blowfish-CBC encrypted with a
 * per-track key, and each encrypted chunk restarts CBC from the same IV so chunks are seekable.
 * Port of the shipped Android `DeezerCrypto`; output must stay bit-identical.
 */
object DeezerCrypto {
    /** Deezer's CDN chunk size. Encryption applies per whole chunk, so this is also the seek grain. */
    const val CHUNK_SIZE = 2048

    /** Only every third chunk is encrypted; the other two are stored as plaintext. */
    const val ENCRYPTED_CHUNK_STRIDE = 3

    /** Fixed key-derivation salt shared by every track; it unlocks nothing without a playable URL. */
    const val DEFAULT_KEY_SALT = "g4el58wc0zvf9na1"

    private const val KEY_BYTES = 16
    private const val BLOCK_BYTES = 8

    /** Deezer restarts every encrypted chunk from this same IV. */
    private val CHUNK_IV = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)

    /**
     * Derives the 16-byte Blowfish key for [trackId]. The MD5 is consumed as lowercase hex text,
     * not raw digest bytes; the raw-byte variant decrypts without error into noise.
     */
    fun deriveKey(
        trackId: String,
        salt: String = DEFAULT_KEY_SALT,
    ): ByteArray {
        val effective = if (salt.length >= KEY_BYTES) salt else DEFAULT_KEY_SALT
        val md5Hex = Md5.hexDigest(trackId.encodeToByteArray())
        return ByteArray(KEY_BYTES) { i ->
            (md5Hex[i].code xor md5Hex[i + 16].code xor effective[i].code).toByte()
        }
    }

    /** True when the chunk at absolute [chunkIndex] is encrypted rather than stored plaintext. */
    fun isEncryptedChunk(chunkIndex: Long): Boolean = chunkIndex % ENCRYPTED_CHUNK_STRIDE == 0L

    /**
     * Decrypts the first [length] bytes of [chunk] in place. Only whole 8-byte blocks are decrypted;
     * a trailing remainder is stored as-is and must not reach the cipher.
     */
    fun decryptChunk(
        chunk: ByteArray,
        length: Int,
        key: ByteArray,
    ) {
        if (length < BLOCK_BYTES) return
        decryptChunkWith(Blowfish(key), chunk, length)
    }

    internal fun decryptChunkWith(
        blowfish: Blowfish,
        chunk: ByteArray,
        length: Int,
    ) {
        if (length < BLOCK_BYTES) return
        val decryptable = length - (length % BLOCK_BYTES)
        val block = ByteArray(BLOCK_BYTES)
        val plain = ByteArray(BLOCK_BYTES)
        var chain = CHUNK_IV
        for (offset in 0 until decryptable step BLOCK_BYTES) {
            chunk.copyInto(block, 0, offset, offset + BLOCK_BYTES)
            blowfish.decryptBlock(block, 0, plain, 0)
            for (i in 0 until BLOCK_BYTES) {
                chunk[offset + i] = (plain[i].toInt() xor chain[i].toInt()).toByte()
            }
            chain = block.copyOf()
        }
    }
}

/**
 * Presents a Deezer CDN stream as plaintext. Encrypted chunks are decrypted into a private buffer
 * so the upstream arrays are never mutated; a one-chunk cache serves sequential small reads.
 */
class DeezerChunkByteSource(
    private val ciphertext: RandomAccessByteSource,
    key: ByteArray,
) : RandomAccessByteSource {
    override val lease = ciphertext.lease

    private val blowfish = Blowfish(key)

    private var cachedIndex: Long = -1
    private var cachedChunk: ByteArray = ByteArray(0)
    private var cachedChunkIsFinal = false

    override suspend fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        val chunkIndex = position / DeezerCrypto.CHUNK_SIZE
        val chunk = loadChunk(chunkIndex)
        val inChunk = (position - chunkIndex * DeezerCrypto.CHUNK_SIZE).toInt()
        if (inChunk >= chunk.bytes.size) {
            return if (chunk.endOfStream) RandomAccessByteSource.END_OF_STREAM else 0
        }
        val count = minOf(length, chunk.bytes.size - inChunk)
        chunk.bytes.copyInto(buffer, offset, inChunk, inChunk + count)
        return count
    }

    private class LoadedChunk(
        val bytes: ByteArray,
        val endOfStream: Boolean,
    )

    private suspend fun loadChunk(chunkIndex: Long): LoadedChunk {
        if (chunkIndex == cachedIndex) return LoadedChunk(cachedChunk, cachedChunkIsFinal)
        val encrypted = DeezerCrypto.isEncryptedChunk(chunkIndex)
        val read = ciphertext.readUpTo(chunkIndex * DeezerCrypto.CHUNK_SIZE, DeezerCrypto.CHUNK_SIZE)
        val bytes = read.bytes
        if (bytes.size == DeezerCrypto.CHUNK_SIZE) {
            // A whole chunk never changes again, so it is decrypted once and cached.
            if (encrypted) DeezerCrypto.decryptChunkWith(blowfish, bytes, DeezerCrypto.CHUNK_SIZE)
            cachedIndex = chunkIndex
            cachedChunk = bytes
            cachedChunkIsFinal = false
            return LoadedChunk(bytes, endOfStream = false)
        }
        if (read.endOfStream) {
            // The short tail of a track is stored as plaintext, so it is served as-is and cached as final.
            cachedIndex = chunkIndex
            cachedChunk = bytes
            cachedChunkIsFinal = true
            return LoadedChunk(bytes, endOfStream = true)
        }
        // Neither whole nor at end of stream, so more bytes may still arrive and a zero-byte upstream read
        // is never EOF. Partial plaintext can be served as far as it has arrived. An encrypted chunk cannot be
        // decrypted until it is whole, so its partial ciphertext is withheld and the caller retries.
        return LoadedChunk(if (encrypted) ByteArray(0) else bytes, endOfStream = false)
    }
}
