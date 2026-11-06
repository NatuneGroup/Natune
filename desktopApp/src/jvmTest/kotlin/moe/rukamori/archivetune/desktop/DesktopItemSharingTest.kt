package moe.rukamori.archivetune.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef

class DesktopItemSharingTest {
    private fun ref(provider: ContentProvider, kind: ContentKind, id: String) = ContentRef(provider.id, kind, id)

    @Test
    fun catalogIdentityChoosesTheLink() {
        assertEquals("https://music.youtube.com/watch?v=abc", desktopItemShareUri(ref(ContentProvider.YOUTUBE, ContentKind.SONG, "abc")))
        assertEquals("https://music.youtube.com/watch?v=ep1", desktopItemShareUri(ref(ContentProvider.YOUTUBE, ContentKind.EPISODE, "ep1")))
        assertEquals("https://music.youtube.com/channel/UCx", desktopItemShareUri(ref(ContentProvider.YOUTUBE, ContentKind.ARTIST, "UCx")))
        assertEquals("https://music.youtube.com/playlist?list=PL1", desktopItemShareUri(ref(ContentProvider.YOUTUBE, ContentKind.PLAYLIST, "PL1")))
        assertEquals("https://music.youtube.com/browse/MPREa", desktopItemShareUri(ref(ContentProvider.YOUTUBE, ContentKind.ALBUM, "MPREa")))
        assertEquals("https://open.spotify.com/track/xyz", desktopItemShareUri(ref(ContentProvider.SPOTIFY_CATALOG, ContentKind.SONG, "xyz")))
        assertEquals("https://open.spotify.com/show/s1", desktopItemShareUri(ref(ContentProvider.SPOTIFY_CATALOG, ContentKind.PODCAST, "s1")))
        assertEquals("https://open.spotify.com/episode/e1", desktopItemShareUri(ref(ContentProvider.SPOTIFY_CATALOG, ContentKind.EPISODE, "e1")))
    }

    @Test
    fun rowsOutsideTheTwoCatalogsNeverGetALink() {
        assertNull(desktopItemShareUri(ref(ContentProvider.YOUTUBE, ContentKind.SONG, "telegram://1?chat=2")))
        assertNull(desktopItemShareUri(ref(ContentProvider.TELEGRAM, ContentKind.SONG, "1_2")))
        assertNull(desktopItemShareUri(ref(ContentProvider.LOCAL, ContentKind.SONG, "/tmp/a.mp3")))
        assertNull(desktopItemShareUri(ref(ContentProvider.TIDAL, ContentKind.SONG, "123")))
    }

    @Test
    fun idsAreUrlEncoded() {
        assertEquals("https://music.youtube.com/watch?v=a%20b", desktopItemShareUri(ref(ContentProvider.YOUTUBE, ContentKind.SONG, "a b")))
    }

    @Test
    fun thePlayerShareCopiesTheCatalogueLinkAndSaysSo() {
        val notices = mutableListOf<String>()
        val copied = mutableListOf<String>()
        shareDesktopSong(ref(ContentProvider.YOUTUBE, ContentKind.SONG, "abc"), notices::add) { uri ->
            copied += uri
            true
        }
        assertEquals(listOf("https://music.youtube.com/watch?v=abc"), copied)
        assertEquals(listOf(DesktopStrings["link_copied"]), notices)
    }

    @Test
    fun thePlayerShareOfALocalFileCopiesNothingAndSaysWhy() {
        val notices = mutableListOf<String>()
        var copies = 0
        shareDesktopSong(ref(ContentProvider.LOCAL, ContentKind.SONG, "/tmp/a.mp3"), notices::add) {
            copies++
            true
        }
        assertEquals(0, copies)
        assertEquals(listOf(DesktopStrings["share_link_unavailable"]), notices)
    }

    @Test
    fun aRefusedClipboardIsReportedInsteadOfACopiedLink() {
        val notices = mutableListOf<String>()
        shareDesktopSong(ref(ContentProvider.YOUTUBE, ContentKind.SONG, "abc"), notices::add) { false }
        assertEquals(listOf(DesktopStrings["clipboard_unavailable"]), notices)
    }
}
