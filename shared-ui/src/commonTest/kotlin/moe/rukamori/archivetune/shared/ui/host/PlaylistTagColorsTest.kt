package moe.rukamori.archivetune.shared.ui.host

import androidx.compose.ui.graphics.Color
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaylistTagColorsTest {
    @Test fun storedHexRoundTripsThroughTheColour() {
        for (hex in listOf("#FF6B6B", "#264653", "#000000", "#FFFFFF", "#0A0B0C")) {
            assertEquals(hex, playlistTagHex(playlistTagColor(hex)))
        }
    }

    @Test fun unreadableValuesFallBackToTheDefaultTagColour() {
        val fallback = playlistTagColor("#FF6B6B")
        assertEquals(fallback, playlistTagColor(""))
        assertEquals(fallback, playlistTagColor("#12345"))
        assertEquals(fallback, playlistTagColor("#GGGGGG"))
        assertEquals(fallback, playlistTagColor("#FF6B6B00"))
    }

    @Test fun hexIsUppercaseAndPaddedAndIgnoresAlpha() {
        assertEquals("#00000A", playlistTagHex(Color(0x8000000A)))
        assertEquals("#ABCDEF", playlistTagHex(playlistTagColor("abcdef")))
    }

    @Test fun paletteKeepsTheBaselineOrderAndSize() {
        assertEquals(25, PlaylistTagPalette.size)
        assertEquals("#FF6B6B", playlistTagHex(PlaylistTagPalette.first()))
        assertEquals("#3498DB", playlistTagHex(PlaylistTagPalette.last()))
    }

    @Test fun localPlaylistIdsHaveTheBaselineShape() {
        val random = Random(7)
        repeat(50) {
            val id = randomLocalPlaylistId(random)
            assertEquals(10, id.length)
            assertTrue(id.startsWith("LP"))
            assertTrue(id.drop(2).all { it in 'a'..'z' || it in 'A'..'Z' }, id)
        }
    }
}
