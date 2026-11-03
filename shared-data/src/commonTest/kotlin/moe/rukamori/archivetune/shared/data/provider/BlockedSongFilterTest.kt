package moe.rukamori.archivetune.shared.data.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.shared.data.repository.ContentRepositoryRouter
import moe.rukamori.archivetune.shared.data.repository.PortableQueueOwner
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.playback.PlaybackCommand
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailure
import moe.rukamori.archivetune.shared.domain.playback.PlaybackLocator
import moe.rukamori.archivetune.shared.domain.playback.PlaybackPort
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
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
import moe.rukamori.archivetune.shared.domain.settings.BlockedSongRef
import moe.rukamori.archivetune.shared.domain.settings.parseBlockedSongRefs
import moe.rukamori.archivetune.shared.domain.settings.serializeBlockedSongRefs

/**
 * The recommendation block list the Android host applies to home, related and radio feeds, replayed against the
 * shared filters and [PortableQueueOwner]: a blocked YouTube song leaves shelf pages and every queue-seeding read;
 * entries of another kind or provider pass through, and an emptied shelf leaves its page.
 */
class BlockedSongFilterTest {
    private val scope = TestScope(UnconfinedTestDispatcher())

    @Test
    fun entryFilterDropsBlockedYouTubeSongsOnly() {
        val entries = listOf(
            YouTubeEntry(ContentKind.SONG, "v1", "keep"),
            YouTubeEntry(ContentKind.SONG, "v2", "blocked"),
            YouTubeEntry(ContentKind.ALBUM, "v2", "album shares the id"),
            YouTubeEntry(ContentKind.PLAYLIST, "p1", "playlist"),
        )
        val blocked = setOf(
            BlockedSongRef(ContentProvider.YOUTUBE.id, "v2"),
            // A ref of another provider cannot match a YouTube feed.
            BlockedSongRef(ContentProvider.LOCAL.id, "v1"),
        )
        val filtered = entries.filterBlockedSongs(blocked)
        assertEquals(listOf("v1", "v2", "p1"), filtered.map { it.id })
        assertEquals(ContentKind.ALBUM, filtered[1].kind)
        assertTrue(entries.filterBlockedSongs(emptySet()) === entries)
    }

    @Test
    fun shelfPageDropsEmptiedShelvesAndKeepsTheRest() {
        val page = YouTubeShelfPage(
            shelves = listOf(
                YouTubeShelf(key = "drop", title = "blocked only", entries = listOf(songEntry("b1"))),
                YouTubeShelf(
                    key = "keep",
                    title = "mixed",
                    entries = listOf(songEntry("b1"), songEntry("v1")),
                    numbered = true,
                ),
            ),
            continuation = "next",
        )
        val filtered = page.filterBlockedSongs(setOf(BlockedSongRef(ContentProvider.YOUTUBE.id, "b1")))
        assertEquals(listOf("keep"), filtered.shelves.map { it.key })
        assertEquals(listOf("v1"), filtered.shelves.single().entries.map { it.id })
        assertTrue(filtered.shelves.single().numbered)
        assertEquals("next", filtered.continuation)
        assertTrue(page.filterBlockedSongs(emptySet()) === page)
    }

    @Test
    fun finiteAndRadioReadsDropBlockedSongs() = scope.runTest {
        var blockedSongs = setOf(BlockedSongRef(ContentProvider.YOUTUBE.id, "b1"))
        val locators = object : QueueLocatorResolver {
            override suspend fun locate(occurrence: ContentOccurrence): PlaybackLocator =
                PlaybackLocator.Remote("https://cdn.example/stream")
        }
        val port = FakePlaybackPort()
        val youtube = FakeYouTubeRepository()
        val owner = PortableQueueOwner(
            player = MusicPlayerController(port, backgroundScope),
            scope = backgroundScope,
            content = ContentRepositoryRouter(listOf(youtube)),
            locators = locators,
            repeatMode = { QueueRepeatMode.OFF },
            autoLoadMoreEnabled = { true },
            blockedSongRefs = { blockedSongs },
        )
        youtube.playlistPages += page(song("p1"), song("b1"), song("p2"))
        // The confirmed-complete finite load seeds radio immediately; the blocked song leaves both reads.
        youtube.radioPages += page(song("b1"), song("r1"), song("r2"))
        owner.withQueueAccess { owner.loadCollection(QueueLoadRequest(playlistRequest("PL1"), QueueCollectionKind.FINITE)) }
        assertEquals(listOf("p1", "p2", "r1", "r2"), owner.state.value.entries.map { it.occurrenceId })

        // Unblocking is live: the same reads no longer drop the ref.
        blockedSongs = emptySet()
        youtube.playlistPages += page(song("p1"), song("b1"))
        owner.withQueueAccess { owner.loadCollection(QueueLoadRequest(playlistRequest("PL2"), QueueCollectionKind.FINITE)) }
        assertEquals(listOf("p1", "b1"), owner.state.value.entries.map { it.occurrenceId })
    }

    @Test
    fun blockedRefRoundTripsThroughThePreferenceCodec() {
        val refs = parseBlockedSongRefs("youtube:v1, youtube:v2 ,broken")
        assertEquals(
            listOf(BlockedSongRef("youtube", "v1"), BlockedSongRef("youtube", "v2")),
            refs,
        )
        assertEquals("youtube:v1,youtube:v2", serializeBlockedSongRefs(refs))
    }

    private fun songEntry(id: String) = YouTubeEntry(ContentKind.SONG, id, "song-$id")

    private fun playlistRequest(id: String) = ContentRequest(
        provider = ContentProvider.YOUTUBE,
        surface = ContentSurface.PLAYLIST,
        parent = ContentRef(ContentProvider.YOUTUBE.id, ContentKind.PLAYLIST, id),
    )

    private fun song(id: String) = ContentOccurrence(
        occurrenceId = id,
        item = ContentItem(
            ref = ContentRef(ContentProvider.YOUTUBE.id, ContentKind.SONG, id),
            title = "song-$id",
            artists = listOf(ContentCredit(name = "artist", id = "artist")),
        ),
        setVideoId = id,
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

    private class FakeYouTubeRepository : TypedContentRepository {
        override val provider = ContentProvider.YOUTUBE
        val playlistPages = ArrayDeque<ContentPage>()
        val radioPages = ArrayDeque<ContentPage>()
        var radioPageCalls = 0

        override suspend fun page(request: ContentRequest, continuation: String?): ContentPage =
            when (request.surface) {
                ContentSurface.RADIO -> {
                    radioPageCalls++
                    radioPages.removeFirstOrNull() ?: ContentPage(emptyList())
                }
                else -> playlistPages.removeFirstOrNull() ?: ContentPage(emptyList())
            }

        override suspend fun item(ref: ContentRef): ContentItem = throw UnsupportedOperationException("unused in tests")
    }
}
