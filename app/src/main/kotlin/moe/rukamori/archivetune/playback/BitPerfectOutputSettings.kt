package moe.rukamori.archivetune.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import moe.rukamori.archivetune.constants.*
import moe.rukamori.archivetune.utils.PreferenceStore

@androidx.annotation.OptIn(UnstableApi::class)
internal fun initializeBitPerfectOutput(context: Context): Boolean {
    // The service can restart in the same process; re-read both switches before building its sinks.
    PreferenceStore.blockUntilLoaded()
    BitPerfectUsbOutput.configureDirect(PreferenceStore.get(DirectUsbDriverKey) == true)
    val active = BitPerfectUsbOutput.isSupported && PreferenceStore.get(BitPerfectUsbOutputKey) == true &&
        PreferenceStore.get(CrossfadeEnabledKey) != true && PreferenceStore.get(AutomixEnabledKey) != true
    BitPerfectUsbOutput.sinkActive = active
    BitPerfectUsbOutput.setEnabled(context, active)
    return active
}
