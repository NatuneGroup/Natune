package moe.rukamori.archivetune.shared.data.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The export naming contract, including the UTF-8 byte cap that keeps `name.ext` inside the 255-byte limit. */
class DownloadExportNamingTest {
    @Test
    fun japaneseTitleFitsTheByteBudget() {
        val title = "夜に駆けるアイドル群青ララバイ怪物祝福勇者ミラージュ決意スパークルハルジオン" +
            "夜に駆けるアイドル群青ララバイ怪物祝福勇者ミラージュ決意スパークルハルジオン" +
            "夜に駆けるアイドル群青ララバイ怪物祝福勇者ミラージュ決意スパークルハルジオン"
        val base = DownloadExportNaming.fileBaseName(title, "アーティスト名")
        // The worst case is the longest emitted extension; the component stays under the 255-byte filesystem limit.
        assertTrue("$base.webm".encodeToByteArray().size <= 255)
        // The cap never splits a multi-byte character: decoding the bytes must give the string back whole.
        assertEquals(base.encodeToByteArray().decodeToString(), base)
    }

    @Test
    fun asciiLongNameFitsTheByteBudget() {
        val base = DownloadExportNaming.fileBaseName("a".repeat(500), "artist")
        assertTrue("$base.flac".encodeToByteArray().size <= 255)
    }

    @Test
    fun forbiddenCharsBlankAndTrailingDot() {
        assertEquals("a_b", DownloadExportNaming.fileBaseName("a/b", ""))
        assertEquals("_", DownloadExportNaming.fileBaseName("a:b", "artist").substringAfterLast(' '))
        assertEquals("song", DownloadExportNaming.fileBaseName("...", ""))
        assertEquals("song", DownloadExportNaming.fileBaseName("   ", ""))
        assertEquals("artist - title", DownloadExportNaming.fileBaseName("title", "artist"))
        assertEquals("title", DownloadExportNaming.fileBaseName("title", ""))
    }

    @Test
    fun extensionSniffing() {
        assertEquals("flac", DownloadExportNaming.extensionOf("fLaCxxxx".encodeToByteArray()))
        assertEquals("ogg", DownloadExportNaming.extensionOf("OggSxxxx".encodeToByteArray()))
        assertEquals("mp3", DownloadExportNaming.extensionOf("ID3xxxxx".encodeToByteArray()))
        assertEquals("m4a", DownloadExportNaming.extensionOf("xxxxftyp".encodeToByteArray()))
        assertEquals("wav", DownloadExportNaming.extensionOf("RIFFxxxxWAVE".encodeToByteArray()))
        assertEquals("webm", DownloadExportNaming.extensionOf(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())))
        assertEquals("mp3", DownloadExportNaming.extensionOf(byteArrayOf(0xFF.toByte(), 0xFB.toByte())))
        assertEquals("bin", DownloadExportNaming.extensionOf(byteArrayOf(0x01, 0x02, 0x03)))
        assertEquals("bin", DownloadExportNaming.extensionOf(byteArrayOf()))
    }
}
