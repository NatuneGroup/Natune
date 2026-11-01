package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.ios_hidden_playlists_empty
import moe.rukamori.archivetune.shared.ui.resources.ios_hidden_playlists_title
import moe.rukamori.archivetune.shared.ui.resources.ios_hidden_playlists_unhide
import moe.rukamori.archivetune.shared.ui.resources.ios_hidden_playlists_unhidden
import moe.rukamori.archivetune.shared.ui.settings.SettingsNotice
import org.jetbrains.compose.resources.stringResource

/** Local playlists the Android app hid from its Library, with Unhide. */
@Composable
internal fun IosHiddenPlaylistsPage(
    graph: IosSessionGraph,
    onNotice: (SettingsNotice) -> Unit,
    onFailure: () -> Unit,
) {
    val hidden by remember { graph.library.observeHiddenPlaylists() }.collectAsState(null)
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Text(
            stringResource(Res.string.ios_hidden_playlists_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(16.dp),
        )
        val rows = hidden
        when {
            rows == null -> CircularProgressIndicator(Modifier.padding(16.dp))
            rows.isEmpty() -> Text(
                stringResource(Res.string.ios_hidden_playlists_empty),
                modifier = Modifier.padding(16.dp),
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.id }) { playlist ->
                    ListItem(
                        headlineContent = { Text(playlist.title) },
                        trailingContent = {
                            TextButton(onClick = {
                                scope.launch {
                                    try {
                                        graph.library.setPlaylistHidden(playlist.id, false)
                                        onNotice(
                                            SettingsNotice.Message(Res.string.ios_hidden_playlists_unhidden),
                                        )
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        onFailure()
                                    }
                                }
                            }) { Text(stringResource(Res.string.ios_hidden_playlists_unhide)) }
                        },
                    )
                }
            }
        }
    }
}
