package moe.rukamori.archivetune.playback

import androidx.media3.common.MediaItem
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.IdentityHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.db.entities.ArtistEntity
import moe.rukamori.archivetune.db.entities.Event
import moe.rukamori.archivetune.db.entities.EventWithSong
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.db.entities.SongEntity
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.shared.data.recommend.QueueTailRecommendation
import moe.rukamori.archivetune.shared.data.recommend.RecommendationSignalSnapshot
import moe.rukamori.archivetune.shared.data.recommend.RecommendationSignals
import moe.rukamori.archivetune.shared.domain.recommend.DiversityConstraints
import moe.rukamori.archivetune.shared.domain.recommend.ListenFact
import moe.rukamori.archivetune.shared.domain.recommend.ListeningOutcome
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.settings.BlockedSongRef

private const val TEST_NOW = 1_700_000_000_000L
private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 86_400_000L

class AndroidQueueTailRecommendationTest {
    @Test
    fun metadataProjectionKeepsCanonicalProviderCreditsAndUnknownDurations() {
        val fixture = Fixture()
        val unknown =
            fixture
                .register(
                    "video",
                    MediaMetadata(
                        id = "video",
                        title = "Display title",
                        artists = listOf(MediaMetadata.Artist(id = null, name = "Unknown credit")),
                        duration = -1,
                        album = MediaMetadata.Album(id = "album", title = "Album"),
                        canonicalTitle = "Canonical title",
                        canonicalArtists = listOf(MediaMetadata.Artist(id = "UC-artist", name = "Canonical artist")),
                    ),
                ).toRecommendationOccurrence(fixture.reader)
        assertEquals("youtube", unknown.item.ref.provider)
        assertEquals(ContentKind.SONG, unknown.item.ref.kind)
        assertEquals("video", unknown.item.ref.id)
        assertEquals("Canonical title", unknown.item.title)
        assertNull(unknown.item.durationMs)
        assertEquals(listOf("UC-artist"), unknown.item.artists.mapNotNull { it.id })
        assertEquals("album", unknown.item.albumId)

        assertEquals(
            180_000L,
            fixture.item("known", duration = 180).toRecommendationOccurrence(fixture.reader).item.durationMs,
        )
        assertNull(fixture.item("zero", duration = 0).toRecommendationOccurrence(fixture.reader).item.durationMs)

        val tagless = fixture.untagged("tagless").toRecommendationOccurrence(fixture.reader)
        assertEquals("tagless", tagless.item.ref.id)
        assertNull(tagless.item.durationMs)
        assertTrue(tagless.item.artists.isEmpty())

        val local = fixture.untagged("content://fixture/song").toRecommendationOccurrence(fixture.reader)
        assertEquals(ContentProvider.LOCAL.id, local.item.ref.provider)
    }

    @Test
    fun savedEventsProjectKnownUnknownLocalAndBlockedRows() = runTest {
        val timestamp = LocalDateTime.of(2026, 1, 2, 3, 4, 5)
        val rows =
            listOf(
                row("known", durationSeconds = 180, playTimeMs = 180_000, timestamp = timestamp),
                row("unknown-negative", durationSeconds = -1, playTimeMs = 180_000, timestamp = timestamp),
                row("unknown-zero", durationSeconds = 0, playTimeMs = 180_000, timestamp = timestamp),
                row("local", durationSeconds = 180, playTimeMs = 180_000, timestamp = timestamp, isLocal = true),
                row("content://fixture/song", durationSeconds = 180, playTimeMs = 180_000, timestamp = timestamp),
                row("blocked", durationSeconds = 180, playTimeMs = 180_000, timestamp = timestamp),
                row(
                    "blocked-artist",
                    durationSeconds = 180,
                    playTimeMs = 180_000,
                    timestamp = timestamp,
                    artists = listOf(ArtistEntity(id = "UC-blocked", name = "Blocked")),
                ),
                row(
                    "local-credit",
                    durationSeconds = 180,
                    playTimeMs = 180_000,
                    timestamp = timestamp,
                    artists =
                        listOf(
                            ArtistEntity(id = "LA-local", name = "Local credit", isLocal = true),
                            ArtistEntity(id = "UC-real", name = "Real credit"),
                        ),
                ),
            )
        val signals =
            AndroidRecommendationSignals(
                events = { rows },
                blockedArtistIds = { setOf("UC-blocked") },
                blockedSongIds = { setOf("blocked") },
                zoneId = ZoneOffset.UTC,
            )

        val snapshot = signals.snapshot(emptySet())
        assertEquals(listOf("known", "unknown-negative", "unknown-zero", "local-credit"), snapshot.facts.map { it.ref.id })
        val known = snapshot.facts.first { it.ref.id == "known" }
        assertEquals(ListeningOutcome.COMPLETED, known.outcome)
        assertEquals(180_000L, known.durationMs)
        assertEquals(timestamp.toInstant(ZoneOffset.UTC).toEpochMilli(), known.atMs)
        val unknown = snapshot.facts.first { it.ref.id == "unknown-negative" }
        assertNull(unknown.durationMs)
        assertEquals(ListeningOutcome.PARTIAL, unknown.outcome)
        assertNull(snapshot.facts.first { it.ref.id == "unknown-zero" }.durationMs)
        val localCredit = snapshot.facts.first { it.ref.id == "local-credit" }
        assertEquals(listOf("UC-real"), localCredit.artistIds)
        assertEquals(setOf(ContentRef(ContentProvider.YOUTUBE.id, ContentKind.ARTIST, "UC-blocked")), snapshot.blockedArtists)
        assertEquals(setOf(BlockedSongRef(ContentProvider.YOUTUBE.id, "blocked")), snapshot.blockedSongs)
    }

