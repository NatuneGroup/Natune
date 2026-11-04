package moe.rukamori.archivetune.shared.ui.dsp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.dsp.DspSettings
import moe.rukamori.archivetune.shared.domain.dsp.DspSettingsDocument
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBand
import moe.rukamori.archivetune.shared.domain.dsp.eq.GraphicEqualizer
import moe.rukamori.archivetune.shared.domain.settings.DspSettingsDocumentKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.SettingsStore

/**
 * The DSP settings surface of a host that has no native settings screen: the ten-band graphic equalizer and
 * the normalization switch, as one [DspSettings] value stored as the single versioned document.
 *
 * The document is read once. A host that has none migrates the legacy keys it already wrote, publishes that
 * value and writes the document, so from then on there is one value rather than switches that can disagree.
 * The legacy keys stay on disk for older builds; nothing here writes them again.
 *
 * A document another host wrote with different bands shows flat, because a level per centre would then
 * describe another equalizer; the first edit writes this layout.
 */
class DspSettingsOwner(
    private val scope: CoroutineScope,
    private val store: SettingsStore,
    private val onApplied: (DspSettings) -> Unit,
    private val onSaveFailure: (Exception) -> Unit = {},
) {
    /** What a host shows: the current value and the band levels it holds. */
    data class State(
        val settings: DspSettings,
        val levelsMb: List<Int>,
    ) {
        val equalizerEnabled: Boolean get() = settings.equalizerEnabled
        val normalizationEnabled: Boolean get() = settings.normalizationEnabled
        val bands: List<EqualizerBand> = GraphicEqualizer.bandsFromLevelsMb(levelsMb)
    }

    private val published = MutableStateFlow(stateOf(DspSettingsDocument.of(raw = null, legacy = DspSettingsDocument.legacyOf(null))))

    /** The current settings, read by the equalizer panel. */
    val state: StateFlow<State> = published.asStateFlow()

    /** The document this surface last read or wrote. Its own save publishes this value, so it is not adopted back. */
    private var appliedDocument: String? = null
    private var adopted = false

    init {
        // The stored value applies at once, so the engine matches it before the first track loads.
        adopt(DspSettingsDocument.rawOf(store.snapshots.value), store.snapshots.value)
        scope.launch {
            store.snapshots
                .map { DspSettingsDocument.rawOf(it) }
                .distinctUntilChanged()
                .collect { raw -> adopt(raw, store.snapshots.value) }
        }
    }

    /** Turns the equalizer on or off and saves the document. */
    fun setEqualizerEnabled(enabled: Boolean) {
        commit(published.value.settings.copy(equalizerEnabled = enabled))
    }

    /** Turns track normalization on or off, which decides whether the chain applies the measured track gain. */
    fun setNormalizationEnabled(enabled: Boolean) {
        commit(published.value.settings.copy(normalizationEnabled = enabled))
    }

    /** Applies one band's gain while its handle is dragged. The engine retunes at once and nothing is saved yet. */
    fun previewGain(index: Int, gainDb: Double) {
        val next = withGain(published.value.settings, index, gainDb) ?: return
        publish(next)
    }

    /** Keeps the last gain of a drag and saves the document once. */
    fun commitGain(index: Int, gainDb: Double) {
        val next = withGain(published.value.settings, index, gainDb) ?: return
        commit(next)
    }

    /** Sets every band back to 0 dB and saves the document. */
    fun resetFlat() {
        commit(published.value.settings.copy(equalizerBands = GraphicEqualizer.bandsFromLevelsMb(GraphicEqualizer.flatLevelsMb())))
    }

    private fun adopt(raw: String?, snapshot: PreferenceSnapshot?) {
        if (adopted && raw == appliedDocument) return
        val stored = DspSettingsDocument.of(raw, DspSettingsDocument.legacyOf(snapshot))
        adopted = true
        publish(stored)
        // No document yet: write the migrated value once, so the two representations cannot drift apart later.
        if (raw == null) persist(DspSettingsDocument.write(stored))
    }

    private fun publish(next: DspSettings) {
        published.value = stateOf(next)
        onApplied(next)
    }

    private fun commit(next: DspSettings) {
        publish(next)
        persist(DspSettingsDocument.write(next))
    }

    /** Writes the document, remembering it first so the snapshot this write publishes is not adopted back. */
    private fun persist(document: String) {
        appliedDocument = document
        scope.launch {
            try {
                store.edit { set(DspSettingsDocumentKey, document) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // A lost save leaves the applied value in memory, so playback and the panel still agree.
                onSaveFailure(failure)
            }
        }
    }

    private fun stateOf(settings: DspSettings): State =
        State(settings, GraphicEqualizer.levelsMbOf(settings.equalizerBands))

    private fun withGain(settings: DspSettings, index: Int, gainDb: Double): DspSettings? {
        val levels = GraphicEqualizer.levelsMbOf(settings.equalizerBands).toMutableList()
        if (index !in levels.indices || !gainDb.isFinite()) return null
        levels[index] = GraphicEqualizer.levelMbOf(gainDb)
        return settings.copy(equalizerBands = GraphicEqualizer.bandsFromLevelsMb(levels))
    }
}
