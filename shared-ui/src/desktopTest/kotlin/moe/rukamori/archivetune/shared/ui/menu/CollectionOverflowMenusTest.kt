package moe.rukamori.archivetune.shared.ui.menu

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.queue.*
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.ui.resources.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class CollectionOverflowMenusTest {
    @Test fun completeCollectionKeepsDuplicateMediaOccurrencesForEveryQueueCommand() = runBlocking {
        val queue = Queue()
        val actions = collectionQueueActions(complete(), queue)
        actions.single { it.id == "play" }.run()
        assertEquals(listOf("first", "second"), queue.last.map { it.occurrenceId })
        assertEquals(1, queue.last.map { it.item.ref.id }.distinct().size)
        actions.single { it.id == "shuffle" }.run()
        assertEquals(setOf("first", "second"), queue.last.map { it.occurrenceId }.toSet())
        actions.single { it.id == "play_next" }.run()
        assertEquals(UserQueuedKind.PLAY_NEXT, queue.kind)
        actions.single { it.id == "add_queue" }.run()
        assertEquals(UserQueuedKind.ADD_TO_QUEUE, queue.kind)
    }

    @Test fun unknownOrIncompleteCollectionNeverRunsAWholeListSideEffect() = runBlocking {
        val queue = Queue()
        collectionQueueActions(null, queue).forEach { action ->
            assertFalse(action.enabled)
            assertFailsWith<IllegalArgumentException> { action.run() }
        }
        assertTrue(queue.last.isEmpty())
    }

    @Test fun actualCollectionPickerCommitsSelectedPlaylistAndBothOccurrences() = runComposeUiTest {
        val library = Library()
        val playlist = ContentItem(ContentRef("local", ContentKind.PLAYLIST, "LP1"), "Destination")
        var dismissed = 0
        setContent {
            MaterialTheme {
                CollectionOverflowMenu("album", true, complete(), Queue(), library, listOf(playlist),
                    onDismiss = { dismissed++ }, onFailure = { error("Mutation failed") })
            }
        }
        onNodeWithTag("menu:add_playlist").performClick()
        onNodeWithTag("playlist:LP1").performClick()
        waitUntil { library.mutation != null }
        val mutation = library.mutation as LibraryMutation.AddToPlaylist
        assertEquals("LP1", mutation.playlistId)
        assertEquals(listOf("first", "second"), mutation.occurrences.map { it.occurrenceId })
        assertEquals(1, dismissed)
    }

    @Test fun renameDialogRequiresConfirmationAndUsesWirePlaylistId() = runComposeUiTest {
        val library = Library()
        val playlist = ContentItem(ContentRef("local", ContentKind.PLAYLIST, "LP1"), "Before")
        setContent {
            MaterialTheme {
                PlaylistRenameMenu(playlist, true, false, true, library, {}, { error("Mutation failed") })
            }
        }
        onNodeWithTag("menu:rename").performClick()
        onNodeWithTag("playlist-name").performTextReplacement("After")
        assertNull(library.mutation)
        onNodeWithText("Save").performClick()
        waitUntil { library.mutation != null }
        assertEquals(LibraryMutation.RenamePlaylist("LP1", "After"), library.mutation)
    }

    @Test fun downloadPlaylistsCannotBeRenamed() = runComposeUiTest {
        val playlist = ContentItem(ContentRef("local", ContentKind.PLAYLIST, "DL"), "Downloads")
        setContent { MaterialTheme {
            PlaylistRenameMenu(playlist, true, false, true, Library(), {}, {}, downloadPlaylist = true)
        } }
        onNodeWithTag("menu:rename").assertDoesNotExist()
    }

    @Test fun lateActionCannotDismissAReopenedMenuWithTheSameSelectedIdentity() = runComposeUiTest {
        val began = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()
        var expanded by mutableStateOf(true)
        var dismissed = 0
        setContent { MaterialTheme {
            SharedOverflowMenu("same-song", expanded, { dismissed++ }, listOf(
                OverflowAction("late", Res.string.m6_play) { began.complete(Unit); finish.await(); done.complete(Unit) }
            ), { error("Unexpected failure") })
        } }
        onNodeWithTag("menu:late").performClick()
        waitUntil { began.isCompleted }
        runOnIdle { expanded = false }
        waitForIdle()
        runOnIdle { expanded = true }
        waitForIdle()
        runOnIdle { finish.complete(Unit) }
        waitUntil { done.isCompleted }
        waitForIdle()
        assertEquals(0, dismissed)
        onNodeWithTag("menu:late").assertIsEnabled()
    }

    private fun complete(): FinitePlaylistCompleteList<ContentOccurrence> {
        val item = ContentItem(ContentRef("youtube", ContentKind.SONG, "duplicate-song"), "Song")
        return FinitePlaylistCompleteList("Album", listOf("first", "second").mapIndexed { index, id ->
            FinitePlaylistSong(ContentOccurrence(id, item), item.ref.id, id, index)
        })
    }
    private class Queue : QueueController {
        override val player: MusicPlayerController get() = error("These commands must use the queue owner")
        override val state = MutableStateFlow(QueueState(0, emptyList(), null, emptyList()))
        var last = emptyList<ContentOccurrence>()
        var kind: UserQueuedKind? = null
        override suspend fun replace(entries: List<ContentOccurrence>, startOccurrenceId: String, playWhenReady: Boolean) { last = entries }
        override suspend fun insert(entries: List<ContentOccurrence>, kind: UserQueuedKind) { last = entries; this.kind = kind }
        override suspend fun remove(occurrenceIds: Set<String>) = error("Not called")
        override suspend fun move(occurrenceId: String, beforeOccurrenceId: String?) = error("Not called")
        override suspend fun select(occurrenceId: String, positionMs: Long) = error("Not called")
    }
    private class Library : LibraryRepository {
        var mutation: LibraryMutation? = null
        override suspend fun mutate(mutation: LibraryMutation) { this.mutation = mutation }
        override suspend fun item(ref: ContentRef): ContentItem = error("Not called")
        override suspend fun page(query: ContentQuery, continuation: String?) = ContentPage(emptyList())
        override fun observe(query: ContentQuery) = flowOf(ContentPage(emptyList()))
    }
}
