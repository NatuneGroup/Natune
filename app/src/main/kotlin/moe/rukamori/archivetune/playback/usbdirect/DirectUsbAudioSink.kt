package moe.rukamori.archivetune.playback.usbdirect

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import kotlin.math.abs

/** Selects direct USB before DefaultAudioSink converts high-resolution integer PCM to float. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class DirectUsbAudioSink(private val context: Context, private val normal: AudioSink) : ForwardingAudioSink(normal) {
    private data class Config(val format: Format, val bufferSize: Int, val outputChannels: IntArray?)
    private var output: AudioOutput? = null
    private var pending: Config? = null
    private var configured: Config? = null
    private var listener: AudioSink.Listener? = null
    private var attributes = AudioAttributes.DEFAULT
    private var sessionId = C.AUDIO_SESSION_ID_UNSET
    private var volume = 1f
    private var playing = false
    private var ended = false
    private var endOfStreamRequested = false
    private var requestedParameters = PlaybackParameters.DEFAULT
    private var skipSilence = false
    private var startTimeUs = C.TIME_UNSET
    private var submittedFrames = 0L
    private var discontinuity = false
    private var currentBuffer: ByteBuffer? = null
    private var advancingReported = false
    private var forceFallback = false
    private var lastPositionUs = Long.MIN_VALUE

    private fun wantsDirect(format: Format): Boolean = !forceFallback &&
        requestedParameters == PlaybackParameters.DEFAULT && !skipSilence &&
        format.sampleMimeType == MimeTypes.AUDIO_RAW && format.encoderDelay == 0 && format.encoderPadding == 0 &&
        UsbDirectAudioOutput.supports(context, format.sampleRate, format.channelCount, format.pcmEncoding)

    override fun supportsFormat(format: Format): Boolean = wantsDirect(format) || normal.supportsFormat(format)
    override fun getFormatSupport(format: Format): Int = if (wantsDirect(format))
        AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY else normal.getFormatSupport(format)

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        forceFallback = false
        pending = Config(inputFormat, specifiedBufferSize, outputChannels)
        ended = false
        endOfStreamRequested = false
    }

    private fun selectOutput(): Boolean {
        val config = pending ?: return true
        if (output != null) {
            if (directPending()) return false
            closeOutput()
        } else if (normal.hasPendingData()) {
            normal.playToEndOfStream()
            if (!normal.isEnded) return false
        }
        normal.reset()
        configured = config
        val format = config.format
        val mapping = config.outputChannels
        val identityMapping = mapping == null ||
            (mapping.size == format.channelCount && (0 until mapping.size).all { mapping[it] == it })
        if (identityMapping && wantsDirect(format)) {
            val outConfig = OutputConfig.Builder()
                .setSampleRate(format.sampleRate)
                .setEncoding(format.pcmEncoding)
                .setChannelMask(Util.getAudioTrackChannelConfig(format.channelCount))
                .setBufferSize(format.sampleRate / 4 * Util.getPcmFrameSize(format.pcmEncoding, format.channelCount))
                .setAudioAttributes(attributes).setAudioSessionId(sessionId).build()
            output = UsbDirectAudioOutput.open(context, outConfig)
        }
        if (output == null) normal.configure(config.format, config.bufferSize, config.outputChannels)
        resetTimeline()
        pending = null
        if (playing) { output?.play() ?: normal.play() }
        output?.setVolume(volume)
        return true
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        try {
            if (!selectOutput()) return false
            val direct = output ?: return normal.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            val format = checkNotNull(configured).format
            val frameBytes = Util.getPcmFrameSize(format.pcmEncoding, format.channelCount)
            if (currentBuffer !== buffer) {
                currentBuffer = buffer
                if (startTimeUs == C.TIME_UNSET) startTimeUs = presentationTimeUs
                val expected = startTimeUs + submittedFrames * 1_000_000L / format.sampleRate
                if (discontinuity || abs(expected - presentationTimeUs) > 200_000) {
                    startTimeUs += presentationTimeUs - expected
                    listener?.onPositionDiscontinuity()
                    discontinuity = false
                }
            }
            val before = buffer.position()
            val complete = direct.write(buffer, encodedAccessUnitCount, presentationTimeUs)
            submittedFrames += (buffer.position() - before) / frameBytes
            if (complete) currentBuffer = null
            return complete
        } catch (error: AudioOutput.WriteException) {
            fallback()
            return false
        }
    }

    private fun directPending(): Boolean {
        val direct = output ?: return false
        val rate = configured?.format?.sampleRate ?: return false
        return direct.positionUs < submittedFrames * 1_000_000L / rate
    }

    private fun fallback() {
        closeOutput()
        forceFallback = true
        configured = pending ?: configured
        configured?.let { normal.configure(it.format, it.bufferSize, it.outputChannels) }
        pending = null
        normal.setPlaybackParameters(requestedParameters)
        normal.setSkipSilenceEnabled(skipSilence)
        if (playing) normal.play()
        listener?.onPositionDiscontinuity()
    }

    override fun playToEndOfStream() {
        try {
            if (!selectOutput()) return
            endOfStreamRequested = true
            if (output == null) { normal.playToEndOfStream(); return }
            if (!directPending()) { closeOutput(); ended = true }
        } catch (error: AudioOutput.WriteException) { fallback() }
    }
    override fun hasPendingData(): Boolean = pending != null || try {
        if (output != null) directPending() else !ended && normal.hasPendingData()
    } catch (error: AudioOutput.WriteException) { fallback(); false }
    override fun isEnded(): Boolean = pending == null && (ended || try {
        if (output != null) endOfStreamRequested && !directPending() else normal.isEnded
    } catch (error: AudioOutput.WriteException) { fallback(); false })
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val direct = output ?: return if (ended) lastPositionUs else normal.getCurrentPositionUs(sourceEnded)
        if (startTimeUs == C.TIME_UNSET) return Long.MIN_VALUE
        return try {
            val playedUs = direct.positionUs
            if (playedUs > 0 && !advancingReported) {
                advancingReported = true
                listener?.onPositionAdvancing(System.currentTimeMillis() - playedUs / 1000)
            }
            (startTimeUs + playedUs).also { lastPositionUs = it }
        } catch (error: AudioOutput.WriteException) { fallback(); lastPositionUs }
    }
    override fun play() {
        playing = true
        try { output?.play() ?: normal.play() } catch (error: AudioOutput.WriteException) { fallback() }
    }
    override fun pause() {
        playing = false
        try { output?.pause() ?: normal.pause() } catch (error: AudioOutput.WriteException) { fallback() }
    }
    override fun flush() {
        closeOutput()
        normal.flush()
        pending = configured
        ended = false
        resetTimeline()
    }
    override fun reset() {
        closeOutput(); normal.reset(); pending = null; configured = null; ended = false; resetTimeline()
    }
    override fun release() { reset(); normal.release() }
    override fun handleDiscontinuity() { discontinuity = true; if (output == null) normal.handleDiscontinuity() }
    override fun setListener(listener: AudioSink.Listener) { this.listener = listener; normal.setListener(listener) }
    override fun setVolume(volume: Float) { this.volume = volume; output?.setVolume(volume); normal.setVolume(volume) }
    override fun setAudioAttributes(audioAttributes: AudioAttributes) { attributes = audioAttributes; normal.setAudioAttributes(audioAttributes) }
    override fun setAudioSessionId(audioSessionId: Int) { sessionId = audioSessionId; normal.setAudioSessionId(audioSessionId) }
    override fun getAudioTrackBufferSizeUs(): Long = output?.let { it.bufferSizeInFrames * 1_000_000L / it.sampleRate }
        ?: normal.audioTrackBufferSizeUs
    override fun getSkipSilenceEnabled(): Boolean = if (output != null) false else normal.skipSilenceEnabled
    override fun getPlaybackParameters(): PlaybackParameters = if (output != null) PlaybackParameters.DEFAULT else normal.playbackParameters
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        requestedParameters = playbackParameters
        normal.setPlaybackParameters(playbackParameters)
        if (output != null && playbackParameters != PlaybackParameters.DEFAULT) fallback()
    }
    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        skipSilence = skipSilenceEnabled; normal.setSkipSilenceEnabled(skipSilenceEnabled)
        if (output != null && skipSilenceEnabled) fallback()
    }
    private fun closeOutput() {
        output?.let { direct ->
            runCatching { if (startTimeUs != C.TIME_UNSET) lastPositionUs = startTimeUs + direct.positionUs }
            direct.release()
        }
        output = null
    }
    private fun resetTimeline() {
        startTimeUs = C.TIME_UNSET; submittedFrames = 0L; currentBuffer = null
        discontinuity = false; advancingReported = false; endOfStreamRequested = false
    }
}
