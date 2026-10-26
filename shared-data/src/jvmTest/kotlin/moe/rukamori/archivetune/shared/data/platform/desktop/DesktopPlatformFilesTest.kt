/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.platform.desktop

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import moe.rukamori.archivetune.shared.domain.platform.FileHandle

class DesktopPlatformFilesTest {
    @Test fun sizeBoundAndAtomicReplacementPreserveContent() = runBlocking {
        val root = Files.createTempDirectory("archivetune-files")
        val target = root.resolve("track.txt")
        val handle = FileHandle(target.toUri().toString())
        val files = DesktopPlatformFiles()
        try {
            files.writeAtomic(handle, byteArrayOf(1, 2, 3))
            assertFailsWith<IllegalArgumentException> { files.read(handle, 2) }
            assertContentEquals(byteArrayOf(1, 2, 3), files.read(handle, 3))
            files.writeAtomic(handle, byteArrayOf(4))
            assertContentEquals(byteArrayOf(4), files.read(handle, 3))
            assertFailsWith<IllegalArgumentException> { files.read(FileHandle("relative"), 10) }
            Files.list(root).use { assertEquals(1, it.count()) }
            files.delete(handle)
        } finally { Files.deleteIfExists(target); Files.delete(root) }
    }
}
