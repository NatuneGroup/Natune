package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun IosPlayerNavigationDialogs(controls: IosPlayerControls) {
    val artists by controls.artistChoices.collectAsState()
    if (artists.isNotEmpty()) AlertDialog(
        onDismissRequest = { controls.artistChoices.value = emptyList() },
        title = { Text(stringResource(Res.string.ios_choose_artist)) },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            artists.forEach { (ref, name) -> TextButton(onClick = { controls.chooseArtist(ref) }) { Text(name) } }
        } }, confirmButton = {},
        dismissButton = { TextButton(onClick = { controls.artistChoices.value = emptyList() }) { Text(stringResource(Res.string.m6_cancel)) } },
    )
    val output by controls.outputPicker.collectAsState()
    if (output) AlertDialog(
        onDismissRequest = { controls.outputPicker.value = false },
        title = { Text(stringResource(Res.string.ios_output_routes)) },
        text = { IosAudioRoutePicker(Modifier.size(64.dp), MaterialTheme.colorScheme.primary, true) },
        confirmButton = { TextButton(onClick = { controls.outputPicker.value = false }) { Text(stringResource(Res.string.ios_ok)) } },
    )
}
