package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.DirectUsbDriverKey
import moe.rukamori.archivetune.playback.BitPerfectUsbOutput
import moe.rukamori.archivetune.ui.component.LocalRestartPrompt
import moe.rukamori.archivetune.ui.component.SwitchPreference
import moe.rukamori.archivetune.ui.component.setPlaybackOutputPreference

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun DirectUsbDriverPreference(parentEnabled: Boolean, modifier: Modifier) {
    val prefs = LocalSettingsPreferenceSnapshot.current
    val restartPrompt = LocalRestartPrompt.current
    val context = LocalContext.current
    val (enabled) = prefs.state(DirectUsbDriverKey, defaultValue = false)
    Column(modifier) {
        SwitchPreference(
            title = { Text(stringResource(R.string.direct_usb_driver_title)) },
            description = stringResource(if (parentEnabled) R.string.direct_usb_driver_desc
                else R.string.direct_usb_driver_requires_bit_perfect),
            icon = { Icon(painterResource(R.drawable.graphic_eq), null) },
            checked = enabled,
            isEnabled = parentEnabled && BitPerfectUsbOutput.isSupported,
            onCheckedChange = { value ->
                setPlaybackOutputPreference(restartPrompt, prefs, context, DirectUsbDriverKey, value)
            },
        )
    }
}
