package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun CustomBackgroundSettings(
    readableFile: (String) -> Boolean,
    pickImage: suspend () -> String?,
    removeImage: suspend () -> Unit,
    artwork: @Composable (String, Modifier) -> Unit,
    controller: SettingsController,
    onFailure: () -> Unit,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot
    if (preferences == null) {
        CircularProgressIndicator()
        return
    }
    val scope = rememberCoroutineScope()
    fun write(change: suspend () -> Unit) {
        scope.launch {
            try {
                change()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onFailure()
            }
        }
    }
    val uri = preferences[PlayerCustomImageUriKey, ""]
    val readable = remember(uri) { uri.isBlank() || readableFile(uri) }
    var blur by remember(preferences[PlayerCustomBlurKey, 0f]) { mutableStateOf(preferences[PlayerCustomBlurKey, 0f]) }
    var contrast by remember(preferences[PlayerCustomContrastKey, 1f]) {
        mutableStateOf(preferences[PlayerCustomContrastKey, 1f])
    }
    var brightness by remember(preferences[PlayerCustomBrightnessKey, 1f]) {
        mutableStateOf(preferences[PlayerCustomBrightnessKey, 1f])
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(Res.string.ios_player_background_title), style = MaterialTheme.typography.headlineSmall)
        if (uri.isBlank()) {
            Text(stringResource(Res.string.ios_player_background_none), style = MaterialTheme.typography.bodyMedium)
        } else {
            Box(Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(16f / 9f)) {
                artwork(uri, Modifier.fillMaxSize())
            }
            if (!readable) {
                Text(stringResource(Res.string.ios_player_background_missing), color = MaterialTheme.colorScheme.error)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                write {
                    val stored = pickImage()
                    if (stored != null) controller.edit { this[PlayerCustomImageUriKey] = stored }
                }
            }) { Text(stringResource(Res.string.ios_player_background_choose)) }
            if (uri.isNotBlank()) {
                OutlinedButton(onClick = {
                    write {
                        controller.edit { this[PlayerCustomImageUriKey] = null }
                        removeImage()
                    }
                }) { Text(stringResource(Res.string.ios_player_background_remove)) }
            }
        }
        BackgroundSlider(Res.string.ios_player_background_blur, blur, 0f..50f, { blur = it }) {
            write { controller.edit { this[PlayerCustomBlurKey] = blur } }
        }
        BackgroundSlider(Res.string.ios_player_background_contrast, contrast, 0.5f..2f, { contrast = it }) {
            write { controller.edit { this[PlayerCustomContrastKey] = contrast } }
        }
        BackgroundSlider(Res.string.ios_player_background_brightness, brightness, 0.5f..2f, { brightness = it }) {
            write { controller.edit { this[PlayerCustomBrightnessKey] = brightness } }
        }
        TextButton(onClick = {
            write {
                controller.edit {
                    this[PlayerCustomBlurKey] = null
                    this[PlayerCustomContrastKey] = null
                    this[PlayerCustomBrightnessKey] = null
                }
            }
        }) { Text(stringResource(Res.string.ios_player_background_reset)) }
    }
}

@Composable
private fun BackgroundSlider(
    label: org.jetbrains.compose.resources.StringResource,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
) {
    Column {
        Text("${stringResource(label)}: ${backgroundSliderValue(value)}", style = MaterialTheme.typography.bodyLarge)
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, onValueChangeFinished = onDone)
    }
}

/** Two decimals, like the desktop slider label, without java.text. */
private fun backgroundSliderValue(value: Float): String {
    val rounded = kotlin.math.round(value * 100) / 100f
    val text = rounded.toString()
    val dot = text.indexOf('.')
    return if (dot < 0) "$text.00" else text.padEnd(dot + 3, '0')
}
