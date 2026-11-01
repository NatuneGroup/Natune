package moe.rukamori.archivetune.androidauto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Driving-playlist pins: JSON round-trips keep order, corrupt payloads decode to nothing, the
 * six-pin cap drops extras instead of evicting picks, and media ids survive song ids that
 * contain slashes.
 */
class AndroidAutoPinnedPlaylistsTest {
    private fun pin(
        id: String,
        kind: AndroidAutoPinnedPlaylistKind = AndroidAutoPinnedPlaylistKind.LOCAL,
    ) = AndroidAutoPinnedPlaylist(kind, id)

    @Test
    fun encodeDecode_roundTripsAndKeepsOrder() {
        val entries =
            listOf(
                pin("b", AndroidAutoPinnedPlaylistKind.ONLINE),
                pin("a"),
                pin("c"),
            )
        assertEquals(entries, AndroidAutoPinnedPlaylists.decode(AndroidAutoPinnedPlaylists.encode(entries)))
    }

    @Test
    fun decode_handlesNullBlankAndCorrupt() {
        assertTrue(AndroidAutoPinnedPlaylists.decode(null).isEmpty())
        assertTrue(AndroidAutoPinnedPlaylists.decode("").isEmpty())
        assertTrue(AndroidAutoPinnedPlaylists.decode("   ").isEmpty())
        assertTrue(AndroidAutoPinnedPlaylists.decode("not json").isEmpty())
        assertTrue(AndroidAutoPinnedPlaylists.decode("{}").isEmpty())
    }

    @Test
    fun decode_dropsBlankIdsButKeepsValidEntries() {
        val decoded =
            AndroidAutoPinnedPlaylists.decode(
                """[{"kind":"LOCAL","id":""},{"kind":"ONLINE","id":"PLabc"}]""",
            )
        assertEquals(listOf(pin("PLabc", AndroidAutoPinnedPlaylistKind.ONLINE)), decoded)
    }

    @Test
    fun toggle_addsAtEndAndRemovesPresent() {
        val start = listOf(pin("a"), pin("b"))
        assertEquals(listOf(pin("a"), pin("b"), pin("c")), AndroidAutoPinnedPlaylists.toggle(start, pin("c")))
        assertEquals(listOf(pin("b")), AndroidAutoPinnedPlaylists.toggle(start, pin("a")))
    }

    @Test
    fun toggle_neverExceedsCap() {
        val full = (1..AndroidAutoPinnedPlaylists.MAX_PINNED_PLAYLISTS).map { pin("p$it") }
        val result = AndroidAutoPinnedPlaylists.toggle(full, pin("extra"))
        assertEquals(AndroidAutoPinnedPlaylists.MAX_PINNED_PLAYLISTS, result.size)
        assertFalse(pin("extra") in result)
        assertTrue(pin("p1") in result)
        assertTrue(pin("p6") in result)
    }

    @Test
    fun toggle_stillRemovesWhenFull() {
        val full = (1..AndroidAutoPinnedPlaylists.MAX_PINNED_PLAYLISTS).map { pin("p$it") }
        val result = AndroidAutoPinnedPlaylists.toggle(full, pin("p1"))
        assertEquals((2..6).map { pin("p$it") }, result)
    }

    @Test
    fun sameIdWithDifferentKind_isDifferentPin() {
        val entries = listOf(pin("same"))
        assertFalse(AndroidAutoPinnedPlaylists.isPinned(entries, pin("same", AndroidAutoPinnedPlaylistKind.ONLINE)))
    }

    @Test
    fun mediaIdFor_usesKindSegment() {
        assertEquals(
            "pinned_playlists/local/PL1",
            AndroidAutoPinnedPlaylists.mediaIdFor(pin("PL1")),
        )
        assertEquals(
            "pinned_playlists/online/PL2",
            AndroidAutoPinnedPlaylists.mediaIdFor(pin("PL2", AndroidAutoPinnedPlaylistKind.ONLINE)),
        )
    }

    @Test
    fun parseMediaId_headerAndSong() {
        val header = AndroidAutoPinnedPlaylists.parseMediaId("pinned_playlists/local/PL1")
        assertEquals(AndroidAutoPinnedPlaylistKind.LOCAL, header?.kind)
        assertEquals("PL1", header?.playlistId)
        assertNull(header?.songMediaId)

        val song = AndroidAutoPinnedPlaylists.parseMediaId("pinned_playlists/online/PL2/song1")
        assertEquals(AndroidAutoPinnedPlaylistKind.ONLINE, song?.kind)
        assertEquals("PL2", song?.playlistId)
        assertEquals("song1", song?.songMediaId)
    }

    @Test
    fun parseMediaId_keepsSlashesInsideSongId() {
        val ref = AndroidAutoPinnedPlaylists.parseMediaId("pinned_playlists/local/PL1/deep/id/9")
        assertEquals("PL1", ref?.playlistId)
        assertEquals("deep/id/9", ref?.songMediaId)
    }

    @Test
    fun parseMediaId_rejectsUnknownKindsAndMalformedIds() {
        assertNull(AndroidAutoPinnedPlaylists.parseMediaId("pinned_playlists"))
        assertNull(AndroidAutoPinnedPlaylists.parseMediaId("pinned_playlists/"))
        assertNull(AndroidAutoPinnedPlaylists.parseMediaId("pinned_playlists/bogus/PL1"))
        assertNull(AndroidAutoPinnedPlaylists.parseMediaId("pinned_playlists/local/"))
        assertNull(AndroidAutoPinnedPlaylists.parseMediaId("queue/1/song"))
    }
}
