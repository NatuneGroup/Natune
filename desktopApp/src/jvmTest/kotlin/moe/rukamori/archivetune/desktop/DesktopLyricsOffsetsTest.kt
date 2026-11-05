package moe.rukamori.archivetune.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import moe.rukamori.archivetune.shared.domain.lyrics.MAX_LYRICS_SYNC_OFFSET_MS

/**
 * The per-song offset moved from the desktop session map to the portable store, so what this host still owns is the
 * value one adjustment writes: the stored offset plus the delta, held inside the shared ±30 s bound.
 * `PortableSongLyricsOffsetTest` pins the store's own contract (persistence, null means delete, bound refusal), and
 * nothing here claims the desktop UI's live observation, which needs a session graph.
 */
class DesktopLyricsOffsetsTest {
    @Test
    fun repeatedAdjustmentsStopAtTheSharedBound() {
        var offset: Long? = null
        repeat(400) { offset = adjustedLyricsOffset(offset, 100) }
        assertEquals(MAX_LYRICS_SYNC_OFFSET_MS, offset)
        repeat(700) { offset = adjustedLyricsOffset(offset, -100) }
        assertEquals(-MAX_LYRICS_SYNC_OFFSET_MS, offset)
    }

    @Test
    fun oneDeltaNeverExceedsTheBound() {
        assertEquals(MAX_LYRICS_SYNC_OFFSET_MS, adjustedLyricsOffset(null, 60_000))
        assertEquals(-MAX_LYRICS_SYNC_OFFSET_MS, adjustedLyricsOffset(MAX_LYRICS_SYNC_OFFSET_MS, -60_000))
    }

    @Test
    fun anAbsentOffsetReadsAsZero() {
        assertEquals(0L, adjustedLyricsOffset(null, 0))
        assertEquals(250L, adjustedLyricsOffset(null, 250))
        assertEquals(-250L, adjustedLyricsOffset(null, -250))
    }
}
