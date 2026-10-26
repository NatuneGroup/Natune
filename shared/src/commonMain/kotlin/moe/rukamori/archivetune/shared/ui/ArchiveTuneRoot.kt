package moe.rukamori.archivetune.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.PlaybackSnapshot
import moe.rukamori.archivetune.shared.resources.*
import org.jetbrains.compose.resources.stringResource

/** Bootstrap surface only. No source adapters, queue, settings or player replicas yet. */
@Composable
fun ArchiveTuneRoot(snapshot: PlaybackSnapshot?) {
    var showDetails by remember { mutableStateOf(false) }
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.headlineLarge)
                Text(stringResource(Res.string.foundation_status))
                Text(
                    if (snapshot == null) stringResource(Res.string.no_engine)
                    else stringResource(Res.string.observed_position, snapshot.positionMillis),
                )
                Button(onClick = { showDetails = !showDetails }) {
                    Text(stringResource(if (showDetails) Res.string.hide_details else Res.string.show_details))
                }
                if (showDetails) Text(stringResource(Res.string.foundation_details))
            }
        }
    }
}
