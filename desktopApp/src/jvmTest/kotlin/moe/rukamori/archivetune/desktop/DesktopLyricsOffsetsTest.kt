package moe.rukamori.archivetune.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentRef

class DesktopLyricsOffsetsTest {
    private fun ref(id: String) = ContentRef("youtube", ContentKind.SONG, id)

    @Test
    fun adjustmentsClampToTheSessionBound() {
        val offsets = DesktopLyricsOffsets()
        repeat(400) { offsets.adjust(ref("a"), 100) }
        assertEquals(30_000L, offsets.value(ref("a")))
        repeat(700) { offsets.adjust(ref("a"), -100) }
        assertEquals(-30_000L, offsets.value(ref("a")))
        offsets.reset(ref("a"))
        assertEquals(0L, offsets.value(ref("a")))
    }

    @Test
    fun oneDeltaNeverExceedsTheBound() {
        val offsets = DesktopLyricsOffsets()
        offsets.adjust(ref("a"), 60_000)
        assertEquals(30_000L, offsets.value(ref("a")))
        offsets.adjust(ref("a"), -60_000)
        assertEquals(0L, offsets.value(ref("a")))
    }

    @Test
    fun theOldestSongEvictsAtTheCap() {
        val offsets = DesktopLyricsOffsets()
        (1..128).forEach { offsets.adjust(ref("s$it"), 5) }
        offsets.adjust(ref("overflow"), 5)
        assertEquals(0L, offsets.value(ref("s1")))
        assertEquals(5L, offsets.value(ref("s2")))
        assertEquals(5L, offsets.value(ref("overflow")))
        // Adjusting a song already tracked never evicts another one.
        repeat(3) { offsets.adjust(ref("s128"), 1) }
        assertEquals(8L, offsets.value(ref("s128")))
        assertEquals(5L, offsets.value(ref("s2")))
    }

    @Test
    fun nullReadsAsZero() {
        assertEquals(0L, DesktopLyricsOffsets().value(null))
    }
}
