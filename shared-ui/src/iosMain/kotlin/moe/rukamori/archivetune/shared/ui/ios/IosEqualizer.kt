package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import moe.rukamori.archivetune.shared.domain.settings.IosUsbOutputSampleRateKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.rukamori.archivetune.shared.data.platform.ios.IosPlaybackPort
import moe.rukamori.archivetune.shared.domain.settings.SettingsStore
import moe.rukamori.archivetune.shared.ui.dsp.DspSettingsOwner
import moe.rukamori.archivetune.shared.ui.equalizer.EqualizerPanel

/**
 * The one DSP settings surface of the iOS host, mirroring the desktop owner: the shared DSP document, read and
 * written by [DspSettingsOwner], whose equalizer bands and normalization switch reach every output and blend deck
 * through [playback].
 *
 * The ten-band layout, the millibel levels, the document and the band-to-unit conversion are shared with the other
 * hosts, so this host carries no band model of its own and the equalizer it shows is the one the chain applies.
 */
internal class IosEqualizer(
    scope: CoroutineScope,
    settings: SettingsStore,
    playback: IosPlaybackPort,
) {
    private val dsp = DspSettingsOwner(
        scope = scope,
        store = settings,
        onApplied = playback::applyDspSettings,
    )
    private val sheet = MutableStateFlow(false)
    init {
        scope.launch {
            settings.snapshots.map { it?.get(IosUsbOutputSampleRateKey) ?: 0 }.distinctUntilChanged()
                .collect(playback::setUsbOutputSampleRate)
        }
    }

    /** The current settings, read by the panel. */
    val state: StateFlow<DspSettingsOwner.State> = dsp.state

    /** Whether the equalizer panel is open over the root. */
    val sheetOpen: StateFlow<Boolean> = sheet.asStateFlow()

    /** Turns the equalizer on or off and saves it. */
    fun setEnabled(enabled: Boolean) = dsp.setEqualizerEnabled(enabled)

    /** Applies one band's gain while its handle is dragged. The engine retunes at once and nothing is saved yet. */
    fun previewGain(index: Int, gainDb: Double) = dsp.previewGain(index, gainDb)

    /** Keeps the last gain of a drag and saves it once. */
    fun commitGain(index: Int, gainDb: Double) = dsp.commitGain(index, gainDb)

    /** Sets every band back to 0 dB and saves it. */
    fun resetFlat() = dsp.resetFlat()

    /** Opens the equalizer panel over the root. */
    fun openSheet() {
        sheet.value = true
    }

    /** Closes the equalizer panel. */
    fun closeSheet() {
        sheet.value = false
    }
}

/** The equalizer panel over the root, opened from the song menu or the shared player-menu action. */
@Composable
internal fun IosEqualizerOverlay(equalizer: IosEqualizer) {
    val open by equalizer.sheetOpen.collectAsState()
    if (!open) return
    val state by equalizer.state.collectAsState()
    Dialog(onDismissRequest = equalizer::closeSheet) {
        EqualizerPanel(
            enabled = state.equalizerEnabled,
            bands = state.bands,
            onEnabledChange = equalizer::setEnabled,
            onGainChange = equalizer::previewGain,
            onGainChangeFinished = equalizer::commitGain,
            onReset = equalizer::resetFlat,
            modifier = Modifier.widthIn(max = 560.dp).padding(16.dp),
        )
    }
}
