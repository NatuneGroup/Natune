/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.menu.artistalbum

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.LibraryMutation
import moe.rukamori.archivetune.shared.domain.repository.LibraryRepository
import moe.rukamori.archivetune.shared.domain.repository.QueueController
import moe.rukamori.archivetune.shared.ui.menu.ArtistChoice
import moe.rukamori.archivetune.shared.ui.menu.MenuShareHandler
import moe.rukamori.archivetune.shared.ui.menu.OverflowAction
import moe.rukamori.archivetune.shared.ui.menu.SharedOverflowMenu
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

private enum class AlbumChooser { NONE, PLAYLIST, ARTIST }

/**
 * Library album dropdown over [libraryAlbumActions], plus the add-to-playlist and artist dialogs. The dropdown closes
 * while a dialog is open, as the shared collection menu does. [artists] is the split artist list in baseline order:
 * a single artist navigates straight away; several open the chooser, which lists each name once.
 */
@Composable
fun LibraryAlbumOverflowMenu(
    identity: String,
    expanded: Boolean,
    facts: LibraryAlbumMenuFacts,
    queue: QueueController,
    library: LibraryRepository,
    sharing: MenuShareHandler,
    host: ArtistAlbumMenuHost,
    artists: List<ArtistChoice<ContentRef>>,
    onViewArtist: (ContentRef) -> Unit,
    editablePlaylists: List<ContentItem>,
    onDismiss: () -> Unit,
    onFailure: () -> Unit,
) {
    var chooser by remember(identity) { mutableStateOf(AlbumChooser.NONE) }
    var saving by remember(identity) { mutableStateOf(false) }
    val lifetime = remember(identity, expanded) { Any() }
    val latestLifetime by rememberUpdatedState(lifetime)
    val scope = rememberCoroutineScope()
    LaunchedEffect(expanded) { if (!expanded) chooser = AlbumChooser.NONE }
    val songs = facts.album.songs?.takeIf { it.isNotEmpty() }
    val commands = libraryAlbumActions(facts, queue, library, sharing, host) + buildList<OverflowAction> {
        add(OverflowAction("add_playlist", Res.string.m6_add_playlist, songs != null, dismissOnSuccess = false) {
            chooser = AlbumChooser.PLAYLIST
        })
        if (artists.isNotEmpty()) {
            add(OverflowAction("view_artist", Res.string.m6_view_artist, dismissOnSuccess = artists.size == 1) {
                if (artists.size == 1) {
                    onViewArtist(artists.single().original)
                } else {
                    chooser = AlbumChooser.ARTIST
                }
            })
        }
    }
    SharedOverflowMenu(identity, expanded && chooser == AlbumChooser.NONE, onDismiss, commands, onFailure)
    if (expanded && chooser == AlbumChooser.PLAYLIST) AlertDialog(
        onDismissRequest = { if (!saving) chooser = AlbumChooser.NONE },
        title = { Text(stringResource(Res.string.m6_add_playlist)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(editablePlaylists, key = { it.ref }) { playlist ->
                    TextButton(
                        enabled = !saving && songs != null,
                        onClick = {
                            val selected = songs
                            if (selected != null && !saving) {
                                saving = true
                                scope.launch {
                                    try {
                                        library.mutate(LibraryMutation.AddToPlaylist(playlist.ref.id, selected))
                                        chooser = AlbumChooser.NONE
                                        if (latestLifetime === lifetime) onDismiss()
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        if (latestLifetime === lifetime) onFailure()
                                    } finally {
                                        saving = false
                                    }
                                }
                            }
                        },
                        modifier = Modifier.testTag("playlist:${playlist.ref.id}"),
                    ) { Text(playlist.title) }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(enabled = !saving, onClick = { chooser = AlbumChooser.NONE }) {
                Text(stringResource(Res.string.m6_cancel))
            }
        },
    )
    if (expanded && chooser == AlbumChooser.ARTIST) AlertDialog(
        onDismissRequest = { chooser = AlbumChooser.NONE },
        title = { Text(stringResource(Res.string.m6_view_artist)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(artists.distinctBy { it.name }) { choice ->
                    TextButton(
                        onClick = {
                            chooser = AlbumChooser.NONE
                            onViewArtist(choice.original)
                            onDismiss()
                        },
                        modifier = Modifier.testTag("artist:${choice.original.id}"),
                    ) { Text(choice.name) }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { chooser = AlbumChooser.NONE }) {
                Text(stringResource(Res.string.m6_cancel))
            }
        },
    )
}

/** YouTube artist dropdown over [youTubeArtistActions]. Every host callback is required; none defaults to a no-op. */
@Composable
fun YouTubeArtistOverflowMenu(
    identity: String,
    expanded: Boolean,
    facts: YouTubeArtistMenuFacts,
    sharing: MenuShareHandler,
    host: ArtistAlbumMenuHost,
    onDismiss: () -> Unit,
    onFailure: () -> Unit,
) {
    SharedOverflowMenu(identity, expanded, onDismiss, youTubeArtistActions(facts, sharing, host), onFailure)
}

/** Library artist dropdown over [libraryArtistActions]. Every host callback is required; none defaults to a no-op. */
@Composable
fun LibraryArtistOverflowMenu(
    identity: String,
    expanded: Boolean,
    facts: LibraryArtistMenuFacts,
    sharing: MenuShareHandler,
    host: ArtistAlbumMenuHost,
    onDismiss: () -> Unit,
    onFailure: () -> Unit,
) {
    SharedOverflowMenu(identity, expanded, onDismiss, libraryArtistActions(facts, sharing, host), onFailure)
}
