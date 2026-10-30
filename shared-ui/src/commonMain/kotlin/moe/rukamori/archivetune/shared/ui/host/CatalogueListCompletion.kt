package moe.rukamori.archivetune.shared.ui.host

import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistCompletion
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.TypedContentRepository

/** Songs and episodes only: albums, artists and playlists in a browse list open their own pages instead. */
internal fun playableRows(rows: List<ContentOccurrence>): List<ContentOccurrence> =
    rows.filter { it.item.ref.kind == ContentKind.SONG || it.item.ref.kind == ContentKind.EPISODE }

/**
 * The whole list from the rows a page holds and the continuation it stopped on. A repeated continuation, a list that
 * outgrows the request bound or a repeated occurrence id is incomplete and returns null, so it is never played.
 */
internal suspend fun completeCatalogueList(
    catalog: TypedContentRepository,
    collection: ContentRequest,
    held: List<ContentOccurrence>,
    heldContinuation: String?,
): List<ContentOccurrence>? {
    val rows = ArrayList<ContentOccurrence>()
    var next: String?
    if (held.isNotEmpty()) {
        rows += held
        next = heldContinuation
    } else {
        val first = catalog.page(collection, null)
        rows += first.items
        next = first.continuation
    }
    val consumed = HashSet<String>()
    var requests = 0
    while (next != null) {
        requests += 1
        if (requests > FinitePlaylistCompletion.MAX_REQUESTS || !consumed.add(next)) return null
        val page = catalog.page(collection, next)
        rows += page.items
        next = page.continuation
    }
    return rows.takeIf { list -> list.map { it.occurrenceId }.toSet().size == list.size }
}
