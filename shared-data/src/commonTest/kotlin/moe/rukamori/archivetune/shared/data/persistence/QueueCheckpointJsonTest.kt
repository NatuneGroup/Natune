package moe.rukamori.archivetune.shared.data.persistence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import moe.rukamori.archivetune.shared.domain.persistence.QueueCheckpoint
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.QueueState

class QueueCheckpointJsonTest {
    private val song = ContentItem(ContentRef("youtube", ContentKind.SONG, "id"), "Title")
    private val queue =
        QueueState(
            4L,
            listOf(ContentOccurrence("a", song), ContentOccurrence("b", song)),
            "a",
            listOf("a", "b"),
        )

    @Test
    fun theSaveTimestampRoundTrips() {
        val checkpoint = QueueCheckpoint(queue, 45_678L, savedAtEpochMs = 1_700_000_000_000L)
        assertEquals(checkpoint, QueueCheckpointJson.decode(QueueCheckpointJson.encode(checkpoint)))
    }

    @Test
    fun anUnstampedCheckpointOmitsTheFieldAndDecodesNull() {
        val checkpoint = QueueCheckpoint(queue, 45_678L)
        val encoded = QueueCheckpointJson.encode(checkpoint)
        // Checkpoints written before the stamp existed decode without one.
        assertEquals(-1, encoded.indexOf("savedAtEpochMs"))
        assertNull(QueueCheckpointJson.decode(encoded).savedAtEpochMs)
        assertEquals(checkpoint, QueueCheckpointJson.decode(encoded))
    }
}
