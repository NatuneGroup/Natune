package moe.rukamori.archivetune.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R

/**
 * The player sheet's own sleep-timer picker (icon, OK/cancel chrome, end-of-song shortcut);
 * the queue sheet keeps its separate ActionPromptDialog variant in QueueComponents.
 */
@Composable
fun PlayerSleepTimerDialog(onDismiss: () -> Unit) {
    val playerConnection = LocalPlayerConnection.current ?: return
    var sleepTimerValue by remember { mutableFloatStateOf(30f) }
    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                painter = painterResource(R.drawable.player_bedtime),
                contentDescription = null,
            )
        },
        title = { Text(stringResource(R.string.sleep_timer)) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    playerConnection.service.sleepTimer.start(sleepTimerValue.roundToInt())
                },
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text =
                        pluralStringResource(
                            R.plurals.minute,
                            sleepTimerValue.roundToInt(),
                            sleepTimerValue.roundToInt(),
                        ),
                    style = MaterialTheme.typography.bodyLarge,
                )

                Slider(
                    value = sleepTimerValue,
                    onValueChange = { sleepTimerValue = it },
                    valueRange = 5f..120f,
                    steps = (120 - 5) / 5 - 1,
                )

                OutlinedIconButton(
                    onClick = {
                        onDismiss()
                        playerConnection.service.sleepTimer.start(-1)
                    },
                ) {
                    Text(stringResource(R.string.end_of_song))
                }
            }
        },
    )
}
