package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

data class HiddenSettingsPlaylist(val id: String, val title: String)

/** Hidden playlists retain Android's title, empty state and visibility action. */
@Composable
fun HiddenPlaylistsSettings(rows: List<HiddenSettingsPlaylist>?, onUnhide: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        LocalSettingsBack.current?.let { SettingsBackHeader(it, Res.string.m6_account_hidden_playlists) }
        when {
            rows == null -> CircularProgressIndicator(Modifier.padding(16.dp))
            rows.isEmpty() -> Text(stringResource(Res.string.m6_account_no_hidden_playlists), Modifier.padding(16.dp))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.id }) { playlist ->
                    ListItem(headlineContent = { Text(playlist.title) }, trailingContent = {
                        IconButton(onClick = { onUnhide(playlist.id) }) {
                            Icon(painterResource(Res.drawable.phone_reference_visibility_off), stringResource(Res.string.m6_account_hidden_playlists_unhide))
                        }
                    })
                }
            }
        }
    }
}
