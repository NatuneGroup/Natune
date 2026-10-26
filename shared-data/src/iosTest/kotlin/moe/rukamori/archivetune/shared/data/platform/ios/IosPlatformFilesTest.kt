/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.platform.ios

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import moe.rukamori.archivetune.shared.domain.platform.FileHandle

class IosPlatformFilesTest {
    @Test
    fun arbitraryPathsAndUnboundedReadsAreNotDocumentGrants() = runBlocking {
        val files = IosPlatformFiles()
        assertFailsWith<IllegalArgumentException> { files.read(FileHandle("/etc/passwd"), 32) }
        assertFailsWith<IllegalArgumentException> { files.read(FileHandle("ios-bookmark:invalid"), -1) }
        assertFailsWith<IllegalArgumentException> { files.read(FileHandle("ios-bookmark:invalid"), Long.MAX_VALUE) }
    }
}
