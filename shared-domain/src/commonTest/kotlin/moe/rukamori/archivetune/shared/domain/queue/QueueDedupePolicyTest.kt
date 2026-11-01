package moe.rukamori.archivetune.shared.domain.queue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.QueueState

class QueueDedupePolicyTest {
    @Test
    fun incomingKeepsFirstOccurrencePerMediaReferenceAndNeverTheCurrentSong() {
        val queue = queueOf(listOf("a" to "songA", "b" to "songB", "c" to "songC"), current = "a")
        val incoming = listOf(
            occurrence("i1", "songA"),
            occurrence("i2", "songB"),
            occurrence("i3", "songB"),
            occurrence("i4", "songD"),
        )
        assertEquals(listOf("i2", "i4"), QueueDedupePolicy.incoming(queue, incoming).map { it.occurrenceId })
    }

    @Test
    fun evictableDropsEveryEarlierOccurrenceExceptTheCurrentOne() {
        val queue = queueOf(listOf("a" to "songA", "b" to "songB", "c" to "songB", "d" to "songD"), current = "b")
        val incoming = listOf(occurrence("i1", "songB"))
        assertEquals(setOf("c"), QueueDedupePolicy.evictable(queue, incoming))
    }

    @Test
    fun evictRemovesTimelineOrderAndKindsTogether() {
        val queue = queueOf(listOf("a" to "songA", "b" to "songB", "c" to "songC"), current = "a")
            .copy(manualKinds = mapOf("b" to UserQueuedKind.PLAY_NEXT))
        val after = QueueDedupePolicy.evict(queue, setOf("b"))
        assertEquals(listOf("a", "c"), after.entries.map { it.occurrenceId })
        assertEquals(listOf("a", "c"), after.playOrder)
        assertEquals(emptyMap(), after.manualKinds)
        assertEquals("a", after.currentOccurrenceId)
    }

    @Test
    fun aShuffledPlayOrderDedupesAndEvictsByMediaReference() {
        val queue = queueOf(listOf("a" to "songA", "b" to "songB", "c" to "songC"), current = "b")
            .copy(playOrder = listOf("c", "b", "a"), shuffleEnabled = true)
        val incoming = listOf(
            occurrence("i1", "songC"),
            occurrence("i2", "songD"),
            occurrence("i3", "songC"),
        )
        // Identity rules ignore play order: the first incoming copy survives and so does the current song.
        assertEquals(listOf("i1", "i2"), QueueDedupePolicy.incoming(queue, incoming).map { it.occurrenceId })
        assertEquals(setOf("c"), QueueDedupePolicy.evictable(queue, incoming))
        val after = QueueDedupePolicy.evict(queue, setOf("c"))
        assertEquals(listOf("a", "b"), after.entries.map { it.occurrenceId })
        assertEquals(listOf("b", "a"), after.playOrder)
        assertEquals("b", after.currentOccurrenceId)
        assertEquals(true, after.shuffleEnabled)
    }

    @Test
    fun anEmptyQueueStillDedupesTheBatch() {
        val queue = QueueState(0L, emptyList(), null, emptyList())
        val incoming = listOf(occurrence("i1", "songA"), occurrence("i2", "songA"), occurrence("i3", "songB"))
        assertEquals(listOf("i1", "i3"), QueueDedupePolicy.incoming(queue, incoming).map { it.occurrenceId })
        assertEquals(emptySet(), QueueDedupePolicy.evictable(queue, incoming))
        assertNull(QueueDedupePolicy.currentRef(queue))
    }

    @Test
    fun theSameIdFromAnotherProviderIsNotADuplicate() {
        val queue = queueOf(listOf("a" to "songA"), current = "a", provider = "youtube")
        val tidal = listOf(
            ContentOccurrence("t1", ContentItem(ContentRef("tidal", ContentKind.SONG, "songA"), "Song")),
        )
        assertEquals(listOf("t1"), QueueDedupePolicy.incoming(queue, tidal).map { it.occurrenceId })
        assertEquals(emptySet(), QueueDedupePolicy.evictable(queue, tidal))
    }

    private fun occurrence(occurrenceId: String, mediaId: String) =
        ContentOccurrence(occurrenceId, ContentItem(ContentRef("youtube", ContentKind.SONG, mediaId), "Song"))

    private fun queueOf(pairs: List<Pair<String, String>>, current: String?, provider: String = "youtube") =
        QueueState(
            revision = 0L,
            entries = pairs.map { (id, media) ->
                ContentOccurrence(id, ContentItem(ContentRef(provider, ContentKind.SONG, media), "Song"))
            },
            currentOccurrenceId = current,
            playOrder = pairs.map { it.first },
        )
}
