package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.rukamori.archivetune.desktop.playback.DesktopPlaybackPort
import moe.rukamori.archivetune.shared.domain.settings.SettingsStore
import moe.rukamori.archivetune.shared.ui.dsp.DspSettingsOwner
import moe.rukamori.archivetune.shared.ui.equalizer.EqualizerPanel

/**
 * The one DSP settings surface of the window: the shared DSP document, read and written by [DspSettingsOwner],
 * whose equalizer bands and normalization switch reach the engine through [playback].
 *
 * The ten-band layout, the millibel levels and the document itself are shared with the other hosts, so this host
 * carries no band model of its own and the equalizer it shows is the one the chain applies. Its levels are stored
 * in millibels, the resolution the panel shows, so a drag round-trips through the document unchanged.
 */
internal class DesktopEqualizer(
    scope: CoroutineScope,
    settings: SettingsStore,
    playback: DesktopPlaybackPort,
) {
    private val dsp = DspSettingsOwner(
        scope = scope,
        store = settings,
        onApplied = playback::applyDspSettings,
        onSaveFailure = { desktopDiagnostic("equalizer was not saved: ${it.javaClass.simpleName}") },
    )
    private val sheet = MutableStateFlow(false)

    /** The current settings, read by the panel. */
    val state: StateFlow<DspSettingsOwner.State> = dsp.state

    /** Whether the equalizer panel is open over the window. */
    val sheetOpen: StateFlow<Boolean> = sheet.asStateFlow()

    /** Turns the equalizer on or off and saves it. */
    fun setEnabled(enabled: Boolean) = dsp.setEqualizerEnabled(enabled)

    /** Applies one band's gain while its handle is dragged. The engine retunes at once and nothing is saved yet. */
    fun previewGain(index: Int, gainDb: Double) = dsp.previewGain(index, gainDb)

    /** Keeps the last gain of a drag and saves it once. */
    fun commitGain(index: Int, gainDb: Double) = dsp.commitGain(index, gainDb)

    /** Sets every band back to 0 dB and saves it. */
    fun resetFlat() = dsp.resetFlat()

    /** Opens the equalizer panel over the window. */
    fun openSheet() {
        sheet.value = true
    }

    /** Closes the equalizer panel. */
    fun closeSheet() {
        sheet.value = false
    }
}

/** The equalizer panel over the window, opened from the player menu. */
@Composable
internal fun DesktopEqualizerOverlay(equalizer: DesktopEqualizer) {
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
