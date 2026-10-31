package moe.rukamori.archivetune.shared.data.stream

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.shared.data.bytesource.*
import moe.rukamori.archivetune.shared.data.stream.spotify.SpotifyPlayPlaySource
import moe.rukamori.archivetune.shared.domain.repository.AudioSecret
import moe.rukamori.archivetune.shared.domain.repository.NativeAudioRecipe

class SpotifyPlayPlaySourceTest {
    private val key = ByteArray(16) { (it * 3 + 1).toByte() }
    private val iv = ByteArray(16) { 255.toByte() }.apply { this[15] = 254.toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    private val origin = StreamOrigin("https", "cdn.invalid", 443)

    private fun source(plaintext: ByteArray, skip: Int, knownLength: Boolean = true): RandomAccessByteSource {
        val raw = ByteArray(skip) { (it * 5).toByte() } + plaintext
        // Not apply(): there `iv` would resolve to Cipher.getIV(), which is still null before init.
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(raw)
        val lease = ByteSourceLease(ByteSourceAccount("spotify", "stream-user", "streaming-scope"), origin,
            setOf(origin), "track-bound", 10_000L, encrypted.size.toLong().takeIf { knownLength })
        return ByteArrayByteSource(lease, encrypted)
    }

    @Test fun vorbisHeaderAndCounterCarryMatchIndependentJceEncryptionAtUnalignedSeeks() = runTest {
        val bytes = "OggS".encodeToByteArray() + ByteArray(2048) { (it * 17).toByte() }
        val encrypted = source(bytes, 167)
        val view = assertNotNull(SpotifyPlayPlaySource.open(encrypted, key.hex(), iv.hex(), false))
        assertEquals(bytes.size.toLong(), view.length)
        assertEquals(encrypted.lease.account, view.source.lease.account)
        assertEquals(encrypted.lease.permittedOrigins, view.source.lease.permittedOrigins)
        val offsets = listOf(0, 1, 14, 15, 16, 17, 127, 255, 1023, 2020)
        offsets.map { at -> async(Dispatchers.Default) {
            val buffer = ByteArray(53) { 99 }
            val count = view.source.readAt(at.toLong(), buffer, 5, 41)
            assertEquals(minOf(41, bytes.size - at), count)
            assertContentEquals(bytes.copyOfRange(at, at + count), buffer.copyOfRange(5, 5 + count))
            assertEquals(99.toByte(), buffer[0])
        } }.awaitAll()
        assertEquals(RandomAccessByteSource.END_OF_STREAM, view.source.readAt(bytes.size.toLong(), ByteArray(1), 0, 1))
        assertEquals(0, view.source.readAt(0, ByteArray(0), 0, 0))
        assertFailsWith<IllegalArgumentException> { view.source.readAt(-1, ByteArray(1), 0, 1) }
        assertFailsWith<IllegalArgumentException> { view.source.readAt(Long.MAX_VALUE, ByteArray(1), 0, 1) }
    }

    @Test fun flacHasNoHeaderAndPartialReadsKeepUnknownLengthAndExpiry() = runTest {
        val bytes = "fLaC".encodeToByteArray() + ByteArray(80) { it.toByte() }
        val encrypted = source(bytes, 0, knownLength = false)
        val partial = object : RandomAccessByteSource by encrypted {
            override suspend fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int) =
                encrypted.readAt(position, buffer, offset, minOf(length, 2))
        }
        val view = assertNotNull(SpotifyPlayPlaySource.open(partial, key.hex(), iv.hex(), true))
        assertNull(view.length)
        assertEquals(10_000L, view.expiresAtEpochMs)
        assertEquals("audio/flac", view.mimeType)
        val result = view.source.readUpTo(0, bytes.size)
        assertContentEquals(bytes, result.bytes)
        assertEquals(RandomAccessByteSource.END_OF_STREAM, view.source.readAt(bytes.size.toLong(), ByteArray(1), 0, 1))
    }

    @Test fun badKeysTruncationAndWrongContainerAreRefusedWithoutCiphertextAsAudio() = runTest {
        val flac = source("fLaC".encodeToByteArray() + ByteArray(20), 0)
        assertNull(SpotifyPlayPlaySource.open(flac, "invalid", iv.hex(), true))
        assertNull(SpotifyPlayPlaySource.open(flac, key.hex(), "invalid", true))
        assertNull(SpotifyPlayPlaySource.open(flac, ByteArray(16).hex(), iv.hex(), true))
        assertNull(SpotifyPlayPlaySource.open(flac, key.hex(), iv.hex(), false))
        assertNull(SpotifyPlayPlaySource.open(source(byteArrayOf(1, 2), 0), key.hex(), iv.hex(), true))
    }

    @Test fun nativePreparationKeepsAccountScopeAndLateLeaseFailuresOrCancellationEscape() = runTest {
        val encrypted = source("OggS".encodeToByteArray() + ByteArray(100), 167)
        var revoked = false
        var cancelled = false
        val guarded = object : RandomAccessByteSource by encrypted {
            override suspend fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                if (cancelled) throw CancellationException("fixture")
                if (revoked) throw ByteSourceException("account revoked")
                return encrypted.readAt(position, buffer, offset, length)
            }
        }
        val recipe = NativeAudioRecipe.SpotifyPlayPlay("https://cdn.invalid/audio", AudioSecret(key.hex()),
            AudioSecret(iv.hex()), false, "streaming-scope")
        val ready = assertIs<NativePreparation.Ready>(NativeRecipePreparation.prepare(recipe,
            object : NativeCiphertextOpener {
                override suspend fun open(url: String, accountScope: String): RandomAccessByteSource {
                    assertEquals(recipe.url, url)
                    assertEquals(recipe.accountScope, accountScope)
                    return guarded
                }
            }))
        revoked = true
        assertFailsWith<ByteSourceException> { ready.plaintext.source.readAt(4, ByteArray(1), 0, 1) }
        cancelled = true
        assertFailsWith<CancellationException> { ready.plaintext.source.readAt(4, ByteArray(1), 0, 1) }
        assertIs<NativePreparation.Refused>(NativeRecipePreparation.prepare(recipe, object : NativeCiphertextOpener {
            override suspend fun open(url: String, accountScope: String): RandomAccessByteSource? = null
        }))
    }
}
