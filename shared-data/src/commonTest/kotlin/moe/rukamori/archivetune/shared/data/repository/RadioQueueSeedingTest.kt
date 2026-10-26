package moe.rukamori.archivetune.shared.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.playback.PlaybackCommand
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailure
import moe.rukamori.archivetune.shared.domain.playback.PlaybackLocator
import moe.rukamori.archivetune.shared.domain.playback.PlaybackOutputRoute
import moe.rukamori.archivetune.shared.domain.playback.PlaybackPort
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistPhase
import moe.rukamori.archivetune.shared.domain.queue.QueueRepeatMode
import moe.rukamori.archivetune.shared.domain.repository.ContentCredit
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentPage
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface
import moe.rukamori.archivetune.shared.domain.repository.QueueCollectionKind
import moe.rukamori.archivetune.shared.domain.repository.QueueLoadRequest
import moe.rukamori.archivetune.shared.domain.repository.QueueLocatorResolver
import moe.rukamori.archivetune.shared.domain.repository.TypedContentRepository
import moe.rukamori.archivetune.shared.domain.repository.radioQueueRequest

/**
 * The watch-next seeding rules `MusicService.startRadioSeamlessly` / `onInfiniteQueueEnabled` have on Android,
 * replayed against [PortableQueueOwner]: only a confirmed-complete finite queue under AutoLoadMore with repeat off
 * seeds radio; an unknown or consumed tail never does; every appended page is hidden-content-filtered and
 * deduplicated by media id.
 */
class RadioQueueSeedingTest {
    // Unconfined so engine-snapshot collectors resume inline during advanceUntilIdle; a StandardTestDispatcher
    // leaves them parked in the queue behind the idle check.
    private val scope = TestScope(UnconfinedTestDispatcher())

    private var repeat = QueueRepeatMode.OFF
    private var autoLoadMore = true
    private var hideExplicit = false
    private var hideVideo = false
    private var blockedArtists = emptySet<String>()

    private val locators = object : QueueLocatorResolver {
        override suspend fun locate(occurrence: ContentOccurrence): PlaybackLocator =
            PlaybackLocator.Remote("https://cdn.example/stream")
    }

    private inner class Harness(background: CoroutineScope) {
        val port = FakePlaybackPort()
        val player = MusicPlayerController(port, background)
        val youtube = FakeYouTubeRepository()
        val owner = PortableQueueOwner(
            player = player,
            scope = background,
            content = ContentRepositoryRouter(listOf(youtube)),
            locators = locators,
            repeatMode = { repeat },
            autoLoadMoreEnabled = { autoLoadMore },
            hideExplicit = { hideExplicit },
            hideVideo = { hideVideo },
            blockedArtistIds = { blockedArtists },
        )

        fun endNow(occurrenceId: String) {
            port.snapshots.value = PlaybackSnapshot(
                occurrenceId = occurrenceId,
                mediaId = occurrenceId,
                playbackGeneration = 1L,
                discontinuityGeneration = 0L,
                positionMs = 1L,
                sampleMonotonicNanos = 0L,
                durationMs = 180_000L,
                isSeekable = true,
                playbackSpeed = 1f,
                playWhenReady = true,
                isAudioAdvancing = true,
                isBuffering = false,
                outputRoute = PlaybackOutputRoute.LOCAL,
                isEnded = true,
            )
        }
    }

