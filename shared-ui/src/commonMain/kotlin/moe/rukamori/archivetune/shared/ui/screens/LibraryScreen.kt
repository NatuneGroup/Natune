package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.library.*
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import org.jetbrains.compose.resources.stringResource

@Composable
fun LibraryScreen(
    filter: LibraryFilter,
    filterOrder: List<LibraryFilter>,
    onFilter: (LibraryFilter) -> Unit,
    state: PagedContentState,
    actions: ContentActions,
    artwork: ContentArtwork,
    sourceSelector: @Composable () -> Unit,
    headerActions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = LocalShellContentPadding.current,
    /** The mixed section's shortcut cards, recent rail and Top mixes, drawn above its rows. Null draws none. */
    mixHeader: LibraryMixHeaderBinding? = null,
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Text(stringResource(Res.string.m4_library), style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(16.dp))
        sourceSelector()
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filterOrder.toLibraryFilterPreference().toLibraryFilterOrder(), key = { it.name }) { option ->
                FilterChip(option == filter, { onFilter(option) },
                    modifier = Modifier.testTag("library-filter-${option.name}"),
                    label = { Text(stringResource(when (option) {
                        LibraryFilter.LIBRARY -> Res.string.m4_library
                        LibraryFilter.PLAYLISTS -> Res.string.m4_playlists
                        LibraryFilter.SONGS -> Res.string.m4_songs
                        LibraryFilter.ARTISTS -> Res.string.m4_artists
                        LibraryFilter.ALBUMS -> Res.string.m4_albums
                    })) })
            }
        }
        headerActions()
        LazyColumn(Modifier.weight(1f), contentPadding = contentPadding) {
            if (filter == LibraryFilter.LIBRARY) mixHeader?.let { libraryMixHeader(it.state, it.actions, artwork) }
            if (filter != LibraryFilter.LIBRARY || mixHeader?.replacesRows != true) {
                contentRows(state, actions, artwork)
            }
        }
    }
}
