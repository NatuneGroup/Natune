package moe.rukamori.archivetune.shared.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerInfoRoutesTest {
    @Test fun providerRoutesRetainLegacyPathsAndEncodeTheirIdentity() {
        assertEquals("track_info/song", ArchiveRoutes.trackInfo("song"))
        assertEquals("media_info/song", ArchiveRoutes.mediaInfo("song"))
        assertEquals("track_info/song?provider=spotify", ArchiveRoutes.trackInfo("song", "spotify"))
        assertEquals("media_info/a%2Fb?provider=source%2Fname", ArchiveRoutes.mediaInfo("a/b", "source/name"))
    }
}
