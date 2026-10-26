package moe.rukamori.archivetune.shared.domain.source

import moe.rukamori.archivetune.shared.domain.source.AudioSourceType.APPLE
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType.DEEZER
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType.QOBUZ_BACKUP
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType.TIDAL
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType.YOUTUBE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Characterizes the per-song "play from" pin codecs. The pins sit in one preference string so backups carry
 * them, which is why a malformed entry must be skipped rather than poison the whole value.
 */
class SourceOverrideCodecTest {
    @Test
    fun parseReadsEveryValidPinAndAcceptsLowerCaseNames() {
        assertEquals(
            mapOf("s1" to YOUTUBE, "s2" to DEEZER),
            SongSourceOverride.parse("s1=YOUTUBE;s2=deezer"),
        )
    }

    @Test
    fun malformedAndUnknownEntriesAreSkipped() {
        assertEquals(
            mapOf("s3" to QOBUZ_BACKUP),
            SongSourceOverride.parse("=TIDAL;s1;s2=NOPE;;s3=QOBUZ_BACKUP;s4=  "),
        )
    }

    @Test
    fun theLastValidPinForASongWinsAndAnInvalidOneNeverReplacesIt() {
        assertEquals(mapOf("s1" to DEEZER), SongSourceOverride.parse("s1=TIDAL;s1=DEEZER"))
        assertEquals(mapOf("s1" to TIDAL), SongSourceOverride.parse("s1=TIDAL;s1=NOPE"))
    }

    @Test
    fun whitespaceAroundTheSongIdAndTheSourceIsTolerated() {
        assertEquals(mapOf("s1" to TIDAL), SongSourceOverride.parse(" s1 = tidal "))
    }

    @Test
    fun aBlankOrMissingValueHasNoPins() {
        assertEquals(emptyMap(), SongSourceOverride.parse(null))
        assertEquals(emptyMap(), SongSourceOverride.parse("  "))
    }

    @Test
    fun serializeRoundTripsThroughParse() {
        val pins = linkedMapOf("a" to TIDAL, "b" to YOUTUBE)

        assertEquals("a=TIDAL;b=YOUTUBE", SongSourceOverride.serialize(pins))
        assertEquals(pins, SongSourceOverride.parse(SongSourceOverride.serialize(pins)))
    }

    @Test
    fun withOverrideSetsOnePinAndClearsOnlyThatPin() {
        val set = SongSourceOverride.withOverride("s1=TIDAL;s2=DEEZER", "s1", YOUTUBE)

        assertEquals("s1=YOUTUBE;s2=DEEZER", set)
        assertEquals("s2=DEEZER", SongSourceOverride.withOverride(set, "s1", null))
        assertEquals("s9=APPLE", SongSourceOverride.withOverride(null, "s9", APPLE))
    }

    @Test
    fun getReadsThePinForOneSongOrNull() {
        assertEquals(DEEZER, SongSourceOverride.get("s1=DEEZER", "s1"))
        assertNull(SongSourceOverride.get("s1=DEEZER", "s2"))
        assertNull(SongSourceOverride.get(null, "s1"))
    }

    @Test
    fun qobuzTrackIdsKeepTextualIdsAndSkipEmptyOrMalformedEntries() {
        assertEquals(
            mapOf("s1" to "123", "s2" to "456"),
            SongSourceQobuzTrackId.parse("s1=123;s2= 456 ;=9;s3=;s4"),
        )
    }

    @Test
    fun qobuzTrackIdPinsSetClearAndRead() {
        assertEquals("s1=99", SongSourceQobuzTrackId.withOverride(null, "s1", "99"))
        assertEquals("", SongSourceQobuzTrackId.withOverride("s1=99", "s1", null))
        assertEquals("456", SongSourceQobuzTrackId.get("s2=456", "s2"))
    }

    @Test
    fun qobuzBackupVideoIdsShareTheTrackIdCodec() {
        val raw = SongSourceQobuzBackupVideoId.withOverride(null, "s1", "vid-1")

        assertEquals("s1=vid-1", raw)
        assertEquals(mapOf("s1" to "vid-1"), SongSourceQobuzBackupVideoId.parse(raw))
        assertEquals("vid-1", SongSourceQobuzBackupVideoId.get(raw, "s1"))
        assertEquals("", SongSourceQobuzBackupVideoId.withOverride(raw, "s1", null))
        assertEquals(
            SongSourceQobuzTrackId.serialize(mapOf("a" to "b")),
            SongSourceQobuzBackupVideoId.serialize(mapOf("a" to "b")),
        )
    }
}
