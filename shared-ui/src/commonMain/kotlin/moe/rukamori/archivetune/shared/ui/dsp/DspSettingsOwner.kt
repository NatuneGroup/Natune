package moe.rukamori.archivetune.shared.ui.dsp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import moe.rukamori.archivetune.shared.domain.dsp.DspSettings
import moe.rukamori.archivetune.shared.domain.dsp.DspSettingsDocument
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBand
import moe.rukamori.archivetune.shared.domain.dsp.eq.GraphicEqualizer
import moe.rukamori.archivetune.shared.domain.settings.AudioNormalizationKey
import moe.rukamori.archivetune.shared.domain.settings.AudioProcessingEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.DspSettingsDocumentKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.SettingsStore

/**
 * The DSP settings surface of a host that has no native settings screen: the ten-band graphic equalizer and
 * the normalization switch, as one [DspSettings] value stored as the single versioned document.
 *
 * The document is read once. A host that has none migrates the legacy keys it already wrote, publishes that
 * value and writes the document, so from then on there is one value rather than switches that can disagree.
 * The legacy keys stay on disk for older builds. The Playback settings normalization row keeps writing its own
 * key, so that key and the document are kept equal; the document is the authority for both.
 *
 * A store hands its current value to a new collector, so the snapshot the constructor already adopted arrives
 * once more. That replay is not an edit: only a row value that differs from the acknowledged snapshot, and from
 * the value this owner itself is writing into that row, is bridged into the document. The row corrections and
 * the document writes otherwise queue behind each other, newest last, so an older queued correction cannot
 * overwrite a value the user has already moved to.
 *
 * The one Audio processing switch is followed from the same store. While it is off the host's engine receives
 * an all-neutral value, but the document keeps every stored preset untouched, so turning the switch back on
 * restores the equalizer, preamp and normalization exactly as they were.
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
    /** What a host shows: the stored value and the band levels it holds. */
    data class State(
        val settings: DspSettings,
        val levelsMb: List<Int>,
        /** False while the shared Audio processing switch is off; [effective] is neutral and [settings] keep the preset. */
        val processingEnabled: Boolean = true,
    ) {
        val equalizerEnabled: Boolean get() = settings.equalizerEnabled
        val normalizationEnabled: Boolean get() = settings.normalizationEnabled
        val bands: List<EqualizerBand> = GraphicEqualizer.bandsFromLevelsMb(levelsMb)

        /** What the host's engine renders: the stored value, or an all-neutral value while processing is off. */
        val effective: DspSettings
            get() = if (processingEnabled) settings else settings.withoutProcessing()
    }

    /** The stored inputs this owner follows as one value, so a change to any of them is adopted once. */
    private data class Inputs(
        val document: String?,
        val normalizationEnabled: Boolean,
        val processingEnabled: Boolean,
    )

    private var stored = DspSettings()
    private var processingEnabled = true
    private val published = MutableStateFlow(stateOf(stored))

    /** The current settings, read by the equalizer panel. */
    val state: StateFlow<State> = published.asStateFlow()

    /** The document this surface last read or wrote. Its own save publishes this value, so it is not adopted back. */
    private var appliedDocument: String? = null

    /** The document a save has already queued but not committed yet, so its own snapshot is not adopted either. */
    private var pendingDocument: String? = null

    /** The store inputs this owner has consumed, so the collector's replay of the same value is not an edit. */
    private var acknowledged: Inputs? = null

    /**
     * The row values a pending document-to-row correction replaces and writes, so the stale value the store
     * still holds is not bridged back into the document as a user edit.
     */
    private var reconciliation: Reconciliation? = null

    /** Orders the queued saves: every write takes a newer revision, and only the newest revision lands. */
    private var writeRevision = 0L

    private val writeLock = Mutex()

    private class Reconciliation(val stale: Boolean, val target: Boolean)

    init {
        // The stored value applies at once, so the engine matches it before the first track loads.
        adopt(inputsOf(store.snapshots.value), store.snapshots.value)
        scope.launch {
            store.snapshots
                .map(::inputsOf)
                .distinctUntilChanged()
                .collect { adopt(it, store.snapshots.value) }
        }
    }

    /** Turns the equalizer on or off and saves the document. */
    fun setEqualizerEnabled(enabled: Boolean) {
        commit(stored.copy(equalizerEnabled = enabled))
    }

    /** Turns track normalization on or off, which decides whether the chain applies the measured track gain. */
    fun setNormalizationEnabled(enabled: Boolean) {
        commit(stored.copy(normalizationEnabled = enabled))
    }

    /** Applies one band's gain while its handle is dragged. The engine retunes at once and nothing is saved yet. */
    fun previewGain(index: Int, gainDb: Double) {
        val next = withGain(stored, index, gainDb) ?: return
        publish(next)
    }

    /** Keeps the last gain of a drag and saves the document once. */
    fun commitGain(index: Int, gainDb: Double) {
        val next = withGain(stored, index, gainDb) ?: return
        commit(next)
    }

    /** Sets every band back to 0 dB and saves the document. */
    fun resetFlat() {
        commit(stored.copy(equalizerBands = GraphicEqualizer.bandsFromLevelsMb(GraphicEqualizer.flatLevelsMb())))
    }

    private fun inputsOf(snapshot: PreferenceSnapshot?): Inputs = Inputs(
        document = DspSettingsDocument.rawOf(snapshot),
        normalizationEnabled = snapshot?.get(AudioNormalizationKey) ?: NORMALIZATION_DEFAULT,
        processingEnabled = snapshot?.get(AudioProcessingEnabledKey) ?: true,
    )

    private fun adopt(inputs: Inputs, snapshot: PreferenceSnapshot?) {
        // A null snapshot means the initial persisted read has not completed, not a fresh installation.
        if (snapshot == null) return
        val previous = acknowledged
        // A store replays its current value to a new collector; the constructor already adopted that snapshot.
        if (previous == inputs) return
        acknowledged = inputs
        if (previous == null || !owns(inputs.document)) {
            // The first value, or a document another host wrote: the document is the authority for both.
            appliedDocument = inputs.document
            processingEnabled = inputs.processingEnabled
            val next = DspSettingsDocument.of(inputs.document, DspSettingsDocument.legacyOf(snapshot))
            publish(next)
            if (inputs.document == null) {
                // No document yet: write the migrated value once, with the row value it was read from.
                persist(next)
            } else if (next.normalizationEnabled != inputs.normalizationEnabled) {
                // A document another host wrote is the authority, so the Playback settings row follows it.
                mirrorNormalization(next.normalizationEnabled, stale = inputs.normalizationEnabled)
            }
            return
        }
        if (inputs.processingEnabled != processingEnabled) {
            // The switch is a route gate, not a document field: the stored value stays exactly as it was.
            processingEnabled = inputs.processingEnabled
            publish(stored)
        }
        val row = inputs.normalizationEnabled
        val pending = reconciliation
        when {
            // The row either still holds the value the document replaced, or already the correction we wrote.
            pending != null && (row == pending.stale || row == pending.target) -> {
                if (row == pending.target) reconciliation = null
            }
            // The Playback settings row writes the legacy key. Bridge an edit of that row into the one
            // document, so the row and the engine cannot hold two different normalization values.
            row != stored.normalizationEnabled -> commit(stored.copy(normalizationEnabled = row))
        }
    }

    /** True when [document] is this owner's own value, so its snapshot carries no foreign change. */
    private fun owns(document: String?): Boolean =
        document == appliedDocument || document == pendingDocument

    private fun publish(next: DspSettings) {
        stored = next
        val state = stateOf(next)
        published.value = state
        onApplied(state.effective)
    }

    private fun commit(next: DspSettings) {
        publish(next)
        persist(next)
    }

    /**
     * Writes the document, remembering it first so the snapshot this write publishes is not adopted back. The
     * Playback settings row reads [AudioNormalizationKey], so the one document value is written there in the
     * same commit: the row and the engine can never disagree by a write.
     *
     * Writes are serialized and revisioned: a write that has been superseded while it waited is dropped, so a
     * slow queued value cannot land after the user has already moved past it.
     */
    private fun persist(settings: DspSettings) {
        val document = DspSettingsDocument.write(settings)
        pendingDocument = document
        val revision = ++writeRevision
        scope.launch {
            writeLock.lock()
            try {
                // A newer write has already been queued, so this one would restore a value the user moved past.
                if (revision != writeRevision) return@launch
                try {
                    store.edit {
                        set(DspSettingsDocumentKey, document)
                        if (this[AudioNormalizationKey, NORMALIZATION_DEFAULT] != settings.normalizationEnabled) {
                            set(AudioNormalizationKey, settings.normalizationEnabled)
                        }
                    }
                    appliedDocument = document
                    reconciliation = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    // A lost save leaves the applied value in memory, so playback and the panel still agree.
                    onSaveFailure(failure)
                } finally {
                    if (pendingDocument == document) pendingDocument = null
                }
            } finally {
                writeLock.unlock()
            }
        }
    }

    /**
     * Mirrors the document's normalization into the row's key, so the row shows the value the chain applies.
     * The row it replaces is remembered, so the stale value the store still holds is not read as an edit.
     */
    private fun mirrorNormalization(enabled: Boolean, stale: Boolean) {
        reconciliation = Reconciliation(stale = stale, target = enabled)
        val revision = ++writeRevision
        scope.launch {
            writeLock.lock()
            try {
                if (revision != writeRevision) return@launch
                try {
                    store.edit { set(AudioNormalizationKey, enabled) }
                    reconciliation = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    onSaveFailure(failure)
                }
            } finally {
                writeLock.unlock()
            }
        }
    }

    private fun stateOf(settings: DspSettings): State =
        State(settings, GraphicEqualizer.levelsMbOf(settings.equalizerBands), processingEnabled)

    private fun withGain(settings: DspSettings, index: Int, gainDb: Double): DspSettings? {
        val levels = GraphicEqualizer.levelsMbOf(settings.equalizerBands).toMutableList()
        if (index !in levels.indices || !gainDb.isFinite()) return null
        levels[index] = GraphicEqualizer.levelMbOf(gainDb)
        return settings.copy(equalizerBands = GraphicEqualizer.bandsFromLevelsMb(levels))
    }

    private companion object {
        /** The normalization default the Playback settings row declares, so an absent key is not a second opinion. */
        const val NORMALIZATION_DEFAULT = true
    }
}
