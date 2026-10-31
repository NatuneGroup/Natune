package moe.rukamori.archivetune.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopSongExportTest {
    @Test fun namesDropPathCharactersAndKeepArtistFirst() {
        assertEquals("A_B - C_D", DesktopSongExport.fileBaseName("C/D", "A:B"))
        assertEquals("Title", DesktopSongExport.fileBaseName("Title", ""))
        assertEquals("song", DesktopSongExport.fileBaseName("...", ""))
    }

    @Test fun extensionsFollowTheContainerBytes() {
        assertEquals("flac", DesktopSongExport.extensionOf("fLaC".toByteArray()))
        assertEquals("ogg", DesktopSongExport.extensionOf("OggS....".toByteArray()))
        assertEquals("m4a", DesktopSongExport.extensionOf("\u0000\u0000\u0000\u0020ftypM4A ".toByteArray()))
        assertEquals("webm", DesktopSongExport.extensionOf(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())))
        assertEquals("mp3", DesktopSongExport.extensionOf(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte())))
        assertEquals("wav", DesktopSongExport.extensionOf("RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray()))
        assertEquals("bin", DesktopSongExport.extensionOf(ByteArray(0)))
    }
}
