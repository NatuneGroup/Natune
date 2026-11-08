package moe.rukamori.archivetune.desktop.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.rukamori.archivetune.shared.domain.dsp.DspSettings
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBand
import moe.rukamori.archivetune.shared.domain.dsp.normalization.NormalizationGain

/** Confined to the playback command owner; its immutable observations are safe for the settings UI. */
internal class DesktopPlaybackOutput {
    private var requested = false
    private var deviceId = ""
    private var volume = 1f
    private var enabled = true
    private val enabledValue = MutableStateFlow(true)
    val enabledState = enabledValue.asStateFlow()
    private val observed = MutableStateFlow(DesktopOutputState())
    val state = observed.asStateFlow()

    fun configure(mixer: NativeTwoDeckMixer?, exclusive: Boolean, id: String) {
        require(id.length < 512 && id.none { it.code < 32 })
        requested = exclusive
        deviceId = id
        observed.value = observed.value.copy(requested = exclusive)
        mixer?.configureOutput(exclusive, id)
    }

    fun create(backend: EngineBackend) = NativeTwoDeckMixer.create(
        backend, exclusive = requested, deviceId = deviceId).also { it.setOutputEnabled(enabled) }

    fun volume(value: Float) { volume = value }
    fun enabled(mixer: NativeTwoDeckMixer?, value: Boolean) {
        enabled = value; enabledValue.value = value
        mixer?.setOutputEnabled(value)
        observed.value = observed.value.copy(muted = !value)
    }
    fun gain(): Float = volume * if (enabled) 1f else 0f

    fun prepareTrack(mixer: NativeTwoDeckMixer) {
        mixer.configureOutput(requested, deviceId)
        mixer.prepareTrack()
    }

    fun refresh(mixer: NativeTwoDeckMixer): Boolean {
        val output = mixer.pollOutput()
        if (output == observed.value) return false
        observed.value = output
        return true
    }

    fun failureReason(): String? = observed.value.let {
        it.reason.takeIf { reason -> it.inputRateHz > 0 && it.outputRateHz == 0 &&
            reason.isNotBlank() && !reason.startsWith("Waiting") }
    }

    fun plan(config: StageConfig) = DesktopExclusiveOutputPolicy.plan(
        observed.value.copy(requested = requested || observed.value.directPcm), config)

    fun settings(stored: DspSettings, bands: List<EqualizerBand>, gainDb: Double?, peak: Double?): DspSettings =
        desktopOutputSettings(observed.value, stored, bands, gainDb, peak)

    fun close(mixer: NativeTwoDeckMixer?) {
        mixer?.close()
        observed.value = DesktopOutputState(requested = requested, muted = !enabled)
    }
}

/**
 * The settings one engine deck renders with. Only a sink that is actually playing the direct lane may drop the
 * user's stages: a requested but refused exclusive route is still shared output, where the stored equalizer and
 * normalization must keep working. The bit-perfect gate decides the reported/bypass plan separately.
 */
internal fun desktopOutputSettings(
    state: DesktopOutputState,
    stored: DspSettings,
    bands: List<EqualizerBand>,
    gainDb: Double?,
    peak: Double?,
): DspSettings {
    if (state.directPcm) return stored.withoutProcessing().copy(linearGain = 1f)
    return stored.copy(equalizerBands = bands, linearGain = NormalizationGain.linearGain(gainDb, peak))
}
