package moe.rukamori.archivetune.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.settings.SpeedDialPin
import moe.rukamori.archivetune.shared.domain.settings.SpeedDialPinType

class DesktopItemActionsTest {
    @Test
    fun pinUpdatesAreIdempotentAndBounded() {
        val pins = (1..24).map { SpeedDialPin(SpeedDialPinType.SONG, "s$it") }
        assertEquals(25, desktopSongPins(pins, "new", true)!!.size)
        val full = (1..25).map { SpeedDialPin(SpeedDialPinType.SONG, "s$it") }
        assertNull(desktopSongPins(full, "new", true))
        assertEquals(full, desktopSongPins(full, "s1", true))
        assertEquals(24, desktopSongPins(full, "s1", false)!!.size)
        assertEquals(full, desktopSongPins(full, "missing", false))
    }

    @Test
    fun pinSupportIsLimitedToPlainYoutubeSongs() {
        assertTrue(ContentRef(ContentProvider.YOUTUBE.id, ContentKind.SONG, "abc").supportsDesktopSongPin())
        assertFalse(ContentRef(ContentProvider.YOUTUBE.id, ContentKind.EPISODE, "abc").supportsDesktopSongPin())
        assertFalse(ContentRef(ContentProvider.YOUTUBE.id, ContentKind.SONG, "telegram://1?x=2").supportsDesktopSongPin())
        assertFalse(ContentRef(ContentProvider.LOCAL.id, ContentKind.SONG, "/tmp/a").supportsDesktopSongPin())
    }

    @Test
    fun downloadIdsMatchTheOwnerFormat() {
        assertEquals("youtube:abc", ContentRef(ContentProvider.YOUTUBE.id, ContentKind.SONG, "abc").songDownloadId())
    }
}
