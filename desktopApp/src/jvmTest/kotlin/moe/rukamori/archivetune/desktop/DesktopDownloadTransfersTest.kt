package moe.rukamori.archivetune.desktop

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.DownloadPhase

class DesktopDownloadTransfersTest {
    @Test
    fun immediateCompletionCannotBeOverwrittenByTheLaunch() = runBlocking {
        val calls = mutableListOf<Pair<ContentItem, String>>()
        Fixture { item, quality -> calls += item to quality }.use { fixture ->
            val item = song()
            fixture.transfers.start(item, "lossless")
            assertEquals(listOf(item to "lossless"), calls)
            val ended = fixture.transfers.state.value.getValue(item.ref.desktopDownloadId())
            assertEquals(DownloadPhase.COMPLETED, ended.phase)
            assertNull(ended.job)
        }
    }

    @Test
    fun duplicateStartKeepsOneRequestAndRemovalWaitsForItsCleanup() = runBlocking {
        val began = CompletableDeferred<Unit>()
        var calls = 0
        var cleanedUp = false
        Fixture { _, _ ->
            calls++
            began.complete(Unit)
            try { awaitCancellation() } finally { cleanedUp = true }
        }.use { fixture ->
            val item = song()
            fixture.transfers.start(item, "")
            began.await()
            fixture.transfers.start(item, "")
            assertEquals(1, calls)
            fixture.transfers.remove(item.ref) {
                assertTrue(cleanedUp)
                assertEquals(DownloadPhase.REMOVING, fixture.transfers.state.value[item.ref.desktopDownloadId()]?.phase)
            }
            assertTrue(fixture.transfers.state.value.isEmpty())
        }
    }

    @Test
    fun stoppedCatalogDownloadResumesWithItsMetadataAndQuality() = runBlocking {
        val calls = mutableListOf<Pair<ContentItem, String>>()
        Fixture { item, quality ->
            calls += item to quality
            awaitCancellation()
        }.use { fixture ->
            val item = song().copy(title = "Catalog title", album = "Catalog album")
            val id = item.ref.desktopDownloadId()
            fixture.transfers.start(item, "high")
            fixture.transfers.stop(id)
            assertEquals(DownloadPhase.STOPPED, fixture.transfers.state.value[id]?.phase)
            fixture.transfers.resume(id)
            assertEquals(listOf(item to "high", item to "high"), calls)
            assertEquals(DownloadPhase.DOWNLOADING, fixture.transfers.state.value[id]?.phase)
            fixture.transfers.stop(id)
        }
    }

    @Test
    fun differentProvidersWithTheSameMediaIdKeepIndependentJobs() = runBlocking {
        Fixture { _, _ -> awaitCancellation() }.use { fixture ->
            val first = song()
            val second = first.copy(ref = first.ref.copy(provider = "qobuz"))
            fixture.transfers.start(first, "")
            fixture.transfers.start(second, "")
            fixture.transfers.stop(first.ref.desktopDownloadId())
            assertEquals(DownloadPhase.STOPPED, fixture.transfers.state.value[first.ref.desktopDownloadId()]?.phase)
            assertEquals(DownloadPhase.DOWNLOADING, fixture.transfers.state.value[second.ref.desktopDownloadId()]?.phase)
            fixture.transfers.remove(second.ref) {}
            assertEquals(setOf(first.ref.desktopDownloadId()), fixture.transfers.state.value.keys)
        }
    }

    @Test
    fun failedTransferPublishesFailureWithoutLoggingProviderMaterial() = runBlocking {
        Fixture { _, _ -> throw IOException("provider detail must not be logged") }.use { fixture ->
            val item = song()
            fixture.transfers.start(item, "")
            assertEquals(DownloadPhase.FAILED, fixture.transfers.state.value[item.ref.desktopDownloadId()]?.phase)
            assertEquals(listOf("the download failed: IOException"), fixture.reports)
        }
    }

    @Test
    fun failedRemovalPropagatesAndCannotLeaveTheMenuPermanentlyBusy() = runBlocking {
        Fixture { _, _ -> }.use { fixture ->
            assertFailsWith<IOException> {
                fixture.transfers.remove(song().ref) { throw IOException("fixture") }
            }
            assertTrue(fixture.transfers.state.value.isEmpty())
        }
    }

    private fun song() = ContentItem(ContentRef("youtube", ContentKind.SONG, "seed"), "Song")

    private class Fixture(transfer: suspend (ContentItem, String) -> Unit) : AutoCloseable {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val reports = mutableListOf<String>()
        val transfers = DesktopDownloadTransfers(scope, transfer) { message -> reports += message }
        override fun close() { scope.cancel() }
    }
}
