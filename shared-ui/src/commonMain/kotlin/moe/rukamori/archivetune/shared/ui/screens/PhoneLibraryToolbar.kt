package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.library.*
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.component.LibrarySortHeader
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Sort/filter writes go through the same owner observed by LibraryContentOwner, never sort a loaded prefix. */
@Composable
internal fun PhoneLibraryToolbar(
    filter: LibraryFilter,
    preferences: PreferenceSnapshot,
    settings: SettingsStore,
    count: Int,
    grid: Boolean,
    onGrid: () -> Unit,
    onFailure: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    fun edit(change: PreferenceEditor.() -> Unit) {
        scope.launch {
            try { settings.edit(change) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { onFailure() }
        }
    }
    if (filter == LibraryFilter.LIBRARY) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (filter) {
            LibraryFilter.SONGS -> LibrarySortHeader(preferences.librarySortState(SongSortTypeKey, SongSortDescendingKey, LibraryDefaults.songSort), SongSortType.entries, ::phoneSortLabel,
                { next -> edit { next.writeTo(this, SongSortTypeKey, SongSortDescendingKey) } })
            LibraryFilter.ARTISTS -> LibrarySortHeader(preferences.librarySortState(ArtistSortTypeKey, ArtistSortDescendingKey, LibraryDefaults.artistSort), ArtistSortType.entries, ::phoneSortLabel,
                { next -> edit { next.writeTo(this, ArtistSortTypeKey, ArtistSortDescendingKey) } })
            LibraryFilter.ALBUMS -> LibrarySortHeader(preferences.librarySortState(AlbumSortTypeKey, AlbumSortDescendingKey, LibraryDefaults.albumSort), AlbumSortType.entries, ::phoneSortLabel,
                { next -> edit { next.writeTo(this, AlbumSortTypeKey, AlbumSortDescendingKey) } })
            LibraryFilter.PLAYLISTS -> LibrarySortHeader(preferences.librarySortState(PlaylistSortTypeKey, PlaylistSortDescendingKey, LibraryDefaults.playlistSort), PlaylistSortType.entries, ::phoneSortLabel,
                { next -> edit { next.writeTo(this, PlaylistSortTypeKey, PlaylistSortDescendingKey) } })
            else -> Unit
        }
        Spacer(Modifier.weight(1f))
        if (filter != LibraryFilter.SONGS) IconButton(onClick = onGrid) {
            Icon(painterResource(if (grid) Res.drawable.m4hv_icon_list_view else Res.drawable.m4hv_icon_grid_view), stringResource(if (grid) Res.string.m4_list_view else Res.string.m4_grid_view))
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        when (filter) {
            LibraryFilter.SONGS -> PhoneLibraryFilter(preferences[SongFilterKey].toEnum(LibraryDefaults.songFilter).name,
                listOf("LIKED" to Res.string.phone_library_liked, "DOWNLOADED" to Res.string.m4_downloads, "LIBRARY" to Res.string.m4_library),
                { value -> edit { this[SongFilterKey] = value } })
            LibraryFilter.ARTISTS -> PhoneLibraryFilter(preferences[ArtistFilterKey].toEnum(LibraryDefaults.artistFilter).name,
                listOf("LIKED" to Res.string.phone_library_liked, "LIBRARY" to Res.string.m4_library),
                { value -> edit { this[ArtistFilterKey] = value } })
            LibraryFilter.ALBUMS -> PhoneLibraryFilter(preferences[AlbumFilterKey].toEnum(LibraryDefaults.albumFilter).name,
                listOf("LIKED" to Res.string.phone_library_liked, "DOWNLOADED_FULL" to Res.string.m4_downloads, "LIBRARY" to Res.string.m4_library),
                { value -> edit { this[AlbumFilterKey] = value } })
            else -> Unit
        }
        Spacer(Modifier.weight(1f))
        Text(stringResource(Res.string.phone_library_count, count), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PhoneLibraryFilter(current: String, options: List<Pair<String, StringResource>>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(selected = current != "LIBRARY", onClick = { open = true },
            label = { Text(stringResource(options.firstOrNull { it.first == current }?.second ?: options.first().second)) },
            leadingIcon = { Icon(painterResource(Res.drawable.phone_reference_filter_alt), null) })
        DropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEach { (value, title) -> DropdownMenuItem(text = { Text(stringResource(title)) }, onClick = { open = false; onSelect(value) }) }
        }
    }
}

private fun phoneSortLabel(type: Enum<*>): StringResource = when (type.name) {
    "CREATE_DATE" -> Res.string.m4_sort_date_added
    "NAME" -> Res.string.m4_name
    "ARTIST" -> Res.string.m4_sort_artist
    "PLAY_TIME" -> Res.string.m4_sort_play_time
    "SONG_COUNT" -> Res.string.phone_library_sort_song_count
    "YEAR" -> Res.string.phone_library_sort_year
    "LENGTH" -> Res.string.phone_library_sort_length
    "LAST_UPDATED" -> Res.string.phone_library_sort_last_updated
    else -> Res.string.m4_custom_order
}
