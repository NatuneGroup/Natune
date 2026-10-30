package moe.rukamori.archivetune.shared.ui.ios

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistCompletion
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.QueueStart
import moe.rukamori.archivetune.shared.domain.repository.TypedContentRepository
import moe.rukamori.archivetune.shared.ui.host.LibraryPageOwners
import moe.rukamori.archivetune.shared.ui.host.completeCatalogueList
import moe.rukamori.archivetune.shared.ui.host.playableRows

/**
 * The rows the iOS browse and artist-section pages showed, so a tap plays the list the listener saw, read to its end
 * within the shared finite-completion bounds first. The shelf feeds (Home, Charts, Moods, Releases, Discovery and the
 * remote artist page) have no shelf transport on this host, so only list pages record here.
 *
 * Main-thread only: pages record during composition and taps read the rows before any work leaves the thread.
 */
internal class IosCatalogueOwners(
    private val catalog: TypedContentRepository,
    private val owners: LibraryPageOwners,
) {
    // Insertion order is the age order, so the oldest page's rows are dropped first once the bound is reached.
    private val shownLists = LinkedHashMap<ContentRequest, List<ContentOccurrence>>()
    private val heldContinuations = LinkedHashMap<ContentRequest, String?>()

    fun record(collection: ContentRequest, rows: List<ContentOccurrence>, continuation: String?) {
        shownLists.remove(collection)
        shownLists[collection] = rows
        heldContinuations.remove(collection)
        heldContinuations[collection] = continuation
        while (shownLists.size > MAX_SHOWN_PAGES) shownLists.remove(shownLists.keys.first())
        while (heldContinuations.size > MAX_SHOWN_PAGES) heldContinuations.remove(heldContinuations.keys.first())
    }

    /** Plays a recorded list from the tapped row. Returns false when [collection] is not a recorded list page. */
    fun playOccurrence(collection: ContentRequest, start: QueueStart): Boolean {
        val held = shownLists[collection] ?: return false
        owners.scope.launch {
            val entries = try {
                withTimeoutOrNull(FinitePlaylistCompletion.TIMEOUT_MS) {
                    completeCatalogueList(catalog, collection, held, heldContinuations[collection])
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (entries.isNullOrEmpty()) {
                return@launch owners.report("the list could not be read to its end, so it was not played")
            }
            val playable = playableRows(entries)
            if (playable.isEmpty()) return@launch owners.report("the list holds no playable rows")
            val startId = playable.firstOrNull { it.occurrenceId == start.occurrenceId }?.occurrenceId
                ?: playable.firstOrNull { it.item.ref == start.item }?.occurrenceId
                ?: playable.first().occurrenceId
            try {
                owners.queue.replace(playable, startId, playWhenReady = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                owners.report("the list could not be played")
            }
        }
        return true
    }

    private companion object {
        /** A few pages back in the navigation stack; an older page reloads its first rows when it plays. */
        const val MAX_SHOWN_PAGES = 8
    }
}
