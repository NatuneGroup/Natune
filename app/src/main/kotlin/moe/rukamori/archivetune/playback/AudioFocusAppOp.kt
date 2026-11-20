package moe.rukamori.archivetune.playback

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process

private const val OPSTR_TAKE_AUDIO_FOCUS = "android:take_audio_focus"

/**
 * False when the user denied the take-audio-focus app op (for example with `appops set ... TAKE_AUDIO_FOCUS ignore`)
 * to play alongside other apps. Every focus request then fails, so playback must proceed without focus instead of
 * pausing. Adapted from Metrolist 14.0.1 (MetrolistGroup/Metrolist 282c5ae77, GPL-3.0).
 */
internal fun appMayTakeAudioFocus(context: Context): Boolean =
    runCatching {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return true
        val mode =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(OPSTR_TAKE_AUDIO_FOCUS, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(OPSTR_TAKE_AUDIO_FOCUS, Process.myUid(), context.packageName)
            }
        mode != AppOpsManager.MODE_IGNORED && mode != AppOpsManager.MODE_ERRORED
    }.getOrDefault(true)
