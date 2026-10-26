package moe.rukamori.archivetune.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentRef

class DesktopCrossfadeHandoffTest {
    private fun song(id: String, albumId: String? = null, album: String? = null, kind: ContentKind = ContentKind.SONG) =
        ContentItem(ContentRef("youtube", kind, id), id, album = album, albumId = albumId)

    private val crossfade = DesktopHandoffSettings(crossfade = true, automix = false, durationSeconds = 5f, gapless = true)

    @Test
    fun offSettingsLetTheSongEnd() {
        val off = crossfade.copy(crossfade = false)
        assertNull(desktopHandoffOverlapMs(off, song("a"), song("b"), 200_000L))
    }

    @Test
    fun crossfadeUsesTheConfiguredLength() {
        assertEquals(5_000L, desktopHandoffOverlapMs(crossfade, song("a"), song("b"), 200_000L))
    }

    @Test
    fun crossfadeIsClampedToTheTrackLessTheEndGuard() {
        assertEquals(2_850L, desktopHandoffOverlapMs(crossfade, song("a"), song("b"), 3_000L))
        assertNull(desktopHandoffOverlapMs(crossfade, song("a"), song("b"), 600L))
    }

    @Test
    fun gaplessSplicesOneAlbumByIdOrTitle() {
        assertEquals(0L, desktopHandoffOverlapMs(crossfade, song("a", albumId = "x"), song("b", albumId = "x"), 200_000L))
        assertEquals(0L, desktopHandoffOverlapMs(crossfade, song("a", album = "Album"), song("b", album = "Album"), 200_000L))
        val noGapless = crossfade.copy(gapless = false)
        assertEquals(5_000L, desktopHandoffOverlapMs(noGapless, song("a", albumId = "x"), song("b", albumId = "x"), 200_000L))
    }

    @Test
    fun episodesNeverBlend() {
        assertNull(desktopHandoffOverlapMs(crossfade, song("a", kind = ContentKind.EPISODE), song("b"), 200_000L))
    }

    @Test
    fun automixTakesThePlainFadeTier() {
        val automix = DesktopHandoffSettings(crossfade = false, automix = true, durationSeconds = 0f, gapless = false)
        assertEquals(6_000L, desktopHandoffOverlapMs(automix, song("a"), song("b"), 200_000L))
        assertEquals(8_000L, desktopHandoffOverlapMs(automix.copy(durationSeconds = 8f), song("a"), song("b"), 200_000L))
    }

    @Test
    fun zeroCrossfadeLengthLetsTheSongEnd() {
        assertNull(desktopHandoffOverlapMs(crossfade.copy(durationSeconds = 0f), song("a"), song("b"), 200_000L))
    }
}
