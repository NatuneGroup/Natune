@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.shared.ui.menu.playerqueue

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlin.math.abs
import kotlin.math.roundToInt
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

private val PLAYER_QUEUE_MULTIPLIER_PRESETS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private val PLAYER_QUEUE_SEMITONE_PRESETS = listOf(-12, -7, -5, 0, 5, 7, 12)

/**
 * The two presentations of the pitch row. The entry names match the baseline menu's `PitchMode`, so the saved form
 * keeps the same names the baseline stored.
 */
private enum class PlayerQueuePitchMode { Semitones, Multiplier }

/** Saves the mode by entry name. An unrecognized name restores as null, so the seeded mode is used instead. */
private val playerQueuePitchModeSaver: Saver<PlayerQueuePitchMode, String> =
    Saver(
        save = { mode -> mode.name },
        restore = { saved -> PlayerQueuePitchMode.entries.firstOrNull { entry -> entry.name == saved } },
    )

/** The multiplier text, such as "x1.25". The "x" comes from a resource so the label can be localized. */
@Composable
private fun multiplierLabel(value: Float): String = stringResource(Res.string.m5pq_multiplier_value, formatMultiplier(value))

/** The semitone text: a plus sign above zero and the bare number otherwise, as the baseline showed. */
@Composable
private fun semitoneLabel(semitones: Int): String =
    if (semitones > 0) stringResource(Res.string.m5pq_semitones_positive, semitones) else semitones.toString()

/**
 * The tempo and pitch dialog. Edits apply to the playback parameters immediately, through
 * [PlayerQueueMenuActions.applyPlaybackParameters], which receives values clamped to the baseline ranges. [initialSpeed]
 * and [initialPitch] seed the local edit state once, so later host updates do not override a dialog in progress. Reset
 * returns both to 1, and OK only closes the dialog.
 */
