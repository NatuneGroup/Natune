package moe.rukamori.archivetune.playback

/**
 * Start index for an Android Auto "play from" request.
 *
 * A car taps a browse child and sends its media id back as [selectedMediaId]; the playback queue
 * is then rebuilt. The browse list is a cached snapshot (see [AutoBrowseChildrenCache]) while
 * the playback list is rebuilt from a fresh shuffle or network feed, so the tapped id is not
 * guaranteed to be present. Starting at index 0 in that case plays the wrong song.
 *
 * @return the start index, or `null` when [selectedMediaId] was requested but is absent from
 *   [items]. Callers must decline the request (log, no-op, or play by media id) instead of
 *   falling back to index 0. A `null` [selectedMediaId] means "play the whole list" and yields 0.
 */
internal fun <T> autoBrowseStartIndex(
    items: List<T>,
    selectedMediaId: String?,
    idOf: (T) -> String,
): Int? {
    if (selectedMediaId == null) return 0
    return items.indexOfFirst { idOf(it) == selectedMediaId }.takeIf { it >= 0 }
}
