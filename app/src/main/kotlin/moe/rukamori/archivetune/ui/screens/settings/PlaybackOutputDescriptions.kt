package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.playback.BitPerfectUsbOutput

@Composable
internal fun bitPerfectOutputDescription(selected: Boolean, status: BitPerfectUsbOutput.Status, supported: Boolean): String {
    val description = when {
        !supported -> stringResource(R.string.bit_perfect_usb_unsupported)
        !selected -> stringResource(R.string.bit_perfect_usb_desc)
        !BitPerfectUsbOutput.sinkActive -> stringResource(R.string.bit_perfect_usb_restart)
        else -> when (status) {
            is BitPerfectUsbOutput.Status.Active -> stringResource(
                if (status.exact) R.string.bit_perfect_usb_active else R.string.bit_perfect_usb_direct_inexact_active,
                status.deviceName.ifBlank { stringResource(R.string.bit_perfect_usb_default_device) },
                status.format,
            )
            is BitPerfectUsbOutput.Status.Fallback -> stringResource(R.string.bit_perfect_usb_fallback, status.reason)
            is BitPerfectUsbOutput.Status.Ready -> stringResource(
                R.string.bit_perfect_usb_ready,
                status.deviceName.ifBlank { stringResource(R.string.bit_perfect_usb_default_device) },
            )
            BitPerfectUsbOutput.Status.NoDevice -> stringResource(R.string.bit_perfect_usb_no_device)
            else -> stringResource(R.string.bit_perfect_usb_desc)
        }
    }
    return if (supported) description + "\n" + stringResource(R.string.settings_usb_exclusions)
    else description
}
