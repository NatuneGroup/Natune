package moe.rukamori.archivetune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoPlaybackStartTest {
    private data class Row(val id: String)

    private val rows = listOf(Row("a"), Row("b"), Row("c"))

    @Test
    fun aPresentSelectedIdStartsAtItsIndex() {
        assertEquals(0, autoBrowseStartIndex(rows, "a", Row::id))
        assertEquals(2, autoBrowseStartIndex(rows, "c", Row::id))
    }

    @Test
    fun aNullSelectedIdPlaysTheWholeListFromTheStart() {
        assertEquals(0, autoBrowseStartIndex(rows, null, Row::id))
        assertEquals(0, autoBrowseStartIndex(emptyList<Row>(), null, Row::id))
    }

    @Test
    fun aMissingSelectedIdIsNotFoundNotIndexZero() {
        // The browse list was a cached snapshot; the rebuilt queue no longer contains the tapped id.
        // Falling back to index 0 would play "a" instead of the tapped "gone" song.
        assertNull(autoBrowseStartIndex(rows, "gone", Row::id))
        assertNull(autoBrowseStartIndex(emptyList<Row>(), "a", Row::id))
    }
}