    @Test
    fun thePlannerDropsARecentProviderFirstCandidateAndReturnsTheLaterOriginalItem() = runTest {
        val fixture = Fixture()
        val signals = CountedSignals()
        val seed = fixture.item("seed", artistId = "seed-artist")
        val recent = fixture.item("recent", artistId = "recent-artist")
        val eligible = fixture.item("eligible", artistId = "eligible-artist")
        signals.value = RecommendationSignalSnapshot(facts = listOf(factFor(fixture, recent, TEST_NOW - 1_000L)))
        val adapter = adapter(signals, fixture)

        val selected =
            adapter.plan(
                candidates = listOf(recent, eligible),
                snapshot = AndroidQueueTailSnapshot(listOf(seed), currentIndex = 0),
            )

        assertEquals(1, signals.calls)
        assertEquals(1, selected.size)
        assertTrue(selected.single() === eligible)
    }

    @Test
    fun automaticAccountingUsesOnlyFutureNonManualIndices() = runTest {
        val fixture = Fixture()
        val signals = CountedSignals()
        val seed = fixture.item("seed", artistId = "seed-artist")
        val automatic = listOf(fixture.item("auto1", artistId = "auto1"), fixture.item("auto2", artistId = "auto2"))
        val adapter =
            adapter(
                signals,
                fixture,
                constraints = DiversityConstraints(maxTailItems = 2),
                automaticIds = mutableSetOf("seed", "auto1", "auto2"),
            )
        val candidates = (1..5).map { fixture.item("c$it", artistId = "artist-$it", albumId = "album-$it") }

        val selected =
            adapter.plan(
                candidates = candidates,
                snapshot =
                    AndroidQueueTailSnapshot(
                        playOrder = listOf(seed) + automatic,
                        currentIndex = 0,
                        manualIndices = setOf(1),
                    ),
            )

        // Current "seed" and manual index 1 are excluded; auto2 alone consumes one of two slots.
        assertEquals(1, selected.size)
        assertTrue(selected.single().mediaId.startsWith("c"))
    }

    @Test
    fun aLaterCurrentDuplicateDoesNotCountPlayedAutomaticIdsAsPending() = runTest {
        val fixture = Fixture()
        val signals = CountedSignals()
        val playOrder = (0..50).map { fixture.item("duplicate", artistId = "artist-$it") }
        val adapter = adapter(signals, fixture, automaticIds = mutableSetOf("duplicate"))
        val candidates = (1..10).map { fixture.item("c$it", artistId = "candidate-$it", albumId = "album-$it") }

        val selected =
            adapter.plan(
                candidates = candidates,
                snapshot = AndroidQueueTailSnapshot(playOrder, currentIndex = 50),
            )

        // The only current occurrence is index 50, so the fifty earlier duplicates are played, not pending.
        assertEquals(10, selected.size)
    }

    @Test
    fun aManualDuplicateIndexDoesNotHideTheAutomaticOccurrence() = runTest {
        val fixture = Fixture()
        val signals = CountedSignals()
        val playOrder =
            listOf(
                fixture.item("duplicate", artistId = "past"),
                fixture.item("duplicate", artistId = "current"),
                fixture.item("duplicate", artistId = "future-auto"),
                fixture.item("duplicate", artistId = "future-manual"),
            )
        val adapter =
            adapter(
                signals,
                fixture,
                constraints = DiversityConstraints(maxTailItems = 2),
                automaticIds = mutableSetOf("duplicate"),
            )
        val candidates = (1..3).map { fixture.item("c$it", artistId = "candidate-$it") }

        val selected =
            adapter.plan(
                candidates = candidates,
                snapshot =
                    AndroidQueueTailSnapshot(
                        playOrder = playOrder,
                        currentIndex = 1,
                        manualIndices = setOf(3),
                    ),
            )

        // Only index 2 is automatic and still pending; index 3 is manual and index 0 is played.
        assertEquals(1, selected.size)
    }

