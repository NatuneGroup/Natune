package moe.rukamori.archivetune.shared.data.playback

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.settings.AutomixEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.CrossfadeDurationKey
import moe.rukamori.archivetune.shared.domain.settings.CrossfadeEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot

class IosBlendPolicyTest {
    private val crossfade = IosBlendSettings(true, false, 5f, true)
    private fun song(id: String, albumId: String? = null, album: String? = null, kind: ContentKind = ContentKind.SONG) =
        ContentItem(ContentRef("youtube", kind, id), id, album = album, albumId = albumId)

    @Test
    fun disabledSettingsNeverArmEvenForOneAlbum() {
        assertNull(iosBlendOverlapMs(crossfade.copy(crossfade = false), song("a", "album"), song("b", "album"), 200_000L))
        assertNull(iosBlendOverlapMs(crossfade.copy(seconds = 0f), song("a"), song("b"), 200_000L))
    }

    @Test
    fun crossfadeHasAndroidDurationBoundsAndEndGuard() {
        assertEquals(5_000L, iosBlendOverlapMs(crossfade, song("a"), song("b"), 200_000L))
        assertEquals(10_000L, iosBlendOverlapMs(crossfade.copy(seconds = 40f), song("a"), song("b"), 200_000L))
        assertEquals(2_850L, iosBlendOverlapMs(crossfade, song("a"), song("b"), 3_000L))
        assertNull(iosBlendOverlapMs(crossfade, song("a"), song("b"), 600L))
        assertNull(iosBlendOverlapMs(crossfade, song("a"), song("b"), -1L))
    }

    @Test
    fun automixUsesTheSamePlainFadeFallbackAsDesktop() {
        val automix = crossfade.copy(crossfade = false, automix = true, seconds = 0f)
        assertEquals(6_000L, iosBlendOverlapMs(automix, song("a"), song("b"), 200_000L))
        assertEquals(8_000L, iosBlendOverlapMs(automix.copy(seconds = 8f), song("a"), song("b"), 200_000L))
        assertEquals(600L, iosBlendOverlapMs(automix, song("a"), song("b"), 600L))
        assertNull(iosBlendOverlapMs(automix, song("a"), song("b"), 499L))
    }

    @Test
    fun automixWinsAnInconsistentStoredPairAndInvalidDurationIsSafe() {
        val stored = PreferenceSnapshot.of(
            AutomixEnabledKey to true, CrossfadeEnabledKey to true, CrossfadeDurationKey to Float.NaN,
        ).iosBlendSettings()
        assertTrue(stored.automix)
        assertFalse(stored.crossfade)
        assertEquals(5f, stored.seconds)
        assertEquals(5_000L, iosBlendOverlapMs(stored, song("a"), song("b"), 200_000L))
    }

    @Test
    fun albumGaplessUsesIdentityThenTitleAndCanBeTurnedOff() {
        assertEquals(0L, iosBlendOverlapMs(crossfade, song("a", "album"), song("b", "album"), 200_000L))
        assertEquals(0L, iosBlendOverlapMs(crossfade, song("a", album = "Album"), song("b", album = "Album"), 200_000L))
        assertEquals(5_000L, iosBlendOverlapMs(crossfade.copy(gapless = false), song("a", "album"), song("b", "album"), 200_000L))
        assertEquals(5_000L, iosBlendOverlapMs(crossfade, song("a", "left"), song("b", "right"), 200_000L))
    }

    @Test
    fun neitherSideOfAPodcastMayBlend() {
        assertNull(iosBlendOverlapMs(crossfade, song("a", kind = ContentKind.EPISODE), song("b"), 200_000L))
        assertNull(iosBlendOverlapMs(crossfade, song("a"), song("b", kind = ContentKind.PODCAST), 200_000L))
    }

    @Test
    fun envelopeHasEqualPowerMidpointAndClampedEndpoints() {
        val progress = IosBlendProgress(1_000L, 10_000L)
        assertEquals(1f, progress.gains().outgoing)
        assertEquals(0f, progress.gains().incoming)
        val halfway = progress.advance(10_500L, 500L, true)
        assertTrue(abs(halfway.outgoing * halfway.outgoing + halfway.incoming * halfway.incoming - 1f) < 0.00001f)
        assertTrue(abs(halfway.outgoing - halfway.incoming) < 0.00001f)
        val end = progress.advance(12_000L, 2_000L, true)
        assertTrue(progress.complete)
        assertTrue(abs(end.outgoing) < 0.00001f)
        assertEquals(1f, end.incoming)
        assertEquals(1_000L, progress.elapsedMs)
    }

    @Test
    fun pauseStallAndASeekDoNotAdvanceTheFade() {
        val progress = IosBlendProgress(5_000L, 10_000L)
        progress.advance(11_000L, 0L, false)
        progress.advance(12_000L, 0L, true)
        assertEquals(0L, progress.elapsedMs)
        progress.advance(12_500L, 500L, true)
        assertEquals(500L, progress.elapsedMs)
        progress.advance(1_000L, 600L, true)
        assertEquals(500L, progress.elapsedMs)
    }

    @Test
    fun renderedFramesNotPrefetchedPcmOwnThePosition() {
        val span = IosPcmSpan(48_000L, 96_000L, 5_000_000L, 7_000_000L)
        assertEquals(5_000_000L, span.positionUs(0L))
        assertEquals(5_000_000L, span.positionUs(48_000L))
        assertEquals(6_000_000L, span.positionUs(72_000L))
        assertEquals(7_000_000L, span.positionUs(120_000L))
    }
}