    @Test
    fun exhaustedFiniteQueueSeedsRadioTailAtEnd() = scope.runTest {
        val h = Harness(backgroundScope)
        h.youtube.playlistPages += page(song("p1"), song("p2"))
        h.youtube.radioPages += page(song("p2"), song("r1"), song("r2"), continuation = "rc1")
        h.youtube.radioPages += page(song("r3"))

        h.owner.withQueueAccess { h.owner.loadCollection(QueueLoadRequest(playlistRequest("PL1"), QueueCollectionKind.FINITE)) }
        // The confirmed-complete finite load seeds radio immediately (`shouldBootstrapInfiniteQueue`),
        // deduplicated against every queued id and filtered; the queue now runs on the radio queue.
        val seeded = h.owner.state.value
        assertEquals(listOf("p1", "p2", "r1", "r2"), seeded.entries.map { it.occurrenceId })
        assertEquals(QueueCollectionKind.RADIO, seeded.tail?.kind)
        assertEquals("rc1", seeded.tail?.continuation)
        assertEquals(FinitePlaylistPhase.UNKNOWN, seeded.phase)
        assertEquals(ContentSurface.RADIO, h.youtube.lastRadioRequest?.surface)
        assertEquals("p1", h.youtube.lastRadioRequest?.parent?.id)

        h.owner.withQueueAccess { h.owner.select("p2", 0L) }
        advanceUntilIdle()
        h.endNow("p2")
        advanceUntilIdle()
        assertEquals("r1", h.owner.state.value.currentOccurrenceId)

        // Consuming the radio tail pages it once more; at the last fresh song the consumed radio tail
        // re-seeds from the current song and, with no page left, simply ends.
        h.endNow("r1")
        advanceUntilIdle()
        h.endNow("r2")
        advanceUntilIdle()
        val grown = h.owner.state.value
        assertEquals(listOf("p1", "p2", "r1", "r2", "r3"), grown.entries.map { it.occurrenceId })
        assertNull(grown.tail)

        h.endNow("r3")
        advanceUntilIdle()
        assertEquals("r3", h.owner.state.value.currentOccurrenceId)
        assertEquals(3, h.youtube.radioPageCalls)
    }

    @Test
    fun repeatAllWrapsInsteadOfSeeding() = scope.runTest {
        val h = Harness(backgroundScope)
        h.youtube.playlistPages += page(song("p1"), song("p2"))
        repeat = QueueRepeatMode.ALL

        h.owner.withQueueAccess { h.owner.loadCollection(QueueLoadRequest(playlistRequest("PL1"), QueueCollectionKind.FINITE)) }
        h.owner.withQueueAccess { h.owner.select("p2", 0L) }
        advanceUntilIdle()
        val advanceFailures = mutableListOf<Throwable>()
        backgroundScope.launch { h.owner.automaticAdvanceFailures.collect { advanceFailures += it } }
        h.endNow("p2")
        advanceUntilIdle()

        if (advanceFailures.isNotEmpty()) throw AssertionError("advance failed: ${advanceFailures.first()}", advanceFailures.first())
        assertEquals("p1", h.owner.state.value.currentOccurrenceId)
        assertEquals(0, h.youtube.radioPageCalls)
    }

    @Test
    fun autoLoadMoreOffLeavesTheQueueEnded() = scope.runTest {
        val h = Harness(backgroundScope)
        h.youtube.playlistPages += page(song("p1"), song("p2"))
        autoLoadMore = false

        h.owner.withQueueAccess { h.owner.loadCollection(QueueLoadRequest(playlistRequest("PL1"), QueueCollectionKind.FINITE)) }
        h.owner.withQueueAccess { h.owner.select("p2", 0L) }
        advanceUntilIdle()
        h.endNow("p2")
        advanceUntilIdle()

        assertEquals("p2", h.owner.state.value.currentOccurrenceId)
        assertEquals(0, h.youtube.radioPageCalls)
    }

    @Test
    fun unknownTailPagesButNeverSeedsAgain() = scope.runTest {
        val h = Harness(backgroundScope)
        h.youtube.radioPages += page(song("r1"), continuation = "rc1")
        h.youtube.radioPages += page(song("r2"))

        val collection = radioQueueRequest(song("seed").item.ref)
        h.owner.withQueueAccess { h.owner.loadCollection(QueueLoadRequest(collection, QueueCollectionKind.RADIO)) }
        assertEquals(FinitePlaylistPhase.UNKNOWN, h.owner.state.value.phase)
        assertEquals("rc1", h.owner.state.value.tail?.continuation)

        h.endNow("r1")
        advanceUntilIdle()
        val paged = h.owner.state.value
        assertEquals(listOf("r1", "r2"), paged.entries.map { it.occurrenceId })
        assertNull(paged.tail)

        // The consumed radio tail re-seeds from the current song; with no page left the queue simply ends.
        h.endNow("r2")
        advanceUntilIdle()
        assertEquals("r2", h.owner.state.value.currentOccurrenceId)
        assertEquals(3, h.youtube.radioPageCalls)
    }

