package moe.rukamori.archivetune.shared.data.stream.hls

import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.shared.data.bytesource.ByteArrayByteSource
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceAccount
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceException
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceLease
import moe.rukamori.archivetune.shared.data.bytesource.RandomAccessByteSource
import moe.rukamori.archivetune.shared.data.bytesource.StreamOrigin
import moe.rukamori.archivetune.shared.data.bytesource.readUpTo
import moe.rukamori.archivetune.shared.data.stream.NativeCiphertextOpener
import moe.rukamori.archivetune.shared.data.stream.NativePreparation
import moe.rukamori.archivetune.shared.data.stream.NativeRecipePreparation
import moe.rukamori.archivetune.shared.data.stream.aes.Aes128
import moe.rukamori.archivetune.shared.data.stream.aes.AesCbc
import moe.rukamori.archivetune.shared.domain.repository.NativeAudioRecipe
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class HlsSegmentedSourceTest {
    private val base = "https://media.example/hls/list.m3u8"
    private val scope = "public"

    private class FakeOpener(
        vararg files: Pair<String, ByteArray>,
    ) : NativeCiphertextOpener {
        private val files = files.toMap()

        override suspend fun open(url: String, accountScope: String): RandomAccessByteSource? =
            files[url]?.let { ByteArrayByteSource(testLease(url, it.size.toLong()), it) }

        override suspend fun openWhole(url: String, accountScope: String, maxBytes: Int): ByteArray? =
            files[url]?.takeIf { it.size <= maxBytes }
    }

    @Test
    fun concatenatesClearSegmentsWithSeekBack() = runTest {
        val a = ByteArray(300) { (it % 7).toByte() }
        val b = ByteArray(200) { (it % 5).toByte() }
        val source = segmentedSource(
            playlist("#EXTM3U\n#EXTINF:1,\na.ts\n#EXTINF:1,\nb.ts\n#EXT-X-ENDLIST\n"),
            "https://media.example/hls/a.ts" to a,
            "https://media.example/hls/b.ts" to b,
        )
        source.indexOpeningSegments()
        // A read ends at its piece boundary, so reads that span segments accumulate through readUpTo.
        assertContentEquals(a + b, source.readUpTo(0, 500).bytes)
        // A backward seek re-reads an already indexed piece.
        assertContentEquals(a.copyOfRange(10, 50), source.readUpTo(10, 40).bytes)
        assertContentEquals((a + b).copyOfRange(295, 305), source.readUpTo(295, 10).bytes)
        assertEquals(
            RandomAccessByteSource.END_OF_STREAM,
            source.readAt(500, ByteArray(10), 0, 10),
        )
    }

    @Test
    fun decryptsAes128SegmentsWithExplicitAndSequenceIvs() = runTest {
        val key = ByteArray(16) { (it * 3 + 1).toByte() }
        val aes = Aes128(key)
        val plainA = ByteArray(100) { it.toByte() }
        val plainB = ByteArray(70) { (it * 2).toByte() }
        // Segment a's key tag carries an explicit IV; segment b's rotated tag declares none, so the media
        // sequence number 41 becomes its IV, as the specification mandates.
        val ivA = ByteArray(16).also { it[15] = 9 }
        val ivB = HlsAes128.initializationVector(null, 41)
        val cipherA = AesCbc.encryptNoPadding(aes, ivA, pkcs7(plainA), pkcs7(plainA).size)
        val cipherB = AesCbc.encryptNoPadding(aes, ivB, pkcs7(plainB), pkcs7(plainB).size)
        val source = segmentedSource(
            playlist(
                "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:40\n" +
                    "#EXT-X-KEY:METHOD=AES-128,URI=\"https://keys.example/k\",IV=0x00000000000000000000000000000009\n" +
                    "#EXTINF:1,\na.ts\n" +
                    "#EXT-X-KEY:METHOD=AES-128,URI=\"k2\"\n" +
                    "#EXTINF:1,\nb.ts\n#EXT-X-ENDLIST\n",
            ),
            "https://keys.example/k" to key,
            "https://media.example/hls/k2" to key,
            "https://media.example/hls/a.ts" to cipherA,
            "https://media.example/hls/b.ts" to cipherB,
        )
        source.indexOpeningSegments()
        assertContentEquals(plainA + plainB, source.readUpTo(0, 170).bytes)
    }

    @Test
    fun honorsByteRangesAndInitMap() = runTest {
        val init = byteArrayOf(0, 0, 0, 24) + "ftypM4A ".encodeToByteArray() + ByteArray(4)
        val resource = ByteArray(500) { (it * 11).toByte() }
        // Two segments share one resource: 100@0 then a continuation range of 40.
        val source = segmentedSource(
            playlist(
                "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n" +
                    "#EXTINF:1,\n#EXT-X-BYTERANGE:100@0\nm.fmp4\n" +
                    "#EXTINF:1,\n#EXT-X-BYTERANGE:40\nm.fmp4\n#EXT-X-ENDLIST\n",
            ),
            "https://media.example/hls/init.mp4" to init,
            "https://media.example/hls/m.fmp4" to resource,
        )
        source.indexOpeningSegments()
        assertEquals("audio/mp4", source.sniffedMimeType)
        val all = source.readUpTo(0, init.size + 140).bytes
        assertEquals(init.size + 140, all.size)
        assertContentEquals(init, all.copyOfRange(0, init.size))
        assertContentEquals(resource.copyOfRange(0, 100), all.copyOfRange(init.size, init.size + 100))
        assertContentEquals(resource.copyOfRange(100, 140), all.copyOfRange(init.size + 100, all.size))
    }

    @Test
    fun decryptsEncryptedInitMapWithItsExplicitIv() = runTest {
        val key = ByteArray(16) { (it + 2).toByte() }
        val aes = Aes128(key)
        val iv = ByteArray(16).also { it[15] = 4 }
        val initPlain = byteArrayOf(0, 0, 0, 24) + "ftypM4A ".encodeToByteArray() + ByteArray(20)
        val initCipher = AesCbc.encryptNoPadding(aes, iv, pkcs7(initPlain), pkcs7(initPlain).size)
        val segPlain = ByteArray(64) { (it * 3).toByte() }
        val segIv = ByteArray(16).also { it[15] = 9 }
        val segCipher = AesCbc.encryptNoPadding(aes, segIv, pkcs7(segPlain), pkcs7(segPlain).size)
        val source = segmentedSource(
            playlist(
                "#EXTM3U\n" +
                    "#EXT-X-KEY:METHOD=AES-128,URI=\"k\",IV=0x00000000000000000000000000000004\n" +
                    "#EXT-X-MAP:URI=\"init.mp4\"\n" +
                    "#EXT-X-KEY:METHOD=AES-128,URI=\"k\",IV=0x00000000000000000000000000000009\n" +
                    "#EXTINF:1,\nseg.ts\n#EXT-X-ENDLIST\n",
            ),
            "https://media.example/hls/k" to key,
            "https://media.example/hls/init.mp4" to initCipher,
            "https://media.example/hls/seg.ts" to segCipher,
        )
        source.indexOpeningSegments()
        assertEquals("audio/mp4", source.sniffedMimeType)
        assertContentEquals(initPlain + segPlain, source.readUpTo(0, initPlain.size + segPlain.size).bytes)
    }

    @Test
    fun refusesUnavailableSegmentAndMissingKey() = runTest {
        val missing = segmentedSource(
            playlist("#EXTM3U\n#EXTINF:1,\na.ts\n#EXT-X-ENDLIST\n"),
        )
        assertFailsWith<ByteSourceException> {
            missing.readAt(0, ByteArray(8), 0, 8)
        }
        val keyless = segmentedSource(
            playlist(
                "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"https://keys.example/none\"\n" +
                    "#EXTINF:1,\na.ts\n#EXT-X-ENDLIST\n",
            ),
            "https://media.example/hls/a.ts" to ByteArray(32),
        )
        assertFailsWith<ByteSourceException> {
            keyless.readAt(0, ByteArray(8), 0, 8)
        }
    }

    @Test
    fun appleHlsRecipeAcceptsAes128AndRefusesFairPlay() = runTest {
        val key = ByteArray(16) { 7 }
        val aes = Aes128(key)
        val plain = ByteArray(48) { it.toByte() }
        val iv = ByteArray(16)
        val cipher = AesCbc.encryptNoPadding(aes, iv, pkcs7(plain), pkcs7(plain).size)
        val playlistText =
            "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"https://keys.example/k\"\n" +
                "#EXTINF:1,\nseg.ts\n#EXT-X-ENDLIST\n"
        val opener = FakeOpener(
            "https://keys.example/k" to key,
            "https://media.example/hls/seg.ts" to cipher,
        )
        val ready = NativeRecipePreparation.prepare(
            NativeAudioRecipe.AppleHls(base, playlistText, "https://license.example/", "256", scope),
            opener,
        )
        assertIs<NativePreparation.Ready>(ready)
        assertContentEquals(plain, ready.plaintext.source.readUpTo(0, 48).bytes)
        assertNull(ready.plaintext.length)

        val fairPlay = NativeRecipePreparation.prepare(
            NativeAudioRecipe.AppleHls(
                base,
                "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"skd://k\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n" +
                    "#EXTINF:1,\nseg.ts\n#EXT-X-ENDLIST\n",
                "https://license.example/",
                "256",
                scope,
            ),
            opener,
        )
        assertIs<NativePreparation.Refused>(fairPlay)
    }

    @Test
    fun segmentedHlsRecipeFetchesPlaylistThroughOpener() = runTest {
        val playlistText = "#EXTM3U\n#EXTINF:1,\nseg.ts\n#EXT-X-ENDLIST\n"
        val payload = ByteArray(120) { (it * 5).toByte() }
        val opener = FakeOpener(
            base to playlistText.encodeToByteArray(),
            "https://media.example/hls/seg.ts" to payload,
        )
        val ready = NativeRecipePreparation.prepare(
            NativeAudioRecipe.SegmentedHls(
                playlistUrl = base,
                playlistText = null,
                audioSource = AudioSourceType.QQ,
                accountScope = scope,
            ),
            opener,
        )
        assertIs<NativePreparation.Ready>(ready)
        assertContentEquals(payload, ready.plaintext.source.readUpTo(0, 120).bytes)
    }

    private fun segmentedSource(
        playlistText: String,
        vararg files: Pair<String, ByteArray>,
    ): HlsSegmentedSource =
        HlsSegmentedSource(
            lease = testLease(base, null),
            playlistUrl = base,
            playlistText = playlistText,
            accountScope = scope,
            opener = FakeOpener(*files),
        )

    private fun playlist(text: String): String = text

    /** PKCS#7-pads to the next 16-byte boundary, as an AES-128 segment is stored. */
    private fun pkcs7(plain: ByteArray): ByteArray {
        val pad = 16 - (plain.size % 16)
        return plain + ByteArray(pad) { pad.toByte() }
    }
}

private fun testLease(
    url: String,
    length: Long?,
): ByteSourceLease {
    val origin = StreamOrigin.parse(url) ?: StreamOrigin.parse("https://media.example")!!
    return ByteSourceLease(
        account = ByteSourceAccount(provider = "test", accountId = null),
        primaryOrigin = origin,
        permittedOrigins = setOf(origin),
        cacheKey = url,
        expiresAtEpochMs = null,
        lengthBytes = length,
    )
}
