package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.library.LibraryDefaults
import moe.rukamori.archivetune.shared.domain.library.LibrarySortState
import moe.rukamori.archivetune.shared.domain.library.librarySortState
import moe.rukamori.archivetune.shared.domain.playlisttags.PlaylistTag
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.settings.PlaylistEditLockKey
import moe.rukamori.archivetune.shared.domain.settings.PlaylistSortDescendingKey
import moe.rukamori.archivetune.shared.domain.settings.PlaylistSortTypeKey
import moe.rukamori.archivetune.shared.ui.IosHost
import moe.rukamori.archivetune.shared.ui.host.LibraryContentOwner
import moe.rukamori.archivetune.shared.ui.host.PlaylistTagPalette
import moe.rukamori.archivetune.shared.ui.host.playlistTagColor
import moe.rukamori.archivetune.shared.ui.host.playlistTagHex
import moe.rukamori.archivetune.shared.ui.host.randomLocalPlaylistId
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveDestination
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoute
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoutes
import moe.rukamori.archivetune.shared.ui.screens.AssignPlaylistTagsActions
import moe.rukamori.archivetune.shared.ui.screens.AssignPlaylistTagsDialog
import moe.rukamori.archivetune.shared.ui.screens.AssignPlaylistTagsUi
import moe.rukamori.archivetune.shared.ui.screens.CreatePlaylistActions
import moe.rukamori.archivetune.shared.ui.screens.CreatePlaylistDialog
import moe.rukamori.archivetune.shared.ui.screens.CreatePlaylistUi
import moe.rukamori.archivetune.shared.ui.screens.PlaylistChoiceUi
import moe.rukamori.archivetune.shared.ui.screens.PlaylistCollectionActions
import moe.rukamori.archivetune.shared.ui.screens.PlaylistCollectionRow
import moe.rukamori.archivetune.shared.ui.screens.PlaylistCollectionUi
import moe.rukamori.archivetune.shared.ui.screens.PlaylistTagEditorUi
import moe.rukamori.archivetune.shared.ui.screens.PlaylistTagUi
import moe.rukamori.archivetune.shared.ui.screens.PlaylistTagsActions
import moe.rukamori.archivetune.shared.ui.screens.PlaylistTagsUi

/** The tag route of the shared graph. */
private const val IOS_TAGS_ROUTE = "library/tags"

/**
 * The create-playlist dialog of this host. The draft lives here, so the page's create action and the overlay share it.
 * The save goes through the one library owner, and a saved playlist opens through [openRoute]. A failed save keeps the
 * draft for another attempt.
 */
