package moe.rukamori.archivetune.playback

import android.content.Intent
import android.media.audiofx.AudioEffect
import moe.rukamori.archivetune.androidauto.AndroidAutoPinnedPlaylists
import moe.rukamori.archivetune.constants.AndroidAutoPinnedPlaylistsJsonKey
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import timber.log.Timber

private const val TAG = "MusicService"

private const val AUTOMOTIVE_ROOT_ITEM_COUNT = 11

internal fun MusicService.sendOpenAudioEffectSessionBroadcast(sessionId: Int) {
    try {
        sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            },
        )
    } catch (e: SecurityException) {
        // Some OEM builds restrict the audio-effect control-session broadcasts.
        Timber.tag(TAG).w(e, "OPEN_AUDIO_EFFECT_CONTROL_SESSION broadcast denied")
    }
}

internal fun MusicService.sendCloseAudioEffectSessionBroadcast(sessionId: Int) {
    try {
        sendBroadcast(
            Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            },
        )
    } catch (e: SecurityException) {
        Timber.tag(TAG).w(e, "CLOSE_AUDIO_EFFECT_CONTROL_SESSION broadcast denied")
    }
}

/**
 * Re-publishes the automotive browse tree so a head unit that already attached sees current
 * queue, pinned driving playlists and library content. Triggered by
 * [MusicService.ACTION_REFRESH_AUTOMOTIVE_LIBRARY].
 */
internal fun MusicService.refreshAutomotiveLibrary() {
    if (runCatching { mediaSession }.isFailure) return
    val pinnedCount =
        runCatching {
            AndroidAutoPinnedPlaylists
                .decode(dataStore.get(AndroidAutoPinnedPlaylistsJsonKey, "[]"))
                .size
        }.getOrDefault(0)
    runCatching {
        mediaSession.notifyChildrenChanged(MusicService.ROOT, AUTOMOTIVE_ROOT_ITEM_COUNT, null)
        mediaSession.notifyChildrenChanged(MusicService.QUEUE, player.mediaItemCount, null)
        mediaSession.notifyChildrenChanged(MusicService.PINNED_PLAYLISTS, pinnedCount, null)
    }.onFailure { error ->
        Timber.tag(TAG).w(error, "Failed to refresh automotive library")
    }
}
