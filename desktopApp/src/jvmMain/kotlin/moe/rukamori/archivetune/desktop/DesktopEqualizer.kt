package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.desktop.playback.DesktopPlaybackPort
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBand
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBandLevels
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBandType
import moe.rukamori.archivetune.shared.domain.settings.EqualizerBandLevelsMbKey
import moe.rukamori.archivetune.shared.domain.settings.EqualizerEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.ui.equalizer.EqualizerPanel
import moe.rukamori.archivetune.shared.ui.settings.VaultSettingsStore

/**
 * Octave centres of the ten bands. This host has no device band list, so it fixes its own layout; Android keeps its.
 */
private val DESKTOP_EQUALIZER_CENTERS_HZ = listOf(
    31.25, 62.5, 125.0, 250.0, 500.0, 1_000.0, 2_000.0, 4_000.0, 8_000.0, 16_000.0,
)

/** One octave of bandwidth per peaking band, the usual graphic-equalizer spacing. */
private const val DESKTOP_EQUALIZER_Q = 1.4142135623730951

/** Millibels per decibel, the unit the stored levels use. */
private const val MILLIBELS_PER_DB = 100

/** The equalizer as the panel shows it: whether it is on, each band's level in millibels, and the bands they set. */
internal data class DesktopEqualizerState(
    val enabled: Boolean,
    val levelsMb: List<Int>,
    val bands: List<EqualizerBand>,
)

/**
 * The two stored values the equalizer reads. Compared as a pair, so an unrelated settings change never re-applies it.
 */
private data class StoredEqualizer(val enabled: Boolean, val levels: String?)

/**
 * The desktop equalizer. Its bands reach the engine through [playback]; its state is saved under the keys Android
 * uses, the on/off flag and the band levels in millibels.
 *
 * A drag applies live through [previewGain] and is saved once through [commitGain]. A change saved by another writer
 * is adopted when the stored pair differs from the one already applied, so this host's own saves do not reapply.
 */
internal class DesktopEqualizer(
    private val scope: CoroutineScope,
    private val settings: VaultSettingsStore,
    private val playback: DesktopPlaybackPort,
) {
    private val published = MutableStateFlow(stateOf(enabled = false, levelsMb = flatLevels()))
    private val sheet = MutableStateFlow(false)

    /** The current equalizer, read by the panel. */
    val state: StateFlow<DesktopEqualizerState> = published.asStateFlow()

    /** Whether the equalizer panel is open over the window. */
    val sheetOpen: StateFlow<Boolean> = sheet.asStateFlow()

    init {
        // The stored value is applied at once, so the engine matches the saved equalizer before the first track loads.
        adopt(storedOf(settings.snapshots.value))
        scope.launch {
            settings.snapshots
                .map { storedOf(it) }
                .distinctUntilChanged()
                .collect { adopt(it) }
        }
    }

    /** Turns the equalizer on or off and saves the flag. */
    fun setEnabled(enabled: Boolean) {
        val current = published.value
        publish(enabled = enabled, levelsMb = current.levelsMb)
        save(enabled = enabled)
    }

    /** Applies one band's gain while its handle is dragged. The engine retunes at once and nothing is saved yet. */
    fun previewGain(index: Int, gainDb: Double) {
        val current = published.value
        val levelsMb = withGain(current.levelsMb, index, gainDb) ?: return
        publish(enabled = current.enabled, levelsMb = levelsMb)
    }

    /** Keeps the last gain of a drag and saves the levels once. */
    fun commitGain(index: Int, gainDb: Double) {
        val current = published.value
        val levelsMb = withGain(current.levelsMb, index, gainDb) ?: return
        publish(enabled = current.enabled, levelsMb = levelsMb)
        save(levelsMb = levelsMb)
    }

    /** Sets every band back to 0 dB and saves the levels. */
    fun resetFlat() {
        val current = published.value
        val levelsMb = flatLevels()
        publish(enabled = current.enabled, levelsMb = levelsMb)
        save(levelsMb = levelsMb)
    }

    /** Opens the equalizer panel over the window. */
    fun openSheet() {
        sheet.value = true
    }

    /** Closes the equalizer panel. */
    fun closeSheet() {
        sheet.value = false
    }

    private fun storedOf(snapshot: PreferenceSnapshot?): StoredEqualizer = StoredEqualizer(
        enabled = snapshot?.get(EqualizerEnabledKey) ?: false,
        levels = snapshot?.get(EqualizerBandLevelsMbKey),
    )

    private fun adopt(stored: StoredEqualizer) {
        publish(
            enabled = stored.enabled,
            levelsMb = EqualizerBandLevels.decode(stored.levels, DESKTOP_EQUALIZER_CENTERS_HZ.size),
        )
    }

    private fun publish(enabled: Boolean, levelsMb: List<Int>) {
        val next = stateOf(enabled, levelsMb)
        published.value = next
        playback.setEqualizer(enabled, next.bands)
    }

    private fun withGain(levelsMb: List<Int>, index: Int, gainDb: Double): List<Int>? {
        if (index !in levelsMb.indices || !gainDb.isFinite()) return null
        val level = (gainDb * MILLIBELS_PER_DB).roundToInt()
            .coerceIn(-EqualizerBandLevels.MAX_LEVEL_MB, EqualizerBandLevels.MAX_LEVEL_MB)
        return levelsMb.toMutableList().also { it[index] = level }
    }

    /** Writes only the keys it was given, so two saves of different values never overwrite each other. */
    private fun save(enabled: Boolean? = null, levelsMb: List<Int>? = null) {
        scope.launch {
            try {
                settings.edit {
                    if (enabled != null) set(EqualizerEnabledKey, enabled)
                    if (levelsMb != null) set(EqualizerBandLevelsMbKey, EqualizerBandLevels.encode(levelsMb))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                desktopDiagnostic("equalizer was not saved: ${failure.javaClass.simpleName}")
            }
        }
    }

    private companion object {
        fun flatLevels(): List<Int> = List(DESKTOP_EQUALIZER_CENTERS_HZ.size) { 0 }

        fun stateOf(enabled: Boolean, levelsMb: List<Int>): DesktopEqualizerState = DesktopEqualizerState(
            enabled = enabled,
            levelsMb = levelsMb,
            bands = DESKTOP_EQUALIZER_CENTERS_HZ.mapIndexed { index, centerHz ->
                EqualizerBand(
                    type = EqualizerBandType.PEAKING,
                    frequencyHz = centerHz,
                    gainDb = levelsMb[index].toDouble() / MILLIBELS_PER_DB,
                    q = DESKTOP_EQUALIZER_Q,
                )
            },
        )
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
            enabled = state.enabled,
            bands = state.bands,
            onEnabledChange = equalizer::setEnabled,
            onGainChange = equalizer::previewGain,
            onGainChangeFinished = equalizer::commitGain,
            onReset = equalizer::resetFlat,
            modifier = Modifier.widthIn(max = 560.dp).padding(16.dp),
        )
    }
}
