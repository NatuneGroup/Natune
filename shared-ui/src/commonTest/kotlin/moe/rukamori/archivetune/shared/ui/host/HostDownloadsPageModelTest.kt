package moe.rukamori.archivetune.shared.ui.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.repository.ContentDownload
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.DownloadPhase
import moe.rukamori.archivetune.shared.ui.screens.DownloadLibraryTab

class HostDownloadsPageModelTest {
    private val first = ref("tidal", "100")
    private val second = ref("qobuz", "100")
    private val firstItem = ContentItem(first, "First")
    private val secondItem = ContentItem(second, "Second")

    @Test fun completedTransfersListUnderTheDownloadedTabWithTheirOwnId() {
        val inputs = inputs(listOf(download(first, DownloadPhase.COMPLETED, total = 900L)))
        val page = inputs.page(DownloadLibraryTab.DOWNLOADED, "")
        val entries = page.downloaded.single().entries
        assertEquals(1, entries.size)
        assertEquals("tidal:100", entries.single().id)
        assertEquals(listOf("tidal:100"), entries.single().songIds)
        assertTrue(page.progress.isEmpty())
    }

    @Test fun twoProvidersNamingOneMediaIdStayDistinctEntries() {
        val page = inputs(
            listOf(
                download(first, DownloadPhase.COMPLETED, total = 1L),
                download(second, DownloadPhase.COMPLETED, total = 2L),
            ),
        ).page(DownloadLibraryTab.DOWNLOADED, "")
        assertEquals(listOf("tidal:100", "qobuz:100"), page.downloaded.single().entries.map { it.id })
    }

    @Test fun aRunningTransferReportsProgressAndBlocksItsSection() {
        val page = inputs(listOf(download(first, DownloadPhase.DOWNLOADING, done = 250L, total = 1000L)))
            .page(DownloadLibraryTab.PROGRESS, "")
        val entry = page.progress.single().entries.single()
        assertEquals(0.25f, entry.progress)
        assertEquals(setOf("tidal:100"), page.busySongIds)
        assertTrue(page.downloaded.isEmpty())
    }

    @Test fun anUnknownSizeLeavesProgressUnknownRatherThanZero() {
        val page = inputs(listOf(download(first, DownloadPhase.DOWNLOADING, done = 5L, total = null)))
            .page(DownloadLibraryTab.PROGRESS, "")
        assertNull(page.progress.single().entries.single().progress)
    }

    @Test fun stoppedAndFailedTransfersKeepTheirPhaseForTheButtonThatActsOnThem() {
        val page = inputs(
            listOf(
                download(first, DownloadPhase.STOPPED, done = 5L, total = 10L),
                download(second, DownloadPhase.FAILED, done = 0L, total = 10L),
            ),
        ).page(DownloadLibraryTab.PROGRESS, "")
        val entries = page.progress.single().entries
        assertTrue(entries.first { it.id == "tidal:100" }.paused)
        assertTrue(entries.first { it.id == "qobuz:100" }.failed)
        assertTrue(page.busySongIds.isEmpty())
    }

    @Test fun aTransferWithoutMetadataIsLeftOutRatherThanTitledByGuess() {
        val page = inputs(listOf(download(first, DownloadPhase.COMPLETED, total = 1L)), emptyList())
            .page(DownloadLibraryTab.DOWNLOADED, "")
        assertTrue(page.downloaded.isEmpty())
    }

    @Test fun theQueueOfDownloadedSongsKeepsTheOwnerOrder() {
        val inputs = inputs(
            listOf(
                download(first, DownloadPhase.COMPLETED, total = 1L),
                download(second, DownloadPhase.COMPLETED, total = 2L),
                download(second, DownloadPhase.FAILED, total = 3L),
            ),
        )
        assertEquals(listOf("download:tidal:100", "download:qobuz:100"), inputs.downloadedOccurrences().map { it.occurrenceId })
    }

    @Test fun anEntryIdNamesItsProviderAndMediaIdOrNothing() {
        assertEquals(first, downloadEntryRef("tidal:100"))
        assertNull(downloadEntryRef("tidal"))
        assertNull(downloadEntryRef("tidal:"))
        assertNull(downloadEntryRef(":100"))
    }

    private fun inputs(transfers: List<ContentDownload>, items: List<ContentItem>? = null) =
        HostDownloadsInputs(transfers, items ?: listOf(firstItem, secondItem))

    private fun download(
        ref: ContentRef,
        phase: DownloadPhase,
        done: Long = 0L,
        total: Long? = null,
    ) = ContentDownload(ref.provider + ":" + ref.id, ref, phase, done, total)

    private fun ref(provider: String, id: String) = ContentRef(provider, ContentKind.SONG, id)
}
