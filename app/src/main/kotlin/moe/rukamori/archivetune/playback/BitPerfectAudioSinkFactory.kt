package moe.rukamori.archivetune.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import moe.rukamori.archivetune.playback.usbdirect.DirectUsbAudioSink

@androidx.annotation.OptIn(UnstableApi::class)
internal fun newBitPerfectAudioSink(context: Context): AudioSink {
    // Direct USB sees integer PCM before Media3's float conversion. The existing platform
    // route keeps its float bypass for exact 16/24-bit input when direct USB is unavailable.
    val platform = DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(true)
        .setEnableAudioTrackPlaybackParams(false)
        .setAudioOutputProvider(BitPerfectAudioOutputProvider(context, AudioTrackAudioOutputProvider.Builder(context).build()))
        .build()
    return DirectUsbAudioSink(context, platform)
}
