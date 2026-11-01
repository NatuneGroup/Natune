package moe.rukamori.archivetune.playback

import android.content.Context
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import moe.rukamori.archivetune.constants.PlaybackPitchKey
import moe.rukamori.archivetune.constants.PlaybackSpeedKey
import moe.rukamori.archivetune.constants.TempoPitchRememberedKey
import moe.rukamori.archivetune.shared.domain.settings.TEMPO_PITCH_VALUE_MAX
import moe.rukamori.archivetune.shared.domain.settings.TEMPO_PITCH_VALUE_MIN
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get

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
