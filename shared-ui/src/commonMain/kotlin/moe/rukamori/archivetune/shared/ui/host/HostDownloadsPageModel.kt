package moe.rukamori.archivetune.shared.ui.host

import moe.rukamori.archivetune.shared.domain.repository.ContentDownload
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.DownloadPhase
import moe.rukamori.archivetune.shared.ui.screens.DownloadEntryUi
import moe.rukamori.archivetune.shared.ui.screens.DownloadLibraryTab
import moe.rukamori.archivetune.shared.ui.screens.DownloadMediaType
import moe.rukamori.archivetune.shared.ui.screens.DownloadSectionUi
import moe.rukamori.archivetune.shared.ui.screens.DownloadsUi

/**
 * The downloads page state from the owner's live transfer list and the songs whose metadata is known. An entry is
 * addressed by the provider-qualified id the owner minted, so pause, resume and remove act on exactly that row, and
 * a transfer whose song metadata is unknown is left out rather than shown under an invented title.
 *
 * A running transfer reports its share of the declared size; a stopped or failed one keeps its last progress, so the
 * button that acts on it reads the phase, never a guess. An in-flight transfer is listed as busy, so the section's
 * buttons stay disabled while its row is already being acted on.
 */
fun hostDownloadsUi(
    transfers: List<ContentDownload>,
    items: List<ContentItem>,
    tab: DownloadLibraryTab,
    query: String,
): DownloadsUi {
    val itemsByRef = items.associateBy { it.ref }
    fun entryOf(transfer: ContentDownload, progress: Float?): DownloadEntryUi? {
        val item = itemsByRef[transfer.ref] ?: return null
        return DownloadEntryUi(
            id = transfer.id,
            item = item,
            songIds = listOf(transfer.id),
            progress = progress,
            // The owner re-queues a failed transfer the same way as a stopped one, so the button resumes it; the
            // status line still names the failure from `failed`, which the screen checks first.
            paused = transfer.phase == DownloadPhase.STOPPED || transfer.phase == DownloadPhase.FAILED,
            failed = transfer.phase == DownloadPhase.FAILED,
        )
    }
    val downloaded = transfers.filter { it.phase == DownloadPhase.COMPLETED }
        .mapNotNull { entryOf(it, progress = null) }
    val active = transfers.filter { it.phase != DownloadPhase.COMPLETED }.mapNotNull { transfer ->
        val declared = transfer.totalBytes?.takeIf { it > 0L }
        val share = declared?.let { (transfer.bytesDownloaded.toFloat() / it).coerceIn(0f, 1f) }
        entryOf(transfer, share)
    }
    return DownloadsUi(
        tab = tab,
        query = query,
        downloaded = downloaded.sectionsOrEmpty(),
        progress = active.sectionsOrEmpty(),
        busySongIds = transfers
            .filter { it.phase == DownloadPhase.QUEUED || it.phase == DownloadPhase.DOWNLOADING || it.phase == DownloadPhase.REMOVING }
            .mapTo(HashSet()) { it.id },
    )
}

/** The one song section, or no section at all, so the page never draws an empty heading. */
private fun List<DownloadEntryUi>.sectionsOrEmpty(): List<DownloadSectionUi> =
    if (isEmpty()) emptyList() else listOf(DownloadSectionUi(DownloadMediaType.SONG, this))

/** The downloads page's own inputs, kept together so a host page reads one snapshot per recomposition. */
data class HostDownloadsInputs(val transfers: List<ContentDownload>, val items: List<ContentItem>) {
    fun page(tab: DownloadLibraryTab, query: String): DownloadsUi = hostDownloadsUi(transfers, items, tab, query)

    /** The playable queue of the downloaded songs, in the order the owner lists them. */
    fun downloadedOccurrences(): List<ContentOccurrence> {
        val itemsByRef = items.associateBy(ContentItem::ref)
        return transfers.filter { it.phase == DownloadPhase.COMPLETED }.mapNotNull { transfer ->
            itemsByRef[transfer.ref]?.let { item -> ContentOccurrence("download:" + transfer.id, item) }
        }
    }
}

/** The stored ref of a download entry, as the page's own song id names it. */
fun downloadEntryRef(id: String): ContentRef? {
    val separator = id.indexOf(':')
    if (separator <= 0 || separator == id.lastIndex) return null
    return ContentRef(provider = id.substring(0, separator), kind = ContentKind.SONG, id = id.substring(separator + 1))
}
