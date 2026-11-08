package moe.rukamori.archivetune.shared.data.platform.ios

import cnames.structs.at_audio_output
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.rukamori.archivetune.nativeaudio.*

/** Decoder configuration and AVAudioEngine-reported route rate; neither measures physical DAC output. */
data class IosAudioOutputFormat(
    val pcmRate: Int = 0,
    val routeRate: Double = 0.0,
    val usb: Boolean = false,
    val requestedUsbRate: Int = 0,
)

/** Control and reporting stay on the port's main scope; the realtime callback only consumes PCM. */
@OptIn(ExperimentalForeignApi::class)
internal class IosUsbOutputControl(private val scope: CoroutineScope) {
    private val formats = MutableStateFlow(IosAudioOutputFormat())
    val state = formats.asStateFlow()
    fun setRate(rate: Int) {
        scope.launch {
            val supported = rate.takeIf { it in setOf(0, 48000, 88200, 96000, 176400, 192000) } ?: 0
            at_audio_usb_sample_rate(supported.toUInt())
            formats.value = formats.value.copy(requestedUsbRate = supported)
        }
    }
    fun observe(output: CPointer<at_audio_output>) {
        formats.value = IosAudioOutputFormat(at_audio_pcm_sample_rate(output).toInt(),
            at_audio_device_sample_rate(output), at_audio_usb_route_active() != 0, formats.value.requestedUsbRate)
    }
}
