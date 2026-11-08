package moe.rukamori.archivetune.desktop.playback

import moe.rukamori.archivetune.shared.domain.dsp.bitperfect.BitPerfectDecision
import moe.rukamori.archivetune.shared.domain.dsp.bitperfect.BitPerfectGate
import moe.rukamori.archivetune.shared.domain.dsp.bitperfect.BitPerfectRouteFacts
import moe.rukamori.archivetune.shared.domain.dsp.bitperfect.SoftwareStagePlan
import moe.rukamori.archivetune.shared.domain.settings.*

/** PCM format values match the private native device ABI, independently of FFmpeg sample enums. */
internal enum class DesktopPcmFormat(val code: Int, val bits: Int) {
    UNKNOWN(0, 0), U8(1, 8), S16(2, 16), S24(3, 24), S32(4, 32), F32(5, 32);
    companion object { fun fromCode(code: Int) = entries.firstOrNull { it.code == code } ?: UNKNOWN }
}

internal data class DesktopOutputDevice(val id: String, val name: String)

/** Observations from the decoded track and the opened sink. A setting alone never activates a sink. */
internal data class DesktopOutputState(
    val requested: Boolean = false,
    val exclusiveActive: Boolean = false,
    val directPcm: Boolean = false,
    val muted: Boolean = false,
    val inputRateHz: Int = 0,
    val inputBits: Int = 0,
    val inputChannels: Int = 0,
    val inputFormat: DesktopPcmFormat = DesktopPcmFormat.UNKNOWN,
    val outputRateHz: Int = 0,
    val outputBits: Int = 0,
    val outputChannels: Int = 0,
    val outputFormat: DesktopPcmFormat = DesktopPcmFormat.UNKNOWN,
    val deviceName: String = "",
    val backend: String = "",
    val reason: String = "",
)

internal fun desktopExclusiveRequested(preferences: PreferenceSnapshot?): Boolean = preferences != null &&
    (preferences[BitPerfectUsbOutputKey, false] || preferences[UsbExclusiveAudioKey, false]) &&
    !preferences[CrossfadeEnabledKey, false] && !preferences[AutomixEnabledKey, false]

internal object DesktopExclusiveOutputPolicy {
    fun decision(state: DesktopOutputState): BitPerfectDecision {
        val known = state.inputRateHz > 0 && state.inputBits > 0 && state.inputChannels > 0 &&
            state.inputFormat != DesktopPcmFormat.UNKNOWN
        val exact = known && state.directPcm && state.exclusiveActive &&
            state.outputRateHz == state.inputRateHz && state.outputChannels == state.inputChannels &&
            state.outputFormat == state.inputFormat && state.outputBits >= state.inputBits
        return BitPerfectGate.evaluate(BitPerfectRouteFacts(
            bitPerfectRequested = state.requested,
            // The native direct lane cannot invoke DSP or software gain. Stored edits resume in shared mode.
            softwareDspEngaged = state.muted,
            usbExclusive = false,
            directPlaybackQueried = known,
            directPlaybackSupported = exact,
            inputSampleRateHz = state.inputRateHz.takeIf { it > 0 } ?: NativeTwoDeckMixer.DEFAULT_SAMPLE_RATE_HZ,
            inputBitDepth = state.inputBits.takeIf { it > 0 } ?: 32,
            outputSampleRateHz = state.outputRateHz,
            effectiveVolume = 1f,
        ))
    }

    fun plan(state: DesktopOutputState, config: StageConfig): SoftwareStagePlan =
        if (!state.requested) desktopStagePlan(config.copy(routeFacts = config.routeFacts.copy(bitPerfectRequested = false)))
        else SoftwareStagePlan.resolve(decision(state), config.request.copy(
            normalizationEnabled = false, equalizerEnabled = false, speed = 1.0, pitchSemitones = 0.0,
            silenceSkippingEnabled = false, twoDeckMixingEnabled = false))
}
