package moe.rukamori.archivetune.shared.data.persistence

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import moe.rukamori.archivetune.shared.domain.platform.FileHandle

class JvmPlatformFilesTest {
    @Test fun replacementIsAtomicAndReadsAreBoundedAndRootConfined() = runTest {
        val root = Files.createTempDirectory("archivetune-files-test")
        try {
            val files = JvmPlatformFiles(root)
            val file = FileHandle("cache.json")
            files.writeAtomic(file, "old".encodeToByteArray())
            files.writeAtomic(file, "new".encodeToByteArray())
            assertEquals("new", files.read(file, 3).decodeToString())
            assertFailsWith<IllegalArgumentException> { files.read(file, 2) }
            assertFailsWith<IllegalArgumentException> { files.read(FileHandle("../outside"), 100) }
            assertFailsWith<IllegalArgumentException> { files.writeAtomic(FileHandle(root.parent.resolve("outside").toString()), byteArrayOf(1)) }
            assertEquals(listOf("cache.json"), Files.list(root).use { it.map { path -> path.fileName.toString() }.toList() })
            files.delete(file)
            assertFalse(Files.exists(root.resolve("cache.json")))
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun rejectsLinkTraversal() = runTest {
        if (System.getProperty("os.name").startsWith("Windows")) return@runTest
        val root = Files.createTempDirectory("archivetune-files-link-test")
        val outside = Files.createTempFile("archivetune-files-outside", ".txt")
        try {
            Files.writeString(outside, "private")
            Files.createSymbolicLink(root.resolve("link"), outside)
            val files = JvmPlatformFiles(root)
            assertFailsWith<IllegalArgumentException> { files.read(FileHandle("link"), 100) }
            assertFailsWith<IllegalArgumentException> { files.writeAtomic(FileHandle("link"), "new".encodeToByteArray()) }
            assertEquals("private", Files.readString(outside))
        } finally { root.toFile().deleteRecursively(); Files.deleteIfExists(outside) }
    }
}