internal class IosPlaylistCreation(
    private val scope: CoroutineScope,
    private val content: LibraryContentOwner,
    private val openRoute: (String) -> Unit,
) {
    var visible by mutableStateOf(false)
        private set
    var name by mutableStateOf("")
        private set
    var submitting by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    fun show() {
        name = ""
        failed = false
        visible = true
    }

    fun rename(value: String) {
        name = value
        failed = false
    }

    /** The dialog offers no dismiss while a save runs, so a dismiss during the save is ignored. */
    fun dismiss() {
        if (!submitting) close()
    }

    /** Drops the draft at once. A save still running completes but opens no playlist, because its page is gone. */
    fun close() {
        visible = false
        name = ""
        failed = false
    }

    fun submit(title: String) {
        if (submitting || title.isBlank()) return
        submitting = true
        failed = false
        scope.launch {
            val id = randomLocalPlaylistId()
            val saved = try {
                content.createPlaylist(id, title)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            submitting = false
            if (!saved) {
                failed = true
                return@launch
            }
            val stillOpen = visible
            close()
            if (stillOpen) openRoute(ArchiveRoutes.content(ContentRef(ContentProvider.LOCAL.id, ContentKind.PLAYLIST, id)))
        }
    }
}

/** The tag assignment dialog's target: one playlist, or the bulk picker when [playlist] is null. */
internal class IosPlaylistTagsState(private val openRoute: (String) -> Unit) {
    data class Target(val playlist: ContentRef?)

    var target: Target? by mutableStateOf(null)
        private set

    fun openTags(ref: ContentRef) {
        target = Target(ref)
    }

    fun openBulkTags() {
        target = Target(null)
    }

    fun dismiss() {
        target = null
    }

    fun manageTags() {
        target = null
        openRoute(IOS_TAGS_ROUTE)
    }
}

/** The local playlists with their tag ids, so the Playlists page can filter by tag. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun IosSessionGraph.taggedPlaylistRows(): Flow<List<PlaylistCollectionRow>> =
    libraryContent.playlistRows().flatMapLatest { rows ->
        if (rows.isEmpty()) {
            flowOf(rows)
        } else {
            combine(
                rows.map { row ->
                    playlistTags.observePlaylistTags(row.item.ref.id).map { assigned ->
                        row.copy(tagIds = assigned.mapTo(HashSet()) { it.id }, exportable = true)
                    }
                },
            ) { it.toList() }
        }
    }

/**
 * The local playlists with their real counts, tags, CSV export and custom order.
 */
@Composable
internal fun iosPlaylistsPage(
    graph: IosSessionGraph,
    host: IosHost,
    creation: IosPlaylistCreation,
    tagState: IosPlaylistTagsState,
    menu: IosItemMenuState,
): ArchiveGraphPage {
    val content = graph.libraryContent
    val snapshot by graph.settings.snapshots.collectAsState()
    var attempt by remember { mutableStateOf(0) }
    val rows by remember(attempt) { graph.taggedPlaylistRows() }.collectAsState(null)
    val tags by remember(attempt) { graph.playlistTags.observeTags() }.collectAsState(emptyList())
    var filterTagIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var query by remember { mutableStateOf("") }
    var grid by remember { mutableStateOf(false) }
    var showHidden by remember { mutableStateOf(false) }
    val sort = snapshot?.librarySortState(PlaylistSortTypeKey, PlaylistSortDescendingKey, LibraryDefaults.playlistSort)
        ?: LibraryDefaults.playlistSort
    val locked = snapshot?.get(PlaylistEditLockKey) ?: true
    val tagIds = tags.mapTo(HashSet()) { it.id }
    val state = PlaylistCollectionUi(
        rows = rows.orEmpty(),
        tags = tags.map { it.toUi() },
        // A deleted tag drops out of the filter, so it can never hide every row.
        filterTagIds = filterTagIds intersect tagIds,
        query = query,
        sort = sort.type,
        descending = sort.descending,
        showHidden = showHidden,
        locked = locked,
        grid = grid,
        loading = rows == null || snapshot == null,
    )
    fun write(refusal: String, change: suspend () -> Unit) {
        graph.scope.launch {
            try {
                change()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("$refusal: ${failure::class.simpleName}")
            }
        }
    }
    val actions = PlaylistCollectionActions(
        // The navigation host supplies back, the tags route and the playlist route for this page.
        back = {},
        query = { query = it },
        sort = { type, descending ->
            write("the playlist order was not saved") { content.setPlaylistListSort(LibrarySortState(type, descending)) }
        },
        showHidden = { showHidden = !showHidden },
        filterTag = { id -> filterTagIds = if (id in filterTagIds) filterTagIds - id else filterTagIds + id },
        grid = { grid = !grid },
        lock = { write("the playlist lock was not saved") { content.setPlaylistLocked(!locked) } },
        create = { creation.show() },
        // The same tags page the settings row opens; the page carries the manager, the filter chips just read its ids.
        manageTags = tagState::manageTags,
        open = {},
        // A local playlist row's overflow opens the shared playlist menu on a synthesized occurrence of the row.
        more = { ref ->
            val row = rows.orEmpty().firstOrNull { it.item.ref == ref }
            if (row != null && ref.provider == ContentProvider.LOCAL.id) {
                menu.open(ContentOccurrence("menu:${ref.provider}:${ref.id}", row.item))
            } else {
                menu.refuse()
            }
        },
        assignTags = { ref -> if (ref.provider == ContentProvider.LOCAL.id) tagState.openTags(ref) else menu.refuse() },
        exportCsv = { ref ->
            val name = rows.orEmpty().firstOrNull { it.item.ref == ref }?.item?.title ?: ref.id
            write("the playlist CSV could not be exported") { iosExportPlaylistCsv(host, graph, ref, name) }
        },
        move = { ref, neighbour, after ->
            if (ref.provider == ContentProvider.LOCAL.id && neighbour.provider == ContentProvider.LOCAL.id) {
                write("the playlist move was not saved") {
                    graph.library.mutate(
                        moe.rukamori.archivetune.shared.domain.repository.LibraryMutation.MovePlaylist(
                            ref.id,
                            neighbour.id,
                            after,
                        ),
                    )
                }
            } else {
                menu.refuse()
            }
        },
        retry = { attempt += 1 },
    )
    return ArchiveGraphPage.Playlists(state, actions)
}

/** The Manage tags page: the tag catalogue of the portable file, with the baseline tag palette. */
@Composable
internal fun iosTagsPage(graph: IosSessionGraph, tagState: IosPlaylistTagsState): ArchiveGraphPage {
    var attempt by remember { mutableStateOf(0) }
    val tags by remember(attempt) { graph.playlistTags.observeTags() }.collectAsState(null)
    var editor by remember { mutableStateOf<PlaylistTagEditorUi?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun write(change: suspend () -> Unit, done: () -> Unit = {}) {
        if (busy) return
        busy = true
        failed = false
        scope.launch {
            try {
                change()
                done()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("tag change not saved: ${failure::class.simpleName}")
                failed = true
            } finally {
                busy = false
            }
        }
    }
    val state = PlaylistTagsUi(
        tags = tags.orEmpty().map { it.toUi() },
        editor = editor,
        loading = tags == null,
        busy = busy,
        failed = failed,
    )
    val actions = PlaylistTagsActions(
        back = {},
        retry = { attempt += 1 },
        create = { editor = PlaylistTagEditorUi(null, "", PlaylistTagPalette.first(), PlaylistTagPalette, canSave = true) },
        edit = { id ->
            tags?.firstOrNull { it.id == id }?.let { tag ->
                editor = PlaylistTagEditorUi(tag.id, tag.name, playlistTagColor(tag.color), PlaylistTagPalette, canSave = true)
            }
        },
        name = { value -> editor = editor?.copy(name = value) },
        color = { value -> editor = editor?.copy(color = value) },
        save = {
            editor?.let { current ->
                val name = current.name.trim()
                val color = playlistTagHex(current.color)
                write({
                    val id = current.id
                    if (id == null) graph.playlistTags.createTag(name, color) else graph.playlistTags.updateTag(id, name, color)
                }) { editor = null }
            }
        },
        cancelEdit = { editor = null },
        delete = { id -> write({ graph.playlistTags.deleteTag(id) }) },
        bulkAssign = tagState::openBulkTags,
    )
    return ArchiveGraphPage.Tags(state, actions)
}

/**
 * The create dialog and the tag dialog over the Playlists and Tags routes. Leaving the route drops both, so a stale
 * draft or a dialog for another page never reopens later.
 */
@Composable
internal fun IosPlaylistOverlays(
    creation: IosPlaylistCreation,
    tagState: IosPlaylistTagsState,
    graph: IosSessionGraph,
    route: ArchiveRoute,
) {
    DisposableEffect(route.value) {
        onDispose {
            creation.close()
            tagState.dismiss()
        }
    }
    if (route.destination == ArchiveDestination.PLAYLISTS && creation.visible) {
        CreatePlaylistDialog(
            // This host syncs no new playlist to an account, so the sync choice stays hidden and never reaches an owner.
            state = CreatePlaylistUi(
                name = creation.name, allowSyncing = false, submitting = creation.submitting, failed = creation.failed,
            ),
            actions = CreatePlaylistActions(
                name = creation::rename,
                sync = { },
                create = { title, _ -> creation.submit(title) },
                dismiss = creation::dismiss,
            ),
        )
    }
    tagState.target?.let { target -> IosAssignTagsDialog(tagState, graph, target) }
}

@Composable
private fun IosAssignTagsDialog(state: IosPlaylistTagsState, graph: IosSessionGraph, target: IosPlaylistTagsState.Target) {
    val catalog by remember { graph.playlistTags.observeTags() }.collectAsState(null)
    val playlists by remember(target) {
        if (target.playlist == null) graph.playlistTags.observeEditablePlaylists() else flowOf(emptyList())
    }.collectAsState(emptyList())
    var selectedTags by remember(target) { mutableStateOf<Set<String>>(emptySet()) }
    var selectedPlaylists by remember(target) { mutableStateOf<Set<ContentRef>>(emptySet()) }
    var loaded by remember(target) { mutableStateOf(target.playlist == null) }
    var busy by remember(target) { mutableStateOf(false) }
    var failed by remember(target) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(target) {
        val playlist = target.playlist ?: return@LaunchedEffect
        try {
            selectedTags = graph.playlistTags.observePlaylistTags(playlist.id).first().mapTo(HashSet()) { it.id }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            graph.report("playlist tags not read: ${failure::class.simpleName}")
            failed = true
        }
        loaded = true
    }
    AssignPlaylistTagsDialog(
        state = AssignPlaylistTagsUi(
            tags = catalog.orEmpty().map { it.toUi() },
            selectedTagIds = selectedTags,
            playlists = if (target.playlist == null) {
                playlists.map { PlaylistChoiceUi(ContentRef(ContentProvider.LOCAL.id, ContentKind.PLAYLIST, it.id), it.name, it.songCount) }
            } else {
                null
            },
            selectedPlaylists = selectedPlaylists,
            loading = catalog == null || !loaded,
            busy = busy,
            failed = failed,
        ),
        actions = AssignPlaylistTagsActions(
            tag = { id -> selectedTags = if (id in selectedTags) selectedTags - id else selectedTags + id },
            playlist = { ref -> selectedPlaylists = if (ref in selectedPlaylists) selectedPlaylists - ref else selectedPlaylists + ref },
            save = { tagIds, refs ->
                busy = true
                failed = false
                scope.launch {
                    try {
                        val playlist = target.playlist
                        if (playlist != null) {
                            graph.playlistTags.replacePlaylistTags(playlist.id, tagIds.toList())
                        } else {
                            graph.playlistTags.addTagsToPlaylists(refs.map { it.id }, tagIds.toList())
                        }
                        state.dismiss()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        graph.report("playlist tags not saved: ${failure::class.simpleName}")
                        failed = true
                    } finally {
                        busy = false
                    }
                }
            },
            manage = state::manageTags,
            dismiss = state::dismiss,
        ),
    )
}

private fun PlaylistTag.toUi(): PlaylistTagUi = PlaylistTagUi(id, name, playlistTagColor(color))
