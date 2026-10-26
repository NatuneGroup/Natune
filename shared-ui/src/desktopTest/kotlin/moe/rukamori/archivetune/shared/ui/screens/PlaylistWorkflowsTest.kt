package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.library.PlaylistSortType
import moe.rukamori.archivetune.shared.ui.navigation.writeM4Screenshot
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class PlaylistWorkflowsTest {
    @Test fun collectionKeysPreserveOpaqueProviderKindAndIdBoundaries() {
        val ref = ContentRef("a-b", ContentKind.PLAYLIST, "c")
        assertNotEquals(ref.collectionUiKey(), ref.copy(provider = "a", id = "b-c").collectionUiKey())
        assertNotEquals(ref.collectionUiKey(), ref.copy(kind = ContentKind.ALBUM).collectionUiKey())
    }

    @Test fun tagEditorRetainsFailedDraftAndDeletionRequiresConfirmation() = runComposeUiTest {
        var saves = 0
        var deleted: String? = null
        var state by mutableStateOf(PlaylistTagsUi(
            listOf(PlaylistTagUi("id", "Tag", Color.Red)),
            editor = PlaylistTagEditorUi("id", "Draft", Color.Blue, listOf(Color.Blue, Color.Red), true),
            failed = true))
        setContent { MaterialTheme {
            PlaylistTagsScreen(state, PlaylistTagsActions({}, {}, {}, {}, {}, {}, { saves++ },
                { state = state.copy(editor = null) }, { deleted = it }, {}), Modifier.size(600.dp, 850.dp))
        } }
        onNodeWithTag("tag-editor-save").performClick()
        assertEquals(1, saves)
        onNodeWithText("Draft").assertExists()
        runOnIdle { state = state.copy(busy = true) }
        onNodeWithTag("tag-editor-save").assertIsNotEnabled()
        runOnIdle { state = state.copy(busy = false) }
        onNodeWithTag("playlist-tag-editor").writeM4Screenshot("m4-playlist-tag-editor.png")
        onNodeWithText("Cancel").performClick()
        onNodeWithTag("tag-delete-id").performClick()
        assertNull(deleted)
        onNodeWithTag("tag-confirm-delete").performClick()
        assertEquals("id", deleted)
    }

    @Test fun collectionCustomOrderIgnoresDirectionAndTagsUseBaselineAnyMatch() {
        val one = ContentItem(ContentRef("local", ContentKind.PLAYLIST, "a"), "First")
        val rows = listOf(
            PlaylistCollectionRow(one, 2, tagIds = setOf("red")),
            PlaylistCollectionRow(one.copy(ref = one.ref.copy(id = "b"), title = "Second"), 3, tagIds = setOf("blue")),
            PlaylistCollectionRow(one.copy(ref = one.ref.copy(id = "hidden")), 2, hidden = true, tagIds = setOf("red")),
        )
        val state = PlaylistCollectionUi(rows, filterTagIds = setOf("red", "blue"))
        assertEquals(listOf("a", "b"), visiblePlaylists(state).map { it.item.ref.id })
        assertEquals(listOf("b", "a"), visiblePlaylists(state.copy(sort = PlaylistSortType.NAME)).map { it.item.ref.id })
    }

    @Test fun collectionReorderingUsesNeighboursAndLockedListCannotWrite() = runComposeUiTest {
        var locked by mutableStateOf(true)
        var moved: Triple<ContentRef, ContentRef, Boolean>? = null
        val ref = ContentRef("local", ContentKind.PLAYLIST, "a")
        val second = ref.copy(id = "b")
        setContent { MaterialTheme {
            PlaylistCollectionScreen(PlaylistCollectionUi(listOf(
                PlaylistCollectionRow(ContentItem(ref, "First"), 2),
                PlaylistCollectionRow(ContentItem(second, "Second"), 3)), locked = locked),
                PlaylistCollectionActions({}, {}, { _, _ -> }, {}, {}, {}, { locked = !locked }, {}, {}, {}, {}, {}, {},
                    { a, b, after -> moved = Triple(a, b, after) }, {}),
                { _, modifier -> Box(modifier) }, Modifier.size(760.dp, 1000.dp))
        } }
        onNodeWithTag("playlist-down-a").assertDoesNotExist()
        onNodeWithTag("playlist-order-lock").performClick()
        onNodeWithTag("playlist-down-a").performClick()
        assertEquals(Triple(ref, second, true), moved)
        onRoot().writeM4Screenshot("m4-playlist-collection.png")
    }

    @Test fun createTrimsDraftGatesSyncAndDoesNotDismissUntilCommitted() = runComposeUiTest {
        var state by mutableStateOf(CreatePlaylistUi(name = "  Name  ", failed = true))
        var submitted: Pair<String, Boolean>? = null
        var dismissed = false
        setContent { MaterialTheme {
            CreatePlaylistDialog(state, CreatePlaylistActions({ state = state.copy(name = it) },
                { state = state.copy(syncRequested = it) }, { n, s -> submitted = n to s }, { dismissed = true }))
        } }
        onNodeWithTag("create-playlist-submit").performClick()
        assertEquals("Name" to false, submitted)
        assertFalse(dismissed)
        onNodeWithTag("create-playlist-name").assertExists()
        runOnIdle { state = state.copy(syncRequested = true) }
        onNodeWithTag("create-playlist-submit").assertIsNotEnabled()
        runOnIdle { state = state.copy(signedIn = true, syncEnabled = true) }
        onNodeWithTag("create-playlist-submit").performClick()
        assertEquals("Name" to true, submitted)
        runOnIdle { state = state.copy(submitting = true) }
        onNodeWithTag("create-playlist-submit").assertIsNotEnabled()
        onNodeWithTag("create-playlist-dialog").writeM4Screenshot("m4-create-playlist.png")
    }

    @Test fun bulkTagsUseTagAndPlaylistIdsNotDisplayNames() = runComposeUiTest {
        val ref = ContentRef("local", ContentKind.PLAYLIST, "LP1")
        var saved: Pair<Set<String>, Set<ContentRef>>? = null
        var state by mutableStateOf(AssignPlaylistTagsUi(
            tags = listOf(PlaylistTagUi("tag-1", "Same", Color.Red), PlaylistTagUi("tag-2", "Same", Color.Blue)),
            selectedTagIds = setOf("tag-2", "deleted"),
            playlists = listOf(PlaylistChoiceUi(ref, "Same", 2)), selectedPlaylists = setOf(ref)))
        setContent { MaterialTheme {
            AssignPlaylistTagsDialog(state, AssignPlaylistTagsActions({}, {},
                { tags, lists -> saved = tags to lists }, {}, {}))
        } }
        onNodeWithTag("assign-tags-save").performClick()
        assertEquals(setOf("tag-2") to setOf(ref), saved)
        runOnIdle { state = state.copy(selectedPlaylists = emptySet()) }
        onNodeWithTag("assign-tags-save").assertIsNotEnabled()
    }

    @Test fun exportPickerOnlyOffersNonemptyCsvAndKeepsOpenOnFailure() = runComposeUiTest {
        val ref = ContentRef("local", ContentKind.PLAYLIST, "LP1")
        var chosen: ContentRef? = null
        setContent { MaterialTheme {
            PlaylistExportDialog(PlaylistExportUi(listOf(PlaylistChoiceUi(ref, "Ready", 2),
                PlaylistChoiceUi(ref.copy(id = "empty"), "Empty", 0)), failed = true),
                { chosen = it }, {})
        } }
        onNodeWithText("Empty").assertDoesNotExist()
        onNodeWithText("Ready").performClick()
        assertEquals(ref, chosen)
        onNodeWithTag("playlist-export").assertExists()
    }
}
