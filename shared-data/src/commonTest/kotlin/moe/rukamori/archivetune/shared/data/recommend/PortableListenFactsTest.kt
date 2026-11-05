package moe.rukamori.archivetune.shared.data.recommend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.recommend.ListeningOutcome
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.settings.BlockedSongRef

class PortableListenFactsTest {
    @Test
    fun aCompletedRowBecomesACompletedTypedFact() {
        val song = dataSong("a")
        val fact = PortableListenFacts.fromRow(dataRow(song, playedMs = 180_000L))!!
        assertEquals(ListeningOutcome.COMPLETED, fact.outcome)
        assertEquals(180_000L, fact.durationMs)
    }

    @Test
    fun theAndroidUnknownDurationSentinelBecomesNullAndNeverCompletes() {
        val song = dataSong("a", durationMs = -1L)
        val fact = PortableListenFacts.fromRow(dataRow(song, playedMs = 120_000L))!!
        assertNull(fact.durationMs)
        assertEquals(ListeningOutcome.PARTIAL, fact.outcome)
    }

    @Test
    fun aZeroDurationIsAlsoUnknown() {
        val song = dataSong("a", durationMs = 0L)
        val fact = PortableListenFacts.fromRow(dataRow(song, playedMs = 30_000L))!!
        assertNull(fact.durationMs)
        assertEquals(ListeningOutcome.PARTIAL, fact.outcome)
    }

    @Test
    fun anUnknownDurationUnderThirtySecondsIsAnEarlySkip() {
        val song = dataSong("a", durationMs = -1L)
        val fact = PortableListenFacts.fromRow(dataRow(song, playedMs = 29_999L))!!
        assertEquals(ListeningOutcome.EARLY_SKIP, fact.outcome)
    }

    @Test
    fun theAndroidEventPlayTimeAndDurationPairNormalisesTheUnknownSentinel() {
        val song = dataSong("a")
        val fact =
            PortableListenFacts.fromEvent(
                occurrence = dataOccurrence(song),
                playedMs = 120_000L,
                durationMs = -1L,
                atMs = DATA_TEST_NOW,
            )!!
        assertNull(fact.durationMs)
        assertEquals(ListeningOutcome.PARTIAL, fact.outcome)
    }

    @Test
    fun theAndroidEventPairRejectsNegativePlayTime() {
        val song = dataSong("a")
        assertNull(
            PortableListenFacts.fromEvent(
                occurrence = dataOccurrence(song),
                playedMs = -1L,
                durationMs = 180_000L,
                atMs = DATA_TEST_NOW,
            ),
        )
    }

    @Test
    fun aMalformedOccurrenceIsSkippedLikeTheLegacyRowsItMirrors() {
        assertNull(PortableListenFacts.fromRow(malformedRow()))
    }

    @Test
    fun aNonSongRowIsNotASongTasteSignal() {
        val album = dataSong("album", kind = ContentKind.ALBUM)
        assertNull(PortableListenFacts.fromRow(dataRow(album, playedMs = 180_000L)))
    }

    @Test
    fun aNegativePlayTimeIsSkippedRatherThanClamped() {
        val song = dataSong("a")
        assertNull(PortableListenFacts.fromRow(dataRow(song, playedMs = -1L)))
    }

    @Test
    fun theHostsInMemoryRecommendedRefsMarkTheFact() {
        val song = dataSong("a")
        val fact = PortableListenFacts.fromRow(dataRow(song, playedMs = 180_000L), setOf(song.ref))!!
        assertTrue(fact.wasRecommended)
        assertEquals(0.5, fact.sourceWeight, 1e-9)
    }

    @Test
    fun aDeliberateListenKeepsFullWeight() {
        val song = dataSong("a")
        val fact = PortableListenFacts.fromRow(dataRow(song, playedMs = 180_000L))!!
        assertFalse(fact.wasRecommended)
        assertEquals(1.0, fact.sourceWeight, 1e-9)
    }

    @Test
    fun aBlockedArtistRemovesEveryListenCreditedToIt() {
        val blocked = dataSong("blocked", artistId = "blocked-artist")
        val allowed = dataSong("allowed", artistId = "allowed-artist")
        val snapshot =
            PortableListenFacts.snapshot(
                rows = listOf(dataRow(blocked, 180_000L), dataRow(allowed, 180_000L)),
                blockedArtists = setOf(ContentRef(blocked.ref.provider, ContentKind.ARTIST, "blocked-artist")),
            )
        assertEquals(listOf(allowed.ref), snapshot.facts.map { it.ref })
        assertEquals(1, snapshot.blockedArtists.size)
    }

    @Test
    fun aBlockedSongIsRemovedFromTheSnapshot() {
        val blocked = dataSong("blocked")
        val allowed = dataSong("allowed")
        val snapshot =
            PortableListenFacts.snapshot(
                rows = listOf(dataRow(blocked, 180_000L), dataRow(allowed, 180_000L)),
                blockedSongs = setOf(BlockedSongRef(blocked.ref.provider, blocked.ref.id)),
            )
        assertEquals(listOf(allowed.ref), snapshot.facts.map { it.ref })
    }

    @Test
    fun theSnapshotKeepsTheBlockListsForCandidateFiltering() {
        val blocked = dataSong("blocked")
        val artist = ContentRef(blocked.ref.provider, ContentKind.ARTIST, "blocked-artist")
        val songRef = BlockedSongRef(blocked.ref.provider, blocked.ref.id)
        val snapshot =
            PortableListenFacts.snapshot(
                rows = emptyList(),
                blockedArtists = setOf(artist),
                blockedSongs = setOf(songRef),
            )
        assertTrue(snapshot.isBlocked(blocked))
        assertEquals(setOf(artist), snapshot.blockedArtists)
        assertEquals(setOf(songRef), snapshot.blockedSongs)
    }

    @Test
    fun anUnblockedCandidateIsNotBlocked() {
        val song = dataSong("a")
        val snapshot = PortableListenFacts.snapshot(emptyList())
        assertFalse(snapshot.isBlocked(song))
    }
}
