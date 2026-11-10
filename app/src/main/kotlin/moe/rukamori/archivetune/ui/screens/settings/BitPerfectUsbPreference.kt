package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.BitPerfectUsbOutputKey
import moe.rukamori.archivetune.constants.DirectUsbDriverKey
import moe.rukamori.archivetune.playback.BitPerfectUsbOutput
import moe.rukamori.archivetune.ui.component.LocalRestartPrompt
import moe.rukamori.archivetune.ui.component.SwitchPreference
import moe.rukamori.archivetune.ui.component.setPlaybackOutputPreference

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun BitPerfectUsbPreference(modifier: Modifier) {
    val prefs = LocalSettingsPreferenceSnapshot.current
    val restartPrompt = LocalRestartPrompt.current
    val context = LocalContext.current
    val (enabled) = prefs.state(BitPerfectUsbOutputKey, defaultValue = false)
    val (direct) = prefs.state(DirectUsbDriverKey, defaultValue = false)
    val supported = BitPerfectUsbOutput.supportedWith(direct)
    val status by BitPerfectUsbOutput.status.collectAsStateWithLifecycle()
    Column(modifier) {
        SwitchPreference(
            title = { Text(stringResource(R.string.bit_perfect_usb_title)) },
            description = bitPerfectOutputDescription(enabled, status, supported),
            icon = { Icon(painterResource(R.drawable.graphic_eq), null) },
            checked = enabled && supported,
            isEnabled = supported,
            onCheckedChange = { value ->
                setPlaybackOutputPreference(restartPrompt, prefs, context, BitPerfectUsbOutputKey, value)
            },
        )
    }
}
