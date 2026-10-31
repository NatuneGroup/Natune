package moe.rukamori.archivetune.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking

class DesktopDownloadExportTest {
    @Test
    fun exportExtensionUsesTheContainerNotTheStorageName() = runBlocking {
        val directory = Files.createTempDirectory("natune-export-format")
        try {
            val stored = directory.resolve("hashed-storage.mp3")
            Files.write(stored, header("fLaC"))
            assertEquals("flac", desktopDownloadExtension(stored))
            Files.write(stored, header("ftyp", 4))
            assertEquals("m4a", desktopDownloadExtension(stored))
            Files.write(stored, header("OggS"))
            assertEquals("ogg", desktopDownloadExtension(stored))
            Files.write(stored, ByteArray(64))
            assertEquals("bin", desktopDownloadExtension(stored))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun completeExportReplacesTheDestinationAndKeepsTheDownload() = runBlocking {
        val directory = Files.createTempDirectory("natune-export-complete")
        try {
            val source = directory.resolve("stored")
            val target = directory.resolve("export.flac")
            val bytes = ByteArray(150_000) { (it % 127).toByte() }
            Files.write(source, bytes)
            Files.writeString(target, "earlier export")
            copyDesktopDownload(DesktopCompletedDownload(source, bytes.size.toLong()), target)
            assertContentEquals(bytes, Files.readAllBytes(target))
            assertContentEquals(bytes, Files.readAllBytes(source))
            assertOnlySourceAndDestination(directory)
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun aSizeMismatchNeverTruncatesAnEarlierExport() = runBlocking {
        val directory = Files.createTempDirectory("natune-export-size")
        try {
            val source = directory.resolve("stored")
            val target = directory.resolve("export")
            Files.writeString(source, "committed audio")
            Files.writeString(target, "earlier export")
            for (expected in listOf(1L, 1_000L)) {
                assertFailsWith<IllegalStateException> {
                    copyDesktopDownload(DesktopCompletedDownload(source, expected), target)
                }
                assertEquals("earlier export", Files.readString(target))
                assertEquals("committed audio", Files.readString(source))
                assertOnlySourceAndDestination(directory)
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun exportingOverTheStoredDownloadIsRefused() = runBlocking {
        val directory = Files.createTempDirectory("natune-export-self")
        try {
            val source = directory.resolve("stored")
            Files.writeString(source, "committed audio")
            assertFailsWith<IllegalArgumentException> {
                copyDesktopDownload(DesktopCompletedDownload(source, Files.size(source)), source)
            }
            assertEquals("committed audio", Files.readString(source))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun missingOrUncommittedBytesCannotBecomeAnExport() = runBlocking {
        val directory = Files.createTempDirectory("natune-export-missing")
        try {
            val source = directory.resolve("missing")
            val target = directory.resolve("export")
            assertFailsWith<IllegalArgumentException> {
                copyDesktopDownload(DesktopCompletedDownload(source, 12), target)
            }
            Files.write(source, ByteArray(0))
            assertFailsWith<IllegalArgumentException> {
                copyDesktopDownload(DesktopCompletedDownload(source, 0), target)
            }
            assertFalse(Files.exists(target))
        } finally { directory.toFile().deleteRecursively() }
    }

    private fun header(magic: String, offset: Int = 0): ByteArray =
        ByteArray(64).also { magic.toByteArray().copyInto(it, offset) }

    private fun assertOnlySourceAndDestination(directory: Path) {
        Files.list(directory).use { assertEquals(2L, it.count()) }
    }
}
