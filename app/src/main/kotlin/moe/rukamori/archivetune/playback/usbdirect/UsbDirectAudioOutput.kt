package moe.rukamori.archivetune.playback.usbdirect

import android.content.Context
import android.media.AudioDeviceInfo
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.playback.BitPerfectUsbOutput
import moe.rukamori.archivetune.playback.ConvertingAudioOutput
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@androidx.annotation.OptIn(UnstableApi::class)
internal class UsbDirectAudioOutput private constructor(
    private val config: OutputConfig,
    private val devices: UsbDirectDevices,
    private val context: Context,
    private var handle: Long,
    val deviceId: Int,
    private val capacity: Int,
    private val frameBytes: Int,
    private val volume: UsbVolume.Volume,
) : AudioOutput {
    private val listeners = CopyOnWriteArraySet<AudioOutput.Listener>()
    private val volumeExecutor = Executors.newSingleThreadExecutor()
    private val volumePending = AtomicBoolean(false)
    private val volumeRevision = AtomicInteger()
    private val volumeCanRise = AtomicBoolean(false)
    @Volatile private var playerVolume = 1f
    @Volatile private var failure = 0
    private var writtenFrames = 0L
    private var exact = true
    private val control = controlFor(handle)

    private fun muted(): Boolean = playerVolume <= 0f || devices.volumeFraction() <= 0f

    fun markInexact() {
        if (!exact) return
        exact = false
        BitPerfectUsbOutput.directInexact(context)
    }

    fun markDetached() { failure = -19 }

    fun refreshVolume(userChange: Boolean = false) {
        if (userChange) volumeCanRise.set(true)
        volumeRevision.incrementAndGet()
        if (failure != 0 || !volumePending.compareAndSet(false, true)) return
        runCatching { volumeExecutor.execute {
            var served = -1
            try {
                do {
                    served = volumeRevision.get()
                    synchronized(this) {
                        if (handle != 0L) {
                            val db = UsbVolume.decibels(volume.snapshot, devices.volumeFraction() * playerVolume)
                            if (volumeCanRise.get()) volume.set(control, db) else volume.setInitial(control, db)
                        }
                    }
                } while (served != volumeRevision.get() && failure == 0)
            } catch (_: Exception) {
                failure = -1005
            } finally {
                volumePending.set(false)
                if (served != volumeRevision.get() && failure == 0) refreshVolume()
            }
        } }.onFailure { volumePending.set(false) }
    }

    private fun check(result: Int): Int {
        if (result < 0) {
            failure = result
            devices.failed(deviceId)
            BitPerfectUsbOutput.directFailed(context)
            throw AudioOutput.WriteException(result, true)
        }
        return result
    }

    @Synchronized override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        if (failure != 0 || handle == 0L) check(if (failure != 0) failure else -1004)
        val count = minOf(buffer.remaining(), 256 * 1024) / frameBytes * frameBytes
        val result = check(UsbDirectNative.nativeWrite(handle, buffer, buffer.position(), count))
        buffer.position(buffer.position() + result)
        writtenFrames += result / frameBytes
        return !buffer.hasRemaining()
    }
    @Synchronized override fun play() { if (handle != 0L) check(UsbDirectNative.nativeSetPlaying(handle, true)) }
    @Synchronized override fun pause() { if (handle != 0L) check(UsbDirectNative.nativeSetPlaying(handle, false)) }
    @Synchronized override fun flush() {
        if (handle != 0L) check(UsbDirectNative.nativeFlush(handle))
        writtenFrames = 0L
    }
    override fun stop() = Unit // Keep the bus running until accepted PCM has drained.
    @Synchronized override fun release() {
        if (handle == 0L) return
        volumeExecutor.shutdownNow()
        runCatching { volume.restore(control) }
        UsbDirectNative.nativeClose(handle)
        handle = 0L
        devices.closed(this)
        listeners.forEach { it.onReleased() }
        listeners.clear()
    }
    @Synchronized override fun getPositionUs(): Long {
        if (failure != 0) check(failure)
        if (handle == 0L) return 0L
        val frames = UsbDirectNative.nativePlayedFrames(handle)
        if (frames < 0) check(frames.toInt())
        return minOf(writtenFrames, frames) * 1_000_000L / config.sampleRate
    }
    override fun getBufferSizeInFrames(): Long = capacity.toLong()
    override fun getSampleRate(): Int = config.sampleRate
    override fun getAudioSessionId(): Int = config.audioSessionId
    override fun isOffloadedPlayback(): Boolean = false
    override fun isStalled(): Boolean = failure != 0
    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
    override fun setPlaybackParameters(playbackParams: PlaybackParameters) = Unit
    override fun setVolume(volume: Float) {
        val next = volume.coerceIn(0f, 1f)
        val changed = next != playerVolume
        playerVolume = next
        refreshVolume(userChange = changed)
    }
    override fun addListener(listener: AudioOutput.Listener) { listeners.add(listener) }
    override fun removeListener(listener: AudioOutput.Listener) { listeners.remove(listener) }
    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) = Unit
    override fun setOffloadEndOfStream() = Unit
    override fun attachAuxEffect(effectId: Int) = Unit
    override fun setAuxEffectSendLevel(level: Float) = Unit
    override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) = Unit

    companion object {
        fun sourceBits(encoding: Int): Int = when (encoding) {
            C.ENCODING_PCM_16BIT -> 16
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_FLOAT -> 24
            C.ENCODING_PCM_32BIT -> 32
            else -> 0
        }

        private fun controlFor(handle: Long) = UsbControl { input, request, value, index, data ->
            val result = UsbDirectNative.nativeControl(handle, input, request, value, index, data)
            check(result == data.size) { "USB control failed: $result" }
        }

        fun supports(context: Context, sampleRate: Int, channels: Int, encoding: Int): Boolean {
            if (!BitPerfectUsbOutput.enabled || !BitPerfectUsbOutput.directEnabled || !UsbDirectNative.available || sourceBits(encoding) == 0) return false
            val devices = BitPerfectUsbOutput.directDevices(context)
            return runCatching { devices.audioDevices().any { device ->
                devices.raw(device)?.let { raw -> UsbDescriptors.plans(raw, sampleRate, channels, sourceBits(encoding)).any { plan ->
                    UsbVolume.feature(raw, plan.alternate.control, plan.alternate.terminal, channels) != null
                } } == true
            } }.getOrDefault(false)
        }

        fun open(context: Context, config: OutputConfig): AudioOutput? {
            if (!BitPerfectUsbOutput.enabled || !BitPerfectUsbOutput.directEnabled) return null
            val devices = BitPerfectUsbOutput.directDevices(context)
            if (!devices.acquire()) return null
            var handle = 0L
            try {
                BitPerfectUsbOutput.clear(context)
                val usb = devices.manager ?: return null
                for (device in devices.audioDevices()) {
                    val raw = devices.raw(device) ?: continue
                    val plans = UsbDescriptors.plans(raw, config.sampleRate, Integer.bitCount(config.channelMask), sourceBits(config.encoding))
                        .filter { UsbVolume.feature(raw, it.alternate.control, it.alternate.terminal, it.format.channels) != null }
                    if (plans.isEmpty()) continue
                    val connection = usb.openDevice(device) ?: continue
                    val result = IntArray(2)
                    // Native duplicates the descriptor. Close Java's copy before native ever releases URBs.
                    try { handle = UsbDirectNative.nativeOpen(connection.fileDescriptor, UsbDirectNative.encode(plans), result) }
                    finally { connection.close() }
                    if (handle == 0L) continue
                    val plan = plans[result[0]]
                    val feature = UsbVolume.feature(raw, plan.alternate.control, plan.alternate.terminal, plan.format.channels)
                        ?: throw IllegalStateException("USB hardware volume unavailable")
                    val volume = UsbVolume.Volume.open(controlFor(handle), feature)
                    try { volume.setInitial(controlFor(handle), UsbVolume.decibels(volume.snapshot, devices.volumeFraction())) }
                    catch (error: Exception) { runCatching { volume.restore(controlFor(handle)) }; throw error }
                    val output = UsbDirectAudioOutput(config, devices, context, handle, device.deviceId, result[1],
                        plan.format.subslot * plan.format.channels, volume)
                    devices.opened(output)
                    BitPerfectUsbOutput.directActive(device.deviceId, device.productName.orEmpty(),
                        context.getString(R.string.bit_perfect_usb_direct_format, plan.format.bits, config.sampleRate))
                    val wireEncoding = when (plan.format.subslot) {
                        2 -> C.ENCODING_PCM_16BIT
                        3 -> C.ENCODING_PCM_24BIT
                        else -> C.ENCODING_PCM_32BIT
                    }
                    handle = 0L // Ownership passed to output.
                    return ConvertingAudioOutput(output, config.encoding, wireEncoding, plan.format.channels, plan.format.bits, output::markInexact, output::muted)
                }
            } catch (error: Exception) {
                timber.log.Timber.w(error, "Direct USB DAC open failed; using platform output")
                BitPerfectUsbOutput.directFailed(context)
            } finally {
                if (handle != 0L) UsbDirectNative.nativeClose(handle)
                if (!devices.hasActiveOutput()) devices.abandonOpen()
            }
            return null
        }

    }
}
