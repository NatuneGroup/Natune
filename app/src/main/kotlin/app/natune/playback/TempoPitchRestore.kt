package app.natune.playback

import android.content.Context
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import app.natune.constants.PlaybackPitchKey
import app.natune.constants.PlaybackSpeedKey
import app.natune.constants.TempoPitchRememberedKey
import app.natune.shared.domain.settings.TEMPO_PITCH_VALUE_MAX
import app.natune.shared.domain.settings.TEMPO_PITCH_VALUE_MIN
import app.natune.utils.dataStore
import app.natune.utils.get

/**
 * Applies the persisted speed and pitch to this player at service start — the opt-in "remember
 * speed and pitch" restore from the tempo-and-pitch dialog. Without the flag the player keeps
 * neutral parameters and the stored values stay unread.
 */
internal fun Player.restorePersistedTempoPitch(context: Context) {
    val dataStore = context.dataStore
    if (!dataStore.get(TempoPitchRememberedKey, false)) return
    playbackParameters =
        PlaybackParameters(
            dataStore.get(PlaybackSpeedKey, 1f).let {
                if (it.isFinite()) it.coerceIn(TEMPO_PITCH_VALUE_MIN, TEMPO_PITCH_VALUE_MAX) else 1f
            },
            dataStore.get(PlaybackPitchKey, 1f).let {
                if (it.isFinite()) it.coerceIn(TEMPO_PITCH_VALUE_MIN, TEMPO_PITCH_VALUE_MAX) else 1f
            },
        )
}