@Composable
fun PlayerQueueTempoPitchDialog(
    initialSpeed: Float,
    initialPitch: Float,
    actions: PlayerQueueMenuActions,
    icon: PlayerQueueMenuIconSlot,
    onDismiss: () -> Unit,
) {
    var tempo by remember {
        mutableFloatStateOf(initialSpeed.safeCoerceIn(PLAYER_QUEUE_TEMPO_MIN, PLAYER_QUEUE_TEMPO_MAX, fallback = 1f))
    }
    var pitch by remember {
        mutableFloatStateOf(initialPitch.safeCoerceIn(PLAYER_QUEUE_PITCH_MIN, PLAYER_QUEUE_PITCH_MAX, fallback = 1f))
    }
    // The pitch row shows semitones or the raw multiplier. The seed comes from the pitch once; a restored dialog then
    // keeps the presentation the user picked.
    var pitchMode by rememberSaveable(stateSaver = playerQueuePitchModeSaver) {
        mutableStateOf(if (isPitchSemitoneAligned(pitch)) PlayerQueuePitchMode.Semitones else PlayerQueuePitchMode.Multiplier)
    }

    fun applyPlayback() {
        actions.applyPlaybackParameters(
            speed = tempo.coerceIn(PLAYER_QUEUE_TEMPO_MIN, PLAYER_QUEUE_TEMPO_MAX),
            pitch = pitch.coerceIn(PLAYER_QUEUE_PITCH_MIN, PLAYER_QUEUE_PITCH_MAX),
        )
    }

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(Res.string.m5pq_tempo_and_pitch))
        },
        dismissButton = {
            TextButton(
                onClick = {
                    tempo = 1f
                    pitch = 1f
                    applyPlayback()
                },
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(Res.string.m5pq_reset))
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(Res.string.m5pq_ok))
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    icon(PlayerQueueMenuIcon.SPEED, LocalContentColor.current, Modifier.size(28.dp))
                    Text(
                        text = stringResource(Res.string.m5pq_tempo),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = multiplierLabel(tempo),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.End,
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconButton(
                        enabled = tempo > PLAYER_QUEUE_TEMPO_MIN,
                        onClick = {
                            tempo = (tempo - 0.01f).coerceIn(PLAYER_QUEUE_TEMPO_MIN, PLAYER_QUEUE_TEMPO_MAX).quantizeTo(0.01f)
                            applyPlayback()
                        },
                    ) {
                        icon(PlayerQueueMenuIcon.REMOVE, LocalContentColor.current, Modifier)
                    }

                    Slider(
                        value = multiplierToSlider(tempo),
                        onValueChange = { slider ->
                            val updated = sliderToMultiplier(slider).quantizeTo(0.01f)
                            if (abs(updated - tempo) >= 0.005f) {
                                tempo = updated
                                applyPlayback()
                            }
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(),
                    )

                    IconButton(
                        enabled = tempo < PLAYER_QUEUE_TEMPO_MAX,
                        onClick = {
                            tempo = (tempo + 0.01f).coerceIn(PLAYER_QUEUE_TEMPO_MIN, PLAYER_QUEUE_TEMPO_MAX).quantizeTo(0.01f)
                            applyPlayback()
                        },
                    ) {
                        icon(PlayerQueueMenuIcon.ADD, LocalContentColor.current, Modifier)
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                ) {
                    PLAYER_QUEUE_MULTIPLIER_PRESETS.forEach { preset ->
                        FilterChip(
                            selected = abs(tempo - preset) < 0.005f,
                            onClick = {
                                tempo = preset
                                applyPlayback()
                            },
                            label = { Text(multiplierLabel(preset)) },
                        )
                    }
                }

                HorizontalDivider()

                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    icon(PlayerQueueMenuIcon.DISCOVER_TUNE, LocalContentColor.current, Modifier.size(28.dp))
                    Text(
                        text = stringResource(Res.string.m5pq_pitch),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text =
                            if (pitchMode == PlayerQueuePitchMode.Multiplier) {
                                multiplierLabel(pitch)
                            } else {
                                semitoneLabel(pitchToSemitones(pitch))
                            },
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.End,
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                ) {
                    FilterChip(
                        selected = pitchMode == PlayerQueuePitchMode.Semitones,
                        onClick = { pitchMode = PlayerQueuePitchMode.Semitones },
                        label = { Text(stringResource(Res.string.m5pq_pitch_mode_semitones_short)) },
                    )
                    FilterChip(
                        selected = pitchMode == PlayerQueuePitchMode.Multiplier,
                        onClick = { pitchMode = PlayerQueuePitchMode.Multiplier },
                        label = { Text(stringResource(Res.string.m5pq_pitch_mode_multiplier_short)) },
                    )
                }

                if (pitchMode == PlayerQueuePitchMode.Semitones) {
                    val currentSemitones = pitchToSemitones(pitch)
                    Slider(
                        value = currentSemitones.toFloat(),
                        onValueChange = { slider ->
                            val semitones = slider.roundToInt().coerceIn(-PLAYER_QUEUE_SEMITONE_LIMIT, PLAYER_QUEUE_SEMITONE_LIMIT)
                            val updated = semitonesToPitch(semitones)
                            if (abs(updated - pitch) >= 0.0005f) {
                                pitch = updated
                                applyPlayback()
                            }
                        },
                        valueRange = -12f..12f,
                        steps = 23,
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(),
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                    ) {
                        PLAYER_QUEUE_SEMITONE_PRESETS.forEach { preset ->
                            FilterChip(
                                selected = currentSemitones == preset,
                                onClick = {
                                    pitch = semitonesToPitch(preset)
                                    applyPlayback()
                                },
                                label = { Text(semitoneLabel(preset)) },
                            )
                        }
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        IconButton(
                            enabled = pitch > PLAYER_QUEUE_PITCH_MIN,
                            onClick = {
                                pitch = (pitch - 0.01f).coerceIn(PLAYER_QUEUE_PITCH_MIN, PLAYER_QUEUE_PITCH_MAX).quantizeTo(0.01f)
                                applyPlayback()
                            },
                        ) {
                            icon(PlayerQueueMenuIcon.REMOVE, LocalContentColor.current, Modifier)
                        }

                        Slider(
                            value = multiplierToSlider(pitch),
                            onValueChange = { slider ->
                                val updated = sliderToMultiplier(slider).quantizeTo(0.01f)
                                if (abs(updated - pitch) >= 0.005f) {
                                    pitch = updated
                                    applyPlayback()
                                }
                            },
                            valueRange = 0f..1f,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(),
                        )

                        IconButton(
                            enabled = pitch < PLAYER_QUEUE_PITCH_MAX,
                            onClick = {
                                pitch = (pitch + 0.01f).coerceIn(PLAYER_QUEUE_PITCH_MIN, PLAYER_QUEUE_PITCH_MAX).quantizeTo(0.01f)
                                applyPlayback()
                            },
                        ) {
                            icon(PlayerQueueMenuIcon.ADD, LocalContentColor.current, Modifier)
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                    ) {
                        PLAYER_QUEUE_MULTIPLIER_PRESETS.forEach { preset ->
                            FilterChip(
                                selected = abs(pitch - preset) < 0.005f,
                                onClick = {
                                    pitch = preset
                                    applyPlayback()
                                },
                                label = { Text(multiplierLabel(preset)) },
                            )
                        }
                    }
                }
            }
        },
    )
}
