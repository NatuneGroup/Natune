package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.shared.data.provider.YouTubeArtistDetail
import moe.rukamori.archivetune.shared.data.provider.YouTubeCatalogueTransport
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistCompletion
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.QueueStart
import moe.rukamori.archivetune.shared.domain.repository.TypedContentRepository
import moe.rukamori.archivetune.shared.ui.host.LibraryPageOwners
import moe.rukamori.archivetune.shared.ui.host.completeCatalogueList
import moe.rukamori.archivetune.shared.ui.host.playableRows
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.ios_transports_list_empty
import moe.rukamori.archivetune.shared.ui.resources.ios_transports_list_not_played
import moe.rukamori.archivetune.shared.ui.resources.ios_transports_list_unreadable
import moe.rukamori.archivetune.shared.ui.resources.ios_transports_row_not_playable
import moe.rukamori.archivetune.shared.ui.resources.ios_transports_shelf_not_played
import org.jetbrains.compose.resources.getString

/**
 * The rows the iOS catalogue pages showed, so a tap plays the list or shelf the listener saw. A list page is read to
 * its end within the shared finite-completion bounds before it plays; a shelf row plays that shelf's own playable rows,
 * as the Android home and charts screens queue their shelves.
 *
 * Main-thread only: pages record during composition and taps read the rows before any work leaves the thread.
 */
internal class IosCatalogueOwners(
    private val catalog: TypedContentRepository,
    internal val youtubeCatalogue: YouTubeCatalogueTransport,
    internal val owners: LibraryPageOwners,
) {
    // Insertion order is the age order, so the oldest page's rows are dropped first once the bound is reached.
    private val shownLists = LinkedHashMap<ContentRequest, List<ContentOccurrence>>()
    private val heldContinuations = LinkedHashMap<ContentRequest, String?>()
    private val shownShelves = LinkedHashMap<ContentRequest, LinkedHashMap<String, List<ContentOccurrence>>>()
    private val artists = LinkedHashMap<ContentRef, IosArtistOwner>()

    fun artist(ref: ContentRef): IosArtistOwner =
        artists.getOrPut(ref) {
            while (artists.size >= MAX_SHOWN_PAGES) artists.remove(artists.keys.first())
            IosArtistOwner(this, ref)
        }

    fun record(collection: ContentRequest, rows: List<ContentOccurrence>, continuation: String?) {
        shownLists.remove(collection)
        shownLists[collection] = rows
        heldContinuations.remove(collection)
        heldContinuations[collection] = continuation
        while (shownLists.size > MAX_SHOWN_PAGES) shownLists.remove(shownLists.keys.first())
        while (heldContinuations.size > MAX_SHOWN_PAGES) heldContinuations.remove(heldContinuations.keys.first())
    }

    /** One shelf of a feed page. A shelf keeps its own row order, apart from the rest of the feed. */
    fun recordShelf(collection: ContentRequest, shelfKey: String, rows: List<ContentOccurrence>) {
        val shelves = shownShelves.getOrPut(collection) { LinkedHashMap() }
        shelves.remove(shelfKey)
        shelves[shelfKey] = rows
        shownShelves.remove(collection)
        shownShelves[collection] = shelves
        while (shownShelves.size > MAX_SHOWN_PAGES) shownShelves.remove(shownShelves.keys.first())
    }

    /** Plays a recorded list or shelf row. Returns false when [collection] is not a recorded catalogue page. */
    fun playOccurrence(collection: ContentRequest, start: QueueStart): Boolean {
        val shelfRows =
            shownShelves[collection]?.values?.firstOrNull { shelf -> shelf.any { it.occurrenceId == start.occurrenceId } }
        if (shelfRows != null) {
            owners.scope.launch {
                val playable = playableRows(shelfRows)
                val startId = playable.firstOrNull { it.occurrenceId == start.occurrenceId }?.occurrenceId
                if (startId == null) {
                    owners.report(getString(Res.string.ios_transports_row_not_playable))
                    return@launch
                }
                try {
                    owners.queue.replace(playable, startId, playWhenReady = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    owners.report(getString(Res.string.ios_transports_shelf_not_played))
                }
            }
            return true
        }
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
                return@launch owners.report(getString(Res.string.ios_transports_list_unreadable))
            }
            val playable = playableRows(entries)
            if (playable.isEmpty()) return@launch owners.report(getString(Res.string.ios_transports_list_empty))
            val startId = playable.firstOrNull { it.occurrenceId == start.occurrenceId }?.occurrenceId
                ?: playable.firstOrNull { it.item.ref == start.item }?.occurrenceId
                ?: playable.first().occurrenceId
            try {
                owners.queue.replace(playable, startId, playWhenReady = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                owners.report(getString(Res.string.ios_transports_list_not_played))
            }
        }
        return true
    }

    private companion object {
        /** A few pages back in the navigation stack; an older page reloads its first rows when it plays. */
        const val MAX_SHOWN_PAGES = 8
    }
}

/**
 * The remote artist page behind the artist routes. [load] re-requests the provider page; a failure keeps the last
 * good page and raises [failed] only while nothing has loaded, matching the Android screen's load-state gate.
 */
internal class IosArtistOwner(
    private val owners: IosCatalogueOwners,
    val ref: ContentRef,
) {
    var page by mutableStateOf<YouTubeArtistDetail?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var failed by mutableStateOf(false)
        private set

    private var generation = 0

    fun load() {
        val gen = ++generation
        loading = true
        owners.owners.scope.launch {
            try {
                val detail = owners.youtubeCatalogue.artist(ref.id)
                if (gen != generation) return@launch
                page = detail
                failed = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (gen == generation) {
                    failed = page == null
                    owners.owners.report("artist page could not be loaded: ${failure::class.simpleName}")
                }
            } finally {
                if (gen == generation) loading = false
            }
        }
    }
}
