package app.natune.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.natune.R
import app.natune.constants.DirectUsbDriverKey
import app.natune.playback.BitPerfectUsbOutput
import app.natune.ui.component.LocalRestartPrompt
import app.natune.ui.component.SwitchPreference
import app.natune.ui.component.setPlaybackOutputPreference

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun DirectUsbDriverPreference(
    modifier: Modifier,
    // Player settings is its own route, outside SettingsScreen's provider, so it passes its snapshot.
    prefs: app.natune.utils.PreferenceSnapshot = LocalSettingsPreferenceSnapshot.current,
) {
    val restartPrompt = LocalRestartPrompt.current
    val context = LocalContext.current
    val (enabled) = prefs.state(DirectUsbDriverKey, defaultValue = false)
    val reason = settingsSwitchDisabledReason(DirectUsbDriverKey, prefs.current, BitPerfectUsbOutput.sinkActive,
        blurSupported = true)
    Column(modifier) {
        SwitchPreference(
            title = { Text(stringResource(R.string.direct_usb_driver_title)) },
            description = stringResource(reason ?: R.string.direct_usb_driver_desc),
            icon = { Icon(painterResource(R.drawable.graphic_eq), null) },
            checked = enabled,
            isEnabled = reason == null,
            onCheckedChange = { value ->
                setPlaybackOutputPreference(restartPrompt, prefs, context, DirectUsbDriverKey, value)
            },
        )
    }
}