    @Test
    fun aProviderBatchBeyondOneHundredCandidatesIsBoundedBeforeScoring() = runTest {
        val fixture = Fixture()
        val signals = CountedSignals()
        val seed = fixture.item("seed", artistId = "seed-artist")
        val page = (1..150).map { fixture.item("c$it", artistId = "artist-$it", albumId = "album-$it") }
        val fillers = (1..45).map { fixture.item("f$it", artistId = "filler-$it") }
        signals.value =
            RecommendationSignalSnapshot(
                facts =
                    fillers.map { factFor(fixture, it, TEST_NOW - HOUR_MS) } +
                        (101..110).map { factFor(fixture, page[it - 1], TEST_NOW - DAY_MS) },
            )
        val adapter = adapter(signals, fixture)

        val selected =
            adapter.plan(
                candidates = page,
                snapshot = AndroidQueueTailSnapshot(listOf(seed), currentIndex = 0),
            )

        assertEquals(10, selected.size)
        assertTrue(selected.all { it.mediaId.removePrefix("c").toInt() in 1..100 })
    }

    @Test
    fun aFixedSeedReturnsTheSameSelectionOnEveryAdapter() = runTest {
        val fixture = Fixture()
        val seed = fixture.item("seed", artistId = "seed-artist")
        val page = (1..12).map { fixture.item("t$it", artistId = "artist-t$it", albumId = "album-t$it") }
        val first = adapter(CountedSignals(), fixture)
        val second = adapter(CountedSignals(), fixture)
        val snapshot = AndroidQueueTailSnapshot(listOf(seed), currentIndex = 0)

        val firstSelected = first.plan(page, snapshot).map { it.mediaId }
        val secondSelected = second.plan(page, snapshot).map { it.mediaId }

        assertEquals(firstSelected, secondSelected)
    }

    private fun adapter(
        signals: RecommendationSignals,
        fixture: Fixture,
        constraints: DiversityConstraints = DiversityConstraints.Default,
        automaticIds: Set<String> = emptySet(),
        blockedSongIds: Set<String> = emptySet(),
    ): AndroidQueueTailRecommendation {
        val scheduler = kotlinx.coroutines.test.UnconfinedTestDispatcher()
        return AndroidQueueTailRecommendation(
            recommendation =
                QueueTailRecommendation(
                    signals = signals,
                    nowMs = { TEST_NOW },
                    offsetMsAt = { 0L },
                    computeDispatcher = scheduler,
                    constraints = constraints,
                ),
            automaticIds = automaticIds,
            metadataOf = fixture.reader,
            blockedSongIds = { blockedSongIds },
            computeDispatcher = scheduler,
        )
    }

    private class Fixture {
        private val metadata = IdentityHashMap<MediaItem, MediaMetadata>()
        val reader: (MediaItem) -> MediaMetadata? = { metadata[it] }

        fun item(
            id: String,
            artistId: String? = null,
            albumId: String? = null,
            duration: Int = 180,
        ): MediaItem =
            register(
                id,
                MediaMetadata(
                    id = id,
                    title = id,
                    artists = listOfNotNull(artistId?.let { MediaMetadata.Artist(id = it, name = it) }),
                    duration = duration,
                    album = albumId?.let { MediaMetadata.Album(id = it, title = it) },
                ),
            )

        fun register(mediaId: String, metadata: MediaMetadata): MediaItem {
            val item = MediaItem.Builder().setMediaId(mediaId).build()
            this.metadata[item] = metadata
            return item
        }

        fun untagged(mediaId: String): MediaItem = MediaItem.Builder().setMediaId(mediaId).build()

        fun metadataOf(item: MediaItem): MediaMetadata = requireNotNull(metadata[item])
    }

    private class CountedSignals : RecommendationSignals {
        var value = RecommendationSignalSnapshot()
        var calls = 0

        override suspend fun snapshot(recommendedRefs: Set<ContentRef>): RecommendationSignalSnapshot {
            calls++
            return value
        }
    }

    private companion object {
        private fun factFor(fixture: Fixture, item: MediaItem, atMs: Long): ListenFact {
            val metadata = fixture.metadataOf(item)
            return ListenFact(
                ref = ContentRef(ContentProvider.YOUTUBE.id, ContentKind.SONG, metadata.id),
                artistIds = metadata.artists.mapNotNull { it.id },
                albumId = metadata.album?.id,
                playedMs = (metadata.duration.coerceAtLeast(1) * 1000L),
                durationMs = metadata.duration.takeIf { it > 0 }?.toLong()?.times(1000L),
                atMs = atMs,
            )
        }

        private fun row(
            id: String,
            durationSeconds: Int,
            playTimeMs: Long,
            timestamp: LocalDateTime,
            isLocal: Boolean = false,
            artists: List<ArtistEntity> = listOf(ArtistEntity(id = "UC-default", name = "Default")),
        ): EventWithSong =
            EventWithSong(
                event = Event(songId = id, timestamp = timestamp, playTime = playTimeMs),
                song =
                    Song(
                        song =
                            SongEntity(
                                id = id,
                                title = id,
                                duration = durationSeconds,
                                isLocal = isLocal,
                            ),
                        artists = artists,
                    ),
            )
    }
}
