package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.host.LocalAndroidPhonePresentation
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import org.jetbrains.compose.resources.painterResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.library.*
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.navigation.LocalDesktopLayout
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import moe.rukamori.archivetune.shared.ui.theme.desktopPageTitle
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
    settings: SettingsStore? = null,
    preferences: PreferenceSnapshot = PreferenceSnapshot.EMPTY,
    onFailure: () -> Unit = {},
) {
    val phone = LocalAndroidPhonePresentation.current
    val scope = rememberCoroutineScope()
    var grid by rememberSaveable(filter) { mutableStateOf(filter == LibraryFilter.ARTISTS || filter == LibraryFilter.ALBUMS) }
    val currentGrid = if (phone && filter == LibraryFilter.PLAYLISTS) preferences[PlaylistViewTypeKey, "LIST"] == "GRID" else grid
    fun toggleGrid() {
        if (filter == LibraryFilter.PLAYLISTS && settings != null) scope.launch {
            try { settings.edit { this[PlaylistViewTypeKey] = if (currentGrid) "LIST" else "GRID" } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { onFailure() }
        } else grid = !grid
    }
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // The reference desktop library starts with its filter chips; the selected rail destination names the page.
        if (!LocalDesktopLayout.current && !phone) {
            Text(stringResource(Res.string.m4_library), style = desktopPageTitle(false),
                modifier = Modifier.padding(16.dp))
        }
        if (phone) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { headerActions() }
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
        if (!phone) headerActions()
        if (phone && settings != null && state.request?.provider != moe.rukamori.archivetune.shared.domain.repository.ContentProvider.SPOTIFY_CATALOG) PhoneLibraryToolbar(filter, preferences, settings, state.items.size, currentGrid, ::toggleGrid, onFailure)
        LazyColumn(Modifier.weight(1f), contentPadding = contentPadding) {
            if (filter == LibraryFilter.LIBRARY) mixHeader?.let { libraryMixHeader(it.state, it.actions, artwork) }
            if (filter != LibraryFilter.LIBRARY || mixHeader?.replacesRows != true) {
                if (phone && currentGrid) {
                    items(state.items.chunked(2), key = { it.first().occurrenceId }) { group ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            group.forEach { row -> Column(Modifier.weight(1f).clickable { actions.open(row) }) {
                                artwork(row.item, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)))
                                Text(row.item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row {
                                    Text(row.item.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    actions.more?.let { more -> IconButton(onClick = { more(row) }) { Icon(painterResource(Res.drawable.m4hv_icon_more_vert), stringResource(Res.string.m4_more)) } }
                                }
                            } }
                            if (group.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    item(key = "library-status") { PageStatus(state, actions) }
                } else contentRows(state, actions, artwork)
            }
        }
    }
}
