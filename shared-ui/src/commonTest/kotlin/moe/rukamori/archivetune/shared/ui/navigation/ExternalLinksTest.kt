package moe.rukamori.archivetune.shared.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExternalLinksTest {
    @Test fun appOpenLinksCarryTheRouteVerbatim() {
        assertEquals(ExternalLink.Route("library"), ExternalLinks.classify("archivetune://open/library"))
        assertEquals(
            ExternalLink.Route("search/abc%20def?provider=YOUTUBE"),
            ExternalLinks.classify("archivetune://open/search/abc%20def?provider=YOUTUBE"),
        )
        assertEquals(ExternalLink.Route("music_recognition?autoStart=7"),
            ExternalLinks.classify("archivetune://open/music_recognition?autoStart=7"))
        assertNull(ExternalLinks.classify("archivetune://open"))
        assertNull(ExternalLinks.classify("archivetune://open/"))
    }

    @Test fun loginAndForeignSchemeLinksKeepTheirClassification() {
        val login = ExternalLinks.classify("archivetune://login?url=https%3A%2F%2Fexample.test%2Fcb")
        assertEquals(ExternalLink.Login("https://example.test/cb"), login)
        assertEquals(ExternalLink.Login(null), ExternalLinks.classify("archivetune://login"))
        // Another host under the scheme names no page and joins no room.
        assertNull(ExternalLinks.classify("archivetune://arbitrary/library"))
        assertNull(ExternalLinks.classify("not a link"))
        assertNull(ExternalLinks.classify(""))
    }

    @Test fun listenInvitesCarryTheRoomCodeLikeTheAndroidHost() {
        assertEquals(ExternalLink.ListenTogetherInvite("abcd12"),
            ExternalLinks.classify("archivetune://listen?code=abcd12"))
        assertEquals(ExternalLink.ListenTogetherInvite("abcd12"),
            ExternalLinks.classify("archivetune://listen?room=abcd12"))
        assertEquals(ExternalLink.ListenTogetherInvite("ABCD12"),
            ExternalLinks.classify("https://devilmi-vivi-music-listen-together.hf.space/listen/ABCD12"))
        // The retired onrender host is still an invite host; it is never contacted.
        assertEquals(ExternalLink.ListenTogetherInvite("ABCD12"),
            ExternalLinks.classify("https://vivimusic-listen-together.onrender.com/listen?code=ABCD12"))
        // Any /listen path counts on the Android handler, not only the two invite hosts.
        assertEquals(ExternalLink.ListenTogetherInvite("ABCD12"),
            ExternalLinks.classify("https://unrelated.example/listen?code=ABCD12"))
        // A codeless listen link opens no room, and an invite host without a code opens nothing.
        assertNull(ExternalLinks.classify("archivetune://listen"))
        assertNull(ExternalLinks.classify("https://devilmi-vivi-music-listen-together.hf.space/other"))
        // A page link under open stays a page link even when its path spells listen.
        assertEquals(ExternalLink.Route("track/123"), ExternalLinks.classify("archivetune://open/track/123"))
        assertEquals(ExternalLink.Route("listen?code=ABCD12"),
            ExternalLinks.classify("archivetune://open/listen?code=ABCD12"))
    }

    @Test fun youtubeLinksMapLikeTheAndroidIntentTable() {
        assertEquals(ExternalLink.YouTubeWatch("vid123", null, false),
            ExternalLinks.classify("https://music.youtube.com/watch?v=vid123"))
        assertEquals(ExternalLink.YouTubeWatch("vid123", "PL9x", true),
            ExternalLinks.classify("https://www.youtube.com/watch?v=vid123&list=PL9x&shuffle=1"))
        assertEquals(ExternalLink.YouTubeWatch("vid123", null, false),
            ExternalLinks.classify("https://youtu.be/vid123"))
        assertEquals(ExternalLink.YouTubeWatch("vid123", null, false),
            ExternalLinks.classify("vnd.youtube://vid123"))
        assertEquals(ExternalLink.Route("online_playlist/OLAK5uy_x"),
            ExternalLinks.classify("https://music.youtube.com/playlist?list=OLAK5uy_x"))
        assertEquals(ExternalLink.Route("online_playlist/PL9x"),
            ExternalLinks.classify("https://www.youtube.com/watch?list=PL9x"))
        assertEquals(ExternalLink.Route("album/MPREx"),
            ExternalLinks.classify("https://www.youtube.com/browse/MPREx"))
        assertEquals(ExternalLink.Route("artist/UCxyz"),
            ExternalLinks.classify("https://www.youtube.com/channel/UCxyz"))
        assertEquals(ExternalLink.Route("artist/name"),
            ExternalLinks.classify("https://www.youtube.com/c/name"))
        // Hosts outside the registered set get no YouTube table, and an unmapped path stays unmapped.
        assertNull(ExternalLinks.classify("https://example.test/watch?v=vid123"))
        assertNull(ExternalLinks.classify("https://www.youtube.com/feed/subscriptions"))
        assertNull(ExternalLinks.classify("https://www.youtube.com/shorts/vid123"))
    }

    @Test fun spotifyAndTelegramLinksMapOntoTheRouteVocabulary() {
        assertEquals(ExternalLink.Route("spotify_playlist/37i9dQ?provider=spotify"),
            ExternalLinks.classify("https://open.spotify.com/playlist/37i9dQ"))
        assertEquals(ExternalLink.Route("album/4LH4?provider=spotify"),
            ExternalLinks.classify("https://open.spotify.com/album/4LH4"))
        assertEquals(ExternalLink.Route("artist/0TnO?provider=spotify"),
            ExternalLinks.classify("https://open.spotify.com/artist/0TnO"))
        assertEquals(ExternalLink.Route("spotify_playlist/37i9dQ?provider=spotify"),
            ExternalLinks.classify("https://open.spotify.com/intl-de/playlist/37i9dQ"))
        // A Spotify track, show or episode names no route, the no-op Android gives every Spotify link.
        assertNull(ExternalLinks.classify("https://open.spotify.com/track/4uLU"))
        assertNull(ExternalLinks.classify("https://open.spotify.com/show/4rOo"))
        assertEquals(ExternalLink.Route("telegram/browse"),
            ExternalLinks.classify("https://t.me/ArchiveTuneGC"))
        assertEquals(ExternalLink.Route("telegram/browse"),
            ExternalLinks.classify("https://telegram.me/ArchiveTuneGC"))
        assertNull(ExternalLinks.classify("https://t.me/"))
    }

    @Test fun openableRouteKeepsTheSigninAndActionRefusal() {
        assertEquals("library", ExternalLinks.openableRoute("library"))
        assertEquals("music_recognition", ExternalLinks.openableRoute("music_recognition"))
        assertNull(ExternalLinks.openableRoute("login"))
        assertNull(ExternalLinks.openableRoute("login?url=x"))
        assertNull(ExternalLinks.openableRoute("music_recognition?autoStart=7"))
        assertNull(ExternalLinks.openableRoute("not_a_route"))
    }
}
