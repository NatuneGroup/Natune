package moe.rukamori.archivetune.shared.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.playback.PlaybackOutputRoute
import moe.rukamori.archivetune.shared.domain.repository.*

class PlayerOccurrenceTest {
    private val first = ContentOccurrence("first", ContentItem(ContentRef("youtube", ContentKind.SONG, "same"), "First occurrence"))
    private val second = ContentOccurrence("second", first.item.copy(title = "Second occurrence"))
    private val queue = QueueState(1, listOf(first, second), "second", listOf("first", "second"))
    private fun snapshot(id: String) = PlaybackSnapshot(id, "same", 1, 0, 0, 0, null, true, 1f,
        true, true, false, PlaybackOutputRoute.LOCAL)

    @Test fun queueAdvanceDoesNotChangeThePlayingItemsActions() {
        assertEquals(first, playerOccurrence(queue, snapshot("first")))
        assertEquals(second, playerOccurrence(queue, snapshot("second")))
    }
    @Test fun missingPlaybackEntryNeverBorrowsAnotherOccurrencesMetadata() {
        assertNull(playerOccurrence(queue, snapshot("removed")))
        assertEquals(second, playerOccurrence(queue, null))
    }
}
