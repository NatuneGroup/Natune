package moe.rukamori.archivetune.shared.ui.host

import moe.rukamori.archivetune.shared.domain.localmedia.LocalTrackRecord
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.ui.screens.LocalSongUi

/**
 * The local-song rows a host page lists: the library's own `local` songs joined to the scan records by track id for
 * the album title and the file's modified time, which the no-metadata library row does not fully carry. The
 * occurrence id is the track's media id, which the scan derives from the file path, so a replayed row keeps the queue
 * entry and the file lookup it had. A row without a scan record is still listed; it sorts last under MODIFIED,
 * because its file time is unknown rather than invented.
 */
fun localSongRows(items: List<ContentItem>, records: List<LocalTrackRecord>): List<LocalSongUi> {
    val byId = records.associateBy { it.id }
    return items.mapNotNull { item ->
        val ref = item.ref
        if (ref.kind != ContentKind.SONG || ref.provider != ContentProvider.LOCAL.id) return@mapNotNull null
        val record = byId[ref.id]
        LocalSongUi(
            row = ContentOccurrence(ref.id, item),
            album = record?.album?.title ?: item.album.orEmpty(),
            modifiedEpochMs = record?.modifiedEpochMs ?: Long.MIN_VALUE,
        )
    }
}
