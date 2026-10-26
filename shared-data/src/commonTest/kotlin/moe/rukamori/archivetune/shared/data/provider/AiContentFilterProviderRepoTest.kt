/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import moe.rukamori.archivetune.shared.domain.platform.*

@OptIn(ExperimentalCoroutinesApi::class)
class AiContentFilterProviderRepoTest {
    private class MemoryFiles : PlatformFiles {
        var bytes: ByteArray? = null
        override suspend fun read(file: FileHandle, maxBytes: Long): ByteArray = bytes ?: error("missing fixture")
        override suspend fun writeAtomic(file: FileHandle, bytes: ByteArray) { this.bytes = bytes.copyOf() }
        override suspend fun delete(file: FileHandle) { bytes = null }
    }
    @Test fun fetchedListsPersistAndModerateConfidenceStaysOptIn() = runTest {
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { respond(if (it.url.encodedPath.endsWith("blocklist.txt")) "!comment\n@Bad-Channel\n" else "@MaybeChannel\n") }
        } }
        val files = MemoryFiles()
        try {
            val repo = KtorAiContentFilterRepository(client, files, FileHandle("cache"), { 100L })
            assertTrue(repo.refresh(enabled = true).updated)
            assertTrue(repo.blocks("@Bad_Channel", enabled = true, includeModerate = false))
            assertFalse(repo.blocks("MaybeChannel", enabled = true, includeModerate = false))
            assertTrue(repo.blocks("MaybeChannel", enabled = true, includeModerate = true))
            assertFalse(repo.blocks("BadChannel", enabled = false, includeModerate = true))
            val reopened = KtorAiContentFilterRepository(client, files, FileHandle("cache"), { 101L })
            assertFalse(reopened.refresh(enabled = true).updated)
            assertTrue(reopened.blocks("BadChannel", true, false))
        } finally { client.close() }
    }
    @Test fun failedOrEmptyRefreshDoesNotReplaceUsefulCache() = runTest {
        var fail = false
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { respond(if (fail) "!nothing" else "@Existing") }
        } }
        try {
            val files = MemoryFiles()
            val repo = KtorAiContentFilterRepository(client, files, FileHandle("cache"), { 100L })
            assertTrue(repo.refresh(true).updated)
            val before = files.bytes!!.copyOf()
            fail = true
            assertEquals(AiFilterRefreshStatus.UNAVAILABLE, repo.refresh(true, force = true).status)
            assertContentEquals(before, files.bytes)
            assertTrue(repo.blocks("@Existing", true, false))
        } finally { client.close() }
    }
}
