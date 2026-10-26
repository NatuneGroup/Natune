package moe.rukamori.archivetune.shared.data.localmedia

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LocalMediaScannerTest {
    @Test fun excludesSubtreesNotSiblingPrefixesAndDoesNotFollowSymlinks() = runTest {
        val root = Files.createTempDirectory("archivetune-scan-test")
        try {
            Files.createDirectories(root.resolve("skip"))
            Files.createDirectories(root.resolve("skip-other"))
            Files.writeString(root.resolve("song.MP3"), "fixture")
            Files.writeString(root.resolve("skip/hidden.mp3"), "hidden")
            Files.writeString(root.resolve("skip-other/visible.flac"), "visible")
            Files.writeString(root.resolve("unsupported.ape"), "unsupported")
            Files.createSymbolicLink(root.resolve("loop"), root)
            val result = JvmLocalMediaScanner().scan(listOf(root), listOf(root.resolve("skip")))
            assertEquals(setOf("song.MP3", "visible.flac"), result.map { it.path.fileName.toString() }.toSet())
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun rebindRequiresUniqueExactContentAndMissingOldPath() = runTest {
        val root = Files.createTempDirectory("archivetune-rebind-test")
        try {
            val oldPath = Files.writeString(root.resolve("old.mp3"), "original")
            val scanner = JvmLocalMediaScanner()
            val previous = scanner.scan(listOf(root)).single()
            val newPath = root.resolve("renamed.mp3")
            Files.move(oldPath, newPath)
            val candidates = scanner.scan(listOf(root))
            assertEquals(newPath, scanner.rebind(previous, candidates)?.path)
            Files.copy(newPath, root.resolve("duplicate.mp3"))
            assertNull(scanner.rebind(previous, scanner.scan(listOf(root))))
        } finally { root.toFile().deleteRecursively() }
    }
}
