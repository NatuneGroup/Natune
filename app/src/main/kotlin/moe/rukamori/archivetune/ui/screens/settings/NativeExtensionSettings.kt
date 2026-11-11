package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.modules.ExtensionRegistration
import moe.rukamori.archivetune.modules.ModuleKind
import moe.rukamori.archivetune.shared.domain.source.SpotifyProviderIds
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.SwitchPreference

internal fun ModuleKind.labelResource(): Int = when (this) {
    ModuleKind.AUDIO_SOURCE -> R.string.extensions_kind_audio
    ModuleKind.METADATA -> R.string.extensions_kind_metadata
    ModuleKind.LYRICS -> R.string.extensions_kind_lyrics
    ModuleKind.PLAYBACK -> R.string.extensions_kind_playback
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExtensionKindFilters(selected: ModuleKind?, onSelect: (ModuleKind?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected == null, onClick = { onSelect(null) }, label = { Text(stringResource(R.string.extensions_all)) })
        ModuleKind.entries.forEach { kind ->
            FilterChip(selected == kind, onClick = { onSelect(kind) }, label = { Text(stringResource(kind.labelResource())) })
        }
    }
}

@Composable
internal fun NativeExtensionCard(
    extension: ExtensionRegistration,
    onEnabled: (Boolean) -> Unit,
    onSettings: () -> Unit,
    onMove: (Int) -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            SwitchPreference(
                title = { Text(extension.displayName) },
                description = stringResource(extension.kind.labelResource()),
                icon = { Icon(painterResource(R.drawable.spotify_icon), null) },
                checked = extension.enabled,
                onCheckedChange = onEnabled,
            )
            if (extension.id == SpotifyProviderIds.LIBRESPOT) Text(
                stringResource(R.string.extensions_spotify_official_unavailable),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PreferenceEntry(
                title = { Text(stringResource(R.string.extensions_settings)) },
                icon = { Icon(painterResource(R.drawable.settings), null) },
                onClick = onSettings,
            )
            Row(Modifier.padding(horizontal = 20.dp)) {
                Text(stringResource(R.string.extensions_priority, extension.priority), modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                if (extension.kind == ModuleKind.PLAYBACK) {
                    IconButton(onClick = { onMove(-1) }, enabled = extension.priority > 1) {
                        Icon(painterResource(R.drawable.arrow_upward), stringResource(R.string.modules_move_up))
                    }
                    IconButton(onClick = { onMove(1) }, enabled = extension.priority < 2) {
                        Icon(painterResource(R.drawable.arrow_downward), stringResource(R.string.modules_move_down))
                    }
                }
            }
        }
    }
}
