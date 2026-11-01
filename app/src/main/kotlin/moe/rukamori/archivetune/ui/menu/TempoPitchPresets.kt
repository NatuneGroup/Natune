package moe.rukamori.archivetune.ui.menu

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.PlaybackPitchKey
import moe.rukamori.archivetune.constants.PlaybackSpeedKey
import moe.rukamori.archivetune.constants.TempoPitchPresetsJsonKey
import moe.rukamori.archivetune.constants.TempoPitchRememberedKey
import moe.rukamori.archivetune.shared.domain.settings.TempoPitchPreset
import moe.rukamori.archivetune.shared.domain.settings.encodeTempoPitchPresets
import moe.rukamori.archivetune.shared.domain.settings.newTempoPitchPresetId
import moe.rukamori.archivetune.shared.domain.settings.parseTempoPitchPresets
import moe.rukamori.archivetune.shared.domain.settings.removeTempoPitchPreset
import moe.rukamori.archivetune.shared.domain.settings.renameTempoPitchPreset
import moe.rukamori.archivetune.shared.domain.settings.upsertTempoPitchPreset
import moe.rukamori.archivetune.utils.PreferenceStore
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.rememberPreference

/** Which name entry the preset dialog is editing: a new preset, or the rename of an existing one. */
private sealed interface TempoPitchPresetNameEdit {
    data object New : TempoPitchPresetNameEdit

    data class Rename(val preset: TempoPitchPreset) : TempoPitchPresetNameEdit
}

/**
 * While the dialog's opt-in "remember" flag holds, every applied pair is stored for the next
 * launch's restore. The flag reads from the cached store, keeping the slider path free of
 * Compose state.
 */
internal fun persistRememberedTempoPitch(context: Context, speed: Float, pitch: Float) {
    PreferenceStore.launchEdit(context.dataStore) {
        if (this[TempoPitchRememberedKey] == true) {
            this[PlaybackSpeedKey] = speed.coerceIn(TempoMin, TempoMax)
            this[PlaybackPitchKey] = pitch.coerceIn(PitchMin, PitchMax)
        }
    }
}

/**
 * The tempo-and-pitch dialog's persistence section: the opt-in "remember" toggle and the named
 * presets list (tap applies both values, pencil renames, delete removes, the footer saves the
 * current pair). [currentTempo] and [currentPitch] feed "save current as preset".
 */
@Composable
internal fun TempoPitchPresetsSection(
    currentTempo: Float,
    currentPitch: Float,
    onApplyPreset: (Float, Float) -> Unit,
) {
    var tempoPitchRemembered by rememberPreference(TempoPitchRememberedKey, false)
    var tempoPitchPresetsJson by rememberPreference(TempoPitchPresetsJsonKey, "[]")
    val tempoPitchPresets = remember(tempoPitchPresetsJson) { parseTempoPitchPresets(tempoPitchPresetsJson) }
    var presetNameEdit by remember { mutableStateOf<TempoPitchPresetNameEdit?>(null) }

    HorizontalDivider()

    // Opt-in persistence: while the flag holds, every applied pair is stored for the next launch.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { tempoPitchRemembered = !tempoPitchRemembered },
    ) {
        Checkbox(
            checked = tempoPitchRemembered,
            onCheckedChange = { tempoPitchRemembered = it },
        )
        Text(
            text = stringResource(R.string.remember_speed_and_pitch),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 8.dp),
        )
    }

    Text(
        text = stringResource(R.string.tempo_pitch_presets),
        style = MaterialTheme.typography.titleMedium,
    )

    tempoPitchPresets.forEach { preset ->
        // A tap applies speed then pitch through the same clamped path the sliders use.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { onApplyPreset(preset.speed, preset.pitch) },
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = preset.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        stringResource(
                            R.string.tempo_pitch_preset_values,
                            formatMultiplier(preset.speed),
                            formatMultiplier(preset.pitch),
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { presetNameEdit = TempoPitchPresetNameEdit.Rename(preset) }) {
                Icon(
                    painter = painterResource(R.drawable.edit),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            }
            IconButton(
                onClick = {
                    tempoPitchPresetsJson =
                        encodeTempoPitchPresets(removeTempoPitchPreset(tempoPitchPresets, preset.id))
                },
            ) {
                Icon(
                    painter = painterResource(R.drawable.delete),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }

    TextButton(
        onClick = { presetNameEdit = TempoPitchPresetNameEdit.New },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.save_current_as_preset))
    }

    presetNameEdit?.let { edit ->
        var name by remember(edit) {
            mutableStateOf((edit as? TempoPitchPresetNameEdit.Rename)?.preset?.name.orEmpty())
        }
        AlertDialog(
            onDismissRequest = { presetNameEdit = null },
            title = {
                Text(
                    stringResource(
                        when (edit) {
                            is TempoPitchPresetNameEdit.Rename -> R.string.rename_preset
                            TempoPitchPresetNameEdit.New -> R.string.new_preset
                        },
                    ),
                )
            },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.preset_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        when (edit) {
                            is TempoPitchPresetNameEdit.Rename ->
                                tempoPitchPresetsJson =
                                    encodeTempoPitchPresets(renameTempoPitchPreset(tempoPitchPresets, edit.preset.id, name))

                            TempoPitchPresetNameEdit.New ->
                                tempoPitchPresetsJson =
                                    encodeTempoPitchPresets(
                                        upsertTempoPitchPreset(
                                            tempoPitchPresets,
                                            TempoPitchPreset(
                                                id = newTempoPitchPresetId(),
                                                name = name.trim(),
                                                speed = currentTempo.coerceIn(TempoMin, TempoMax),
                                                pitch = currentPitch.coerceIn(PitchMin, PitchMax),
                                            ),
                                        ),
                                    )
                        }
                        presetNameEdit = null
                    },
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { presetNameEdit = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
