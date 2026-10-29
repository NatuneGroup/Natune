package moe.rukamori.archivetune.ui.menu

import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.kmp.settings.sharedSnapshot
import moe.rukamori.archivetune.playback.BitPerfectUsbOutput
import moe.rukamori.archivetune.shared.domain.settings.bitPerfectOutputSelected
import moe.rukamori.archivetune.utils.rememberPreferenceSnapshot

/** The custom float sink bypasses both session effects and the speed/pitch processor. */
@Composable
internal fun BitPerfectProcessingNotice(@StringRes title: Int, onDismiss: () -> Unit): Boolean {
    val preferences = rememberPreferenceSnapshot()
    val status by BitPerfectUsbOutput.status.collectAsStateWithLifecycle()
    // Status changes invalidate the volatile native sink read, including a pending-restart release.
    val blocked = preferences.current.sharedSnapshot().bitPerfectOutputSelected() ||
        status.let { BitPerfectUsbOutput.sinkActive }
    if (blocked) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(title)) },
            text = { Text(stringResource(R.string.settings_processing_bypassed_bit_perfect)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
        )
    }
    return blocked
}