    @Test
    fun startRadioSeamlesslyReplacesTheQueueTail() = scope.runTest {
        val h = Harness(backgroundScope)
        h.youtube.playlistPages += page(song("p1"), song("p2"), song("p3"))
        h.youtube.radioPages += page(song("p1"), song("r1"), song("r2"), continuation = "rc9")
        h.youtube.radioPages += page(song("p1"), song("rA"), song("rB"), continuation = "rcS")

        h.owner.withQueueAccess { h.owner.loadCollection(QueueLoadRequest(playlistRequest("PL1"), QueueCollectionKind.FINITE)) }
        // The load-time seed already appended a radio tail, which "Start radio" replaces wholesale.
        assertEquals(listOf("p1", "p2", "p3", "r1", "r2"), h.owner.state.value.entries.map { it.occurrenceId })
        advanceUntilIdle()
        assertTrue(h.owner.withQueueAccess { h.owner.startRadioSeamlessly() })

        val queue = h.owner.state.value
        assertEquals(listOf("p1", "rA", "rB"), queue.entries.map { it.occurrenceId })
        assertEquals("p1", queue.currentOccurrenceId)
        assertEquals(FinitePlaylistPhase.UNKNOWN, queue.phase)
        assertEquals("rcS", queue.tail?.continuation)
        assertEquals(QueueCollectionKind.RADIO, queue.tail?.kind)
    }

    @Test
    fun startRadioSeamlesslyWithoutFreshSongsLeavesTheQueue() = scope.runTest {
        val h = Harness(backgroundScope)
        h.youtube.playlistPages += page(song("p1"), song("p2"))
        h.youtube.radioPages += page(song("p1"), continuation = "rc1")

        h.owner.withQueueAccess { h.owner.loadCollection(QueueLoadRequest(playlistRequest("PL1"), QueueCollectionKind.FINITE)) }
        advanceUntilIdle()
        assertTrue(h.owner.withQueueAccess { h.owner.startRadioSeamlessly() })

        // The second radio page answers empty, so the queue keeps exactly what the load-time seed left.
        val queue = h.owner.state.value
        assertEquals(listOf("p1", "p2"), queue.entries.map { it.occurrenceId })
        assertEquals("p1", queue.currentOccurrenceId)
        assertNull(queue.tail)
    }

    @Test
    fun bootstrapPagesUntilAFreshSongArrives() = scope.runTest {
        val h = Harness(backgroundScope)
        h.youtube.playlistPages += page(song("p1"))
        h.youtube.radioPages += page(song("p1"), continuation = "rc1")
        h.youtube.radioPages += page(song("p1"), continuation = "rc2")
        h.youtube.radioPages += page(song("r1"), continuation = "rc3")

        h.owner.withQueueAccess { h.owner.loadCollection(QueueLoadRequest(playlistRequest("PL1"), QueueCollectionKind.FINITE)) }
        advanceUntilIdle()

        // The bootstrap pages until a fresh song arrives, bounded at three pages; it leaves the last token.
        val queue = h.owner.state.value
        assertEquals(listOf("p1", "r1"), queue.entries.map { it.occurrenceId })
        assertEquals("rc3", queue.tail?.continuation)
        assertEquals(3, h.youtube.radioPageCalls)
    }

    @Test
    fun hiddenContentIsFilteredOutOfSeededRadio() = scope.runTest {
        val h = Harness(backgroundScope)
        hideExplicit = true
        hideVideo = true
        blockedArtists = setOf("blocked")
        h.youtube.playlistPages += page(song("p1"), song("p2"), song("p3", explicit = true))
        h.youtube.radioPages += page(song("r1", explicit = true), song("r2", isVideo = true), song("r3", artist = "blocked"), song("r4"))

        h.owner.withQueueAccess { h.owner.loadCollection(QueueLoadRequest(playlistRequest("PL1"), QueueCollectionKind.FINITE)) }
        // The explicit playlist song never entered the queue, and the seeded radio kept only r4.
        assertEquals(listOf("p1", "p2", "r4"), h.owner.state.value.entries.map { it.occurrenceId })
    }

