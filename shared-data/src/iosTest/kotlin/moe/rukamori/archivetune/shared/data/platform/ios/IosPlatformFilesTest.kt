package moe.rukamori.archivetune.shared.data.platform.ios

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import moe.rukamori.archivetune.shared.domain.platform.FileHandle

class IosPlatformFilesTest {
    @Test
    fun arbitraryPathsAndUnboundedReadsAreNotDocumentGrants(): Unit = runBlocking {
        val files = IosPlatformFiles()
        assertFailsWith<IllegalArgumentException> { files.read(FileHandle("/etc/passwd"), 32) }
        assertFailsWith<IllegalArgumentException> { files.read(FileHandle("ios-bookmark:invalid"), -1) }
        assertFailsWith<IllegalArgumentException> { files.read(FileHandle("ios-bookmark:invalid"), Long.MAX_VALUE) }
    }
}
