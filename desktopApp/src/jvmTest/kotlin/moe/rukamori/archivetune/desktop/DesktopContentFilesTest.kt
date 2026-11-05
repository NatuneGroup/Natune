package moe.rukamori.archivetune.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DesktopContentFilesTest {
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
