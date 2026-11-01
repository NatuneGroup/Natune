package moe.rukamori.archivetune.ui.menu

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.widget.Toast
import moe.rukamori.archivetune.R

/**
 * "Open system equalizer" for the equalizer sheet. No live audio session means the panel would
 * open against a dead stream; no resolvable activity means no equalizer is installed on the
 * device. Both surface a toast instead of a silent no-op.
 */
internal fun Context.launchSystemEqualizerPanel(
    audioSessionId: Int,
    launch: (Intent) -> Unit,
) {
    if (audioSessionId <= 0) {
        Toast.makeText(this, getString(R.string.equalizer_stream_unavailable), Toast.LENGTH_SHORT).show()
        return
    }
    val intent =
        Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        }
    if (intent.resolveActivity(packageManager) == null) {
        Toast.makeText(this, getString(R.string.equalizer_not_found), Toast.LENGTH_SHORT).show()
        return
    }
    try {
        launch(intent)
    } catch (e: android.content.ActivityNotFoundException) {
        Toast.makeText(this, getString(R.string.equalizer_not_found), Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        Toast.makeText(this, getString(R.string.equalizer_not_found), Toast.LENGTH_SHORT).show()
    }
}
