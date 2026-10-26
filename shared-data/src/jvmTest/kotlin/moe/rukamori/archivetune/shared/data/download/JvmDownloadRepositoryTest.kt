package moe.rukamori.archivetune.shared.data.download

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.*
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType

class JvmDownloadRepositoryTest {
    @Test fun sourceCachesAreDistinctAndDownloadsNeverEvictAutomatically() = runTest {
        val dir = Files.createTempDirectory("archivetune-download-test")
        val client = HttpClient(MockEngine { respond("fixture-media") })
        try {
            val repository = JvmDownloadRepository(dir, client)
            val tidal = DownloadKey("same", AudioSourceType.TIDAL, "lossless", "fixture-provider")
            val deezer = tidal.copy(source = AudioSourceType.DEEZER)
            val first = repository.download(tidal, "https://audio.example/t")
            val second = repository.download(deezer, "https://audio.example/d")
            assertNotEquals(first, second)
            assertTrue(Files.exists(first) && Files.exists(second))
            assertContentEquals("fixture-media".toByteArray(), Files.readAllBytes(first))
            assertEquals(first, repository.find(tidal))
        } finally { client.close(); dir.toFile().deleteRecursively() }
    }
    @Test fun cancelledOlderRequestCannotDeleteNewerStagingOrCommittedDownload() = runTest {
        val dir = Files.createTempDirectory("archivetune-download-race")
        val began = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine {
            if (it.url.encodedPath == "/old") { began.complete(Unit); awaitCancellation() }
            respond("new")
        })
        try {
            val repository = JvmDownloadRepository(dir, client)
            val key = DownloadKey("same", AudioSourceType.TIDAL, "lossless", "fixture-provider")
            val old = launch { repository.download(key, "https://audio.example/old") }
            began.await()
            val result = repository.download(key, "https://audio.example/new")
            old.cancelAndJoin()
            assertEquals("new", Files.readString(result))
            assertEquals(result, repository.find(key))
            Files.list(dir.resolve("staging")).use { assertEquals(0L, it.count()) }
        } finally { client.close(); dir.toFile().deleteRecursively() }
    }
    @Test fun refusedStreamingAccountAndOversizeNeverLeavePartialSuccess() = runTest {
        val dir = Files.createTempDirectory("archivetune-download-limit")
        val client = HttpClient(MockEngine { respond("too large") })
        try {
            val repository = JvmDownloadRepository(dir, client, maxBytes = 2)
            assertFailsWith<IllegalArgumentException> {
                repository.download(DownloadKey("id", AudioSourceType.SPOTIFY, "x"), "https://audio.example")
            }
            val key = DownloadKey("id", AudioSourceType.QOBUZ, "x", "fixture-provider")
            assertFailsWith<IllegalArgumentException> { repository.download(key, "https://audio.example") }
            assertNull(repository.find(key))
        } finally { client.close(); dir.toFile().deleteRecursively() }
    }
}
