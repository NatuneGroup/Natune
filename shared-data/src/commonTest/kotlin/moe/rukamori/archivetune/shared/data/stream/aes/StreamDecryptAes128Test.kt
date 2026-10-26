/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.data.stream.aes

import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.shared.data.bytesource.ByteArrayByteSource
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceAccount
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceException
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceLease
import moe.rukamori.archivetune.shared.data.bytesource.RandomAccessByteSource
import moe.rukamori.archivetune.shared.data.bytesource.StreamOrigin
import moe.rukamori.archivetune.shared.data.stream.hls.HlsAes128
import moe.rukamori.archivetune.shared.data.stream.hls.HlsAes128SegmentSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamDecryptAes128Test {
    private val fipsKHex = "000102030405060708090a0b0c0d0e0f"
    private val fipsPlainHex = "00112233445566778899aabbccddeeff"
    private val fipsCipherHex = "69c4e0d86a7b0430d8cdb78070b4c55a"

    private val nistCbcKHex = "2b7e151628aed2a6abf7158809cf4f3c"
    private val nistCbcIvHex = "000102030405060708090a0b0c0d0e0f"
    private val nistCbcPlaintextHex =
        "6bc1bee22e409f96e93d7e117393172a" +
            "ae2d8a571e03ac9c9eb76fac45af8e51" +
            "30c81c46a35ce411e5fbc1191a0a52ef" +
            "f69f2445df4f9b17ad2b417be66c3710"
    private val nistCbcCiphertextHex =
        "7649abac8119b246cee98e9b12e9197d" +
            "5086cb9b507219ee95db113a917678b2" +
            "73bed6b8e3c1743b7116e69e22229516" +
            "3ff1caa1681fac09120eca307586e1a7"

    @Test
    fun fipsAppendixC1EncryptsAndDecrypts() {
        val aes = Aes128(hex(fipsKHex))
        val out = ByteArray(16)
        aes.encryptBlock(hex(fipsPlainHex), 0, out, 0)
        assertContentEquals(hex(fipsCipherHex), out)
        val back = ByteArray(16)
        aes.decryptBlock(out, 0, back, 0)
        assertContentEquals(hex(fipsPlainHex), back)
    }

    @Test
    fun nistSp800_38aCbcVectorsInBothDirections() {
        val aes = Aes128(hex(nistCbcKHex))
        val iv = hex(nistCbcIvHex)
        val plain = hex(nistCbcPlaintextHex)
        val cipher = hex(nistCbcCiphertextHex)
        assertContentEquals(cipher, AesCbc.encryptNoPadding(aes, iv, plain, plain.size))
        assertContentEquals(plain, AesCbc.decryptNoPadding(aes, iv, cipher, cipher.size))
    }

    @Test
    fun pkcs7UnpadAcceptsValidAndRejectsInvalidPadding() {
        val valid = ByteArray(16) { 0x41 }
        for (i in 12 until 16) valid[i] = 4
        assertEquals(4, AesCbc.pkcs7Unpad(valid))

        val fullBlock = ByteArray(16) { 16 }
        assertEquals(16, AesCbc.pkcs7Unpad(fullBlock))

        val zeroPad = ByteArray(16) { 0x41 }
        zeroPad[15] = 0
        assertFailsWith<ByteSourceException> { AesCbc.pkcs7Unpad(zeroPad) }

        val seventeenPad = ByteArray(16) { 0x41 }
        seventeenPad[15] = 17
        assertFailsWith<ByteSourceException> { AesCbc.pkcs7Unpad(seventeenPad) }

        val inconsistent = ByteArray(16) { 0x41 }
        for (i in 12 until 16) inconsistent[i] = 4
        inconsistent[13] = 3
        assertFailsWith<ByteSourceException> { AesCbc.pkcs7Unpad(inconsistent) }
    }

    @Test
    fun hlsSegmentSourceReturnsPlaintextForFullAndRandomReads() = runTest {
        val plain = ByteArray(1000) { (it * 7 + 3).toByte() }
        val cipher = encryptWithPkcs7(plain)
        val source = segmentSource(cipher)

        val full = ByteArray(plain.size)
        assertEquals(plain.size, source.readAt(0, full, 0, full.size))
        assertContentEquals(plain, full)

        for (offset in listOf(0, 1, 15, 16, 17, 511, 999)) {
            for (length in 1..40) {
                val buffer = ByteArray(length)
                val expected = minOf(length, plain.size - offset)
                val count = source.readAt(offset.toLong(), buffer, 0, length)
                assertEquals(expected, count, "offset=$offset length=$length")
                assertContentEquals(plain.copyOfRange(offset, offset + expected), buffer.copyOf(count))
            }
        }

        val buffer = ByteArray(8)
        assertEquals(RandomAccessByteSource.END_OF_STREAM, source.readAt(1000, buffer, 0, 8))
        assertEquals(RandomAccessByteSource.END_OF_STREAM, source.readAt(1200, buffer, 0, 8))
    }

    @Test
    fun hlsIvDerivationFromMediaSequenceAndHexText() {
        val derived = HlsAes128.initializationVector(null, 258)
        assertEquals(16, derived.size)
        for (i in 0 until 14) assertEquals(0, derived[i].toInt())
        assertEquals(1, derived[14].toInt())
        assertEquals(2, derived[15].toInt())

        val parsed = HlsAes128.parseHexIv("0x000102030405060708090a0b0c0d0e0f")
        assertContentEquals(ByteArray(16) { it.toByte() }, parsed)
    }

    @Test
    fun hlsKeyTagParsing() {
        val tag =
            HlsAes128.parseKeyTag(
                "#EXT-X-KEY:METHOD=AES-128,URI=\"https://k.example/a,b.key\",IV=0x00000000000000000000000000000001",
            )
        assertEquals("https://k.example/a,b.key", tag?.keyUri)
        assertEquals(1, tag?.iv?.last()?.toInt())
        assertNull(HlsAes128.parseKeyTag("#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"x\""))
        assertNull(HlsAes128.parseKeyTag("#EXT-X-KEY:METHOD=NONE"))
        assertTrue(HlsAes128.parseKeyTag("#EXT-X-KEY:METHOD=AES-128,URI=\"x\"")?.iv == null)
    }

    @Test
    fun segmentSourceRejectsUnknownOrUnalignedLength() {
        val source = ByteArrayByteSource(lease(15), ByteArray(15))
        assertFailsWith<ByteSourceException> { HlsAes128SegmentSource(source, Aes128(hex(fipsKHex)), ByteArray(16)) }
        val unknown = ByteArrayByteSource(lease(null), ByteArray(16))
        assertFailsWith<ByteSourceException> { HlsAes128SegmentSource(unknown, Aes128(hex(fipsKHex)), ByteArray(16)) }
    }

    private fun segmentSource(cipher: ByteArray): HlsAes128SegmentSource =
        HlsAes128SegmentSource(
            ByteArrayByteSource(lease(cipher.size.toLong()), cipher),
            Aes128(hex(fipsKHex)),
            ByteArray(16) { (it + 1).toByte() },
        )

    private fun encryptWithPkcs7(plain: ByteArray): ByteArray {
        val padLength = 16 - plain.size % 16
        val padded = plain.copyOf(plain.size + padLength)
        for (i in plain.size until padded.size) padded[i] = padLength.toByte()
        val iv = ByteArray(16) { (it + 1).toByte() }
        return AesCbc.encryptNoPadding(Aes128(hex(fipsKHex)), iv, padded, padded.size)
    }

    private fun lease(length: Long?): ByteSourceLease =
        ByteSourceLease(
            account = ByteSourceAccount("hls", null),
            primaryOrigin = StreamOrigin.parse("https://cdn.example/seg.ts")!!,
            permittedOrigins = setOf(StreamOrigin.parse("https://cdn.example/seg.ts")!!),
            cacheKey = "segment",
            expiresAtEpochMs = null,
            lengthBytes = length,
        )

    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