    @Test
    fun startRadioSeamlesslyRefusesANonYouTubeSong() = scope.runTest {
        val h = Harness(backgroundScope)
        h.youtube.playlistPages += page(localSong("l1"))

        h.owner.withQueueAccess { h.owner.loadCollection(QueueLoadRequest(playlistRequest("L1"), QueueCollectionKind.FINITE)) }
        advanceUntilIdle()
        assertFalse(h.owner.withQueueAccess { h.owner.startRadioSeamlessly() })
        assertEquals(0, h.youtube.radioPageCalls)
    }

    @Test
    fun radioRequestMapsSongAndPlaylistSeeds() {
        val song = radioQueueRequest(ContentRef(ContentProvider.YOUTUBE.id, ContentKind.SONG, "v1"))
        assertEquals(ContentSurface.RADIO, song.surface)
        assertEquals(ContentKind.SONG, song.parent?.kind)
        assertNull(song.section)

        val playlist = radioQueueRequest(ContentRef(ContentProvider.YOUTUBE.id, ContentKind.PLAYLIST, "RDAMv1"), "sv1", "params")
        assertEquals(ContentKind.PLAYLIST, playlist.parent?.kind)
        assertEquals("sv1", playlist.section?.id)
        assertEquals("params", playlist.section?.params)

        assertFailsWith<IllegalArgumentException> {
            radioQueueRequest(ContentRef(ContentProvider.LOCAL.id, ContentKind.SONG, "x"))
        }
        assertFailsWith<IllegalArgumentException> {
            radioQueueRequest(ContentRef(ContentProvider.YOUTUBE.id, ContentKind.ALBUM, "x"))
        }
    }

    private fun playlistRequest(id: String) = ContentRequest(
        provider = ContentProvider.YOUTUBE,
        surface = ContentSurface.PLAYLIST,
        parent = ContentRef(ContentProvider.YOUTUBE.id, ContentKind.PLAYLIST, id),
    )

    private fun song(
        id: String,
        explicit: Boolean = false,
        isVideo: Boolean = false,
        artist: String? = "artist",
    ) = ContentOccurrence(
        occurrenceId = id,
        item = ContentItem(
            ref = ContentRef(ContentProvider.YOUTUBE.id, ContentKind.SONG, id),
            title = "song-$id",
            explicit = explicit,
            isVideo = isVideo,
            artists = listOfNotNull(artist?.let { ContentCredit(name = it, id = it) }),
        ),
        setVideoId = id,
    )

    private fun localSong(id: String) = ContentOccurrence(
        occurrenceId = id,
        item = ContentItem(
            ref = ContentRef(ContentProvider.LOCAL.id, ContentKind.SONG, id),
            title = "song-$id",
        ),
    )

    private fun page(vararg items: ContentOccurrence, continuation: String? = null) =
        ContentPage(items.toList(), continuation)

    private class FakePlaybackPort : PlaybackPort {
        override val snapshots = MutableStateFlow<PlaybackSnapshot?>(null)
        override val failures = MutableSharedFlow<PlaybackFailure>()
        val submitted = mutableListOf<PlaybackCommand>()

        override fun submit(command: PlaybackCommand) {
            submitted += command
        }

        override fun close() {}
    }

    /** A scripted YouTube transport: playlist pages feed finite walks, radio pages feed watch-next queues. */
    private class FakeYouTubeRepository : TypedContentRepository {
        override val provider = ContentProvider.YOUTUBE
        val playlistPages = ArrayDeque<ContentPage>()
        val radioPages = ArrayDeque<ContentPage>()
        var radioPageCalls = 0
        var lastRadioRequest: ContentRequest? = null

        override suspend fun page(request: ContentRequest, continuation: String?): ContentPage =
            when (request.surface) {
                ContentSurface.RADIO -> {
                    radioPageCalls++
                    if (continuation == null) lastRadioRequest = request
                    radioPages.removeFirstOrNull() ?: ContentPage(emptyList())
                }
                else -> playlistPages.removeFirstOrNull() ?: ContentPage(emptyList())
            }

        override suspend fun item(ref: ContentRef): ContentItem = throw UnsupportedOperationException("unused in tests")
    }
}
