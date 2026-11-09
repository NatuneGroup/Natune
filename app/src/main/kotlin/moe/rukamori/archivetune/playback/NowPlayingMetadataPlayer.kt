@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package moe.rukamori.archivetune.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.DeviceInfo
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import java.util.IdentityHashMap

/** Session/notification metadata is a projection; resolving artwork never edits the playlist. */
internal class NowPlayingMetadataPlayer(
    target: Player,
    private val resolve: (MediaItem?, MediaMetadata) -> MediaMetadata,
) : ForwardingPlayer(target) {
    private val listeners = IdentityHashMap<Player.Listener, Player.Listener>()
    private var publishedMetadata: MediaMetadata? = null

    override fun getMediaMetadata(): MediaMetadata = resolve(currentMediaItem, super.getMediaMetadata())

    override fun addListener(listener: Player.Listener) {
        if (listeners.containsKey(listener)) return
        val forwarding = SessionListener(listener)
        listeners[listener] = forwarding
        super.addListener(forwarding)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)?.let { super.removeListener(it) }
    }

    /** Called after normal transitions, crossfade promotion, and current-track enrichment. */
    fun publishMetadata() {
        val metadata = mediaMetadata
        if (metadata == publishedMetadata) return
        publishedMetadata = metadata
        val events = Player.Events(FlagSet.Builder().add(Player.EVENT_MEDIA_METADATA_CHANGED).build())
        val snapshot = listeners.keys.toList()
        snapshot.forEach { if (listeners.containsKey(it)) it.onMediaMetadataChanged(metadata) }
        snapshot.forEach { if (listeners.containsKey(it)) it.onEvents(this, events) }
    }

    /**
     * Every Player.Listener method is a Java default method, and Kotlin `by` delegation does not forward
     * those, so each callback is passed on explicitly. Only metadata is swapped for the resolved projection.
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private inner class SessionListener(private val listener: Player.Listener) : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            val resolved = getMediaMetadata()
            publishedMetadata = resolved
            listener.onMediaMetadataChanged(resolved)
        }

        override fun onEvents(player: Player, events: Player.Events) = listener.onEvents(this@NowPlayingMetadataPlayer, events)
        override fun onTimelineChanged(timeline: Timeline, reason: Int) = listener.onTimelineChanged(timeline, reason)
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = listener.onMediaItemTransition(mediaItem, reason)
        override fun onTracksChanged(tracks: Tracks) = listener.onTracksChanged(tracks)
        override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) = listener.onPlaylistMetadataChanged(mediaMetadata)
        override fun onIsLoadingChanged(isLoading: Boolean) = listener.onIsLoadingChanged(isLoading)
        override fun onLoadingChanged(isLoading: Boolean) = listener.onLoadingChanged(isLoading)
        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) =
            listener.onAvailableCommandsChanged(availableCommands)
        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) =
            listener.onTrackSelectionParametersChanged(parameters)
        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) =
            listener.onPlayerStateChanged(playWhenReady, playbackState)
        override fun onPlaybackStateChanged(playbackState: Int) = listener.onPlaybackStateChanged(playbackState)
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) =
            listener.onPlayWhenReadyChanged(playWhenReady, reason)
        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) =
            listener.onPlaybackSuppressionReasonChanged(playbackSuppressionReason)
        override fun onIsPlayingChanged(isPlaying: Boolean) = listener.onIsPlayingChanged(isPlaying)
        override fun onRepeatModeChanged(repeatMode: Int) = listener.onRepeatModeChanged(repeatMode)
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) =
            listener.onShuffleModeEnabledChanged(shuffleModeEnabled)
        override fun onPlayerError(error: PlaybackException) = listener.onPlayerError(error)
        override fun onPlayerErrorChanged(error: PlaybackException?) = listener.onPlayerErrorChanged(error)
        override fun onPositionDiscontinuity(reason: Int) = listener.onPositionDiscontinuity(reason)
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) =
            listener.onPositionDiscontinuity(oldPosition, newPosition, reason)
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) =
            listener.onPlaybackParametersChanged(playbackParameters)
        override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) = listener.onSeekBackIncrementChanged(seekBackIncrementMs)
        override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) =
            listener.onSeekForwardIncrementChanged(seekForwardIncrementMs)
        override fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) =
            listener.onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs)
        override fun onAudioSessionIdChanged(audioSessionId: Int) = listener.onAudioSessionIdChanged(audioSessionId)
        override fun onAudioAttributesChanged(audioAttributes: AudioAttributes) = listener.onAudioAttributesChanged(audioAttributes)
        override fun onVolumeChanged(volume: Float) = listener.onVolumeChanged(volume)
        override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = listener.onSkipSilenceEnabledChanged(skipSilenceEnabled)
        override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) = listener.onDeviceInfoChanged(deviceInfo)
        override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) = listener.onDeviceVolumeChanged(volume, muted)
        override fun onVideoSizeChanged(videoSize: VideoSize) = listener.onVideoSizeChanged(videoSize)
        override fun onSurfaceSizeChanged(width: Int, height: Int) = listener.onSurfaceSizeChanged(width, height)
        override fun onRenderedFirstFrame() = listener.onRenderedFirstFrame()
        override fun onCues(cues: List<Cue>) = listener.onCues(cues)
        override fun onCues(cueGroup: CueGroup) = listener.onCues(cueGroup)
        override fun onMetadata(metadata: Metadata) = listener.onMetadata(metadata)
    }
}
