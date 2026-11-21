package app.natune.ui.screens.settings

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
import app.natune.R
import app.natune.constants.BitPerfectUsbOutputKey
import app.natune.constants.DirectUsbDriverKey
import app.natune.playback.BitPerfectUsbOutput
import app.natune.ui.component.LocalRestartPrompt
import app.natune.ui.component.SwitchPreference
import app.natune.ui.component.setPlaybackOutputPreference

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun BitPerfectUsbPreference(
    modifier: Modifier,
    // Player settings is its own route, outside SettingsScreen's provider, so it passes its snapshot.
    prefs: app.natune.utils.PreferenceSnapshot = LocalSettingsPreferenceSnapshot.current,
) {
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
