package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.domain.library.LibraryDefaults
import moe.rukamori.archivetune.shared.domain.library.LibrarySource
import moe.rukamori.archivetune.shared.domain.settings.LibrarySourceKey
import moe.rukamori.archivetune.shared.domain.settings.toEnum

/** Android's Library source pills: YouTube Music and Spotify, offered only while Spotify can actually serve them. */
@Composable
internal fun DesktopLibrarySourceSelector(graph: DesktopSessionGraph, spotify: DesktopSpotifyAccounts) {
    val snapshot by graph.settings.snapshots.collectAsState()
    val spotifyAvailable = spotify.libraryAvailable()
    if (!spotifyAvailable) return
    val stored = snapshot?.get(LibrarySourceKey).toEnum(LibraryDefaults.source)
    val selected = stored.resolved(spotifyAvailable)
    Row(
        Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == LibrarySource.YTM,
            onClick = { graph.scope.launch { graph.settings.edit { this[LibrarySourceKey] = LibrarySource.YTM.name } } },
            label = { Text(DesktopStrings["library_source_ytm"]) },
        )
        FilterChip(
            selected = selected == LibrarySource.SPOTIFY,
            onClick = { graph.scope.launch { graph.settings.edit { this[LibrarySourceKey] = LibrarySource.SPOTIFY.name } } },
            label = { Text(DesktopStrings["home_source_spotify"]) },
        )
    }
}
