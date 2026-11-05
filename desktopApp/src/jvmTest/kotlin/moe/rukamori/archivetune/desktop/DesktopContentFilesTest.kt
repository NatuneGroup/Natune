package moe.rukamori.archivetune.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DesktopContentFilesTest {
    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()
    private fun ascii(value: String) = value.toByteArray(Charsets.US_ASCII)

    @Test
    fun containerSignaturesPickTheRealExtension() {
        assertEquals("flac", desktopAudioExtension(ascii("fLaC") + bytes(0, 0)))
        assertEquals("ogg", desktopAudioExtension(ascii("OggS") + bytes(0, 2)))
        assertEquals("wav", desktopAudioExtension(ascii("RIFF") + bytes(0, 0, 0, 0) + ascii("WAVE")))
        assertEquals("m4a", desktopAudioExtension(bytes(0, 0, 0, 24) + ascii("ftyp") + ascii("M4A ")))
        assertEquals("webm", desktopAudioExtension(bytes(0x1a, 0x45, 0xdf, 0xa3) + ascii("....webm")))
        assertEquals("mka", desktopAudioExtension(bytes(0x1a, 0x45, 0xdf, 0xa3) + ascii("....matroska")))
        assertEquals("mp3", desktopAudioExtension(ascii("ID3") + bytes(4, 0)))
        assertEquals("aac", desktopAudioExtension(bytes(0xff, 0xf1, 0x50)))
        assertEquals("aac", desktopAudioExtension(bytes(0xff, 0xf9, 0x50)))
        assertEquals("mp3", desktopAudioExtension(bytes(0xff, 0xfb, 0x90)))
        assertEquals("bin", desktopAudioExtension(bytes(0x00, 0x01, 0x02, 0x03)))
        assertEquals("bin", desktopAudioExtension(byteArrayOf()))
        assertEquals("bin", desktopAudioExtension(bytes(0xff)))
    }

    @Test
    fun exportsAreAtomicAndRejectSelfCopy() {
        // A body, not `= runBlocking { }`: JUnit4 only runs void test methods, and the last assertion here
        // returns the exception it matched.
        runBlocking {
            val dir = Files.createTempDirectory("desktop-content-files-test")
            try {
                val source = dir.resolve("song.flac")
                Files.write(source, byteArrayOf(1, 2, 3))
                val target = dir.resolve("out.flac")
                copyDesktopContentFile(source, target)
                assertTrue(Files.readAllBytes(target).contentEquals(byteArrayOf(1, 2, 3)))
                // The staging file never survives a successful move.
                assertEquals(listOf("out.flac", "song.flac"), Files.list(dir).use { it.map { p -> p.fileName.toString() }.sorted().toList() })
                assertFailsWith<IllegalArgumentException> { copyDesktopContentFile(source, source) }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    }
}
