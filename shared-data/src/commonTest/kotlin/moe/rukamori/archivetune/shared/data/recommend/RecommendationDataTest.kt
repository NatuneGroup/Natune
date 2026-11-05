package moe.rukamori.archivetune.shared.data.recommend

import moe.rukamori.archivetune.shared.data.persistence.ContentJson
import moe.rukamori.archivetune.shared.data.persistence.PortableListenEventRow
import moe.rukamori.archivetune.shared.domain.repository.ContentCredit
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef

internal const val DATA_TEST_NOW = 1_700_000_000_000L

internal fun dataSong(
    id: String,
    artistId: String? = "artist-$id",
    albumId: String? = null,
    durationMs: Long? = 180_000L,
    kind: ContentKind = ContentKind.SONG,
): ContentItem =
    ContentItem(
        ref = ContentRef(ContentProvider.YOUTUBE.id, kind, id),
        title = "Song $id",
        durationMs = durationMs,
        artists = listOfNotNull(artistId?.let { ContentCredit("Artist $it", it) }),
        albumId = albumId,
    )

internal fun dataOccurrence(item: ContentItem, occurrenceId: String = "${item.ref.id}-occurrence") =
    ContentOccurrence(occurrenceId, item)

internal fun dataRow(
    item: ContentItem,
    playedMs: Long,
    timestampMs: Long = DATA_TEST_NOW,
    occurrenceId: String = "${item.ref.id}-occurrence",
): PortableListenEventRow =
    PortableListenEventRow(
        id = "event-${item.ref.id}-$playedMs-$timestampMs",
        occurrenceJson = ContentJson.encode(ContentOccurrence(occurrenceId, item)).toString(),
        songKey = item.ref.id,
        artistName = item.subtitle,
        albumName = item.album,
        timestampMs = timestampMs,
        playTimeMs = playedMs,
    )

internal fun malformedRow(playedMs: Long = 60_000L): PortableListenEventRow =
    PortableListenEventRow(
        id = "event-malformed",
        occurrenceJson = "{not json",
        songKey = "broken",
        artistName = "",
        albumName = null,
        timestampMs = DATA_TEST_NOW,
        playTimeMs = playedMs,
    )
