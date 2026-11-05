package moe.rukamori.archivetune.shared.domain.recommend

import moe.rukamori.archivetune.shared.domain.repository.ContentCredit
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef

internal const val DAY_MS = 86_400_000L
internal const val TEST_NOW = 1_700_000_000_000L

internal fun testSong(
    id: String,
    artistId: String? = "artist-$id",
    albumId: String? = null,
    durationMs: Long? = 180_000L,
): ContentItem {
    val ref = ContentRef(ContentProvider.YOUTUBE.id, ContentKind.SONG, id)
    return ContentItem(
        ref = ref,
        title = "Song $id",
        durationMs = durationMs,
        artists = listOfNotNull(artistId?.let { ContentCredit("Artist $it", it) }),
        albumId = albumId,
    )
}

internal fun testOccurrence(item: ContentItem, occurrenceId: String = "${item.ref.id}-occurrence"): ContentOccurrence =
    ContentOccurrence(occurrenceId, item)

internal fun testFact(
    item: ContentItem,
    playedMs: Long = item.durationMs ?: 180_000L,
    atMs: Long = TEST_NOW,
    durationMs: Long? = item.durationMs,
    wasRecommended: Boolean = false,
    sourceArm: String? = null,
): ListenFact =
    ListenFact(
        ref = item.ref,
        artistIds = item.artists.mapNotNull { it.id },
        albumId = item.albumId,
        playedMs = playedMs,
        durationMs = durationMs?.takeIf { it > 0L },
        atMs = atMs,
        wasRecommended = wasRecommended,
        sourceArm = sourceArm,
    )

internal fun completedFact(
    item: ContentItem,
    atMs: Long = TEST_NOW,
    wasRecommended: Boolean = false,
): ListenFact = testFact(item, playedMs = 180_000L, durationMs = 180_000L, atMs = atMs, wasRecommended = wasRecommended)

internal fun quickSkipFact(item: ContentItem, atMs: Long = TEST_NOW): ListenFact =
    testFact(item, playedMs = 2_000L, durationMs = 180_000L, atMs = atMs)

internal fun partialFact(item: ContentItem, atMs: Long = TEST_NOW): ListenFact =
    testFact(item, playedMs = 60_000L, durationMs = 180_000L, atMs = atMs)

internal fun emptyProfile(
    nowMs: Long = TEST_NOW,
    context: ListeningContext = ListeningContext(12, false),
): TasteProfile = TasteProfile.build(emptyList(), nowMs, context) { context }

internal fun profileWith(
    facts: List<ListenFact>,
    nowMs: Long = TEST_NOW,
    context: ListeningContext = ListeningContext(12, false),
    contextOf: (ListenFact) -> ListeningContext = { context },
): TasteProfile = TasteProfile.build(facts, nowMs, context, contextOf)
