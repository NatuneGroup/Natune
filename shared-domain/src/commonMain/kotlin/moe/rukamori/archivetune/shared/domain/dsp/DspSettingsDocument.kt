package moe.rukamori.archivetune.shared.domain.dsp

import moe.rukamori.archivetune.shared.domain.dsp.eq.GraphicEqualizer
import moe.rukamori.archivetune.shared.domain.settings.AudioNormalizationKey
import moe.rukamori.archivetune.shared.domain.settings.DspSettingsDocumentKey
import moe.rukamori.archivetune.shared.domain.settings.EqualizerBandLevelsMbKey
import moe.rukamori.archivetune.shared.domain.settings.EqualizerEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.EqualizerOutputGainEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.EqualizerOutputGainMbKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot

/**
 * One stored DSP document: a [DspSettings] value under [DspSettingsDocumentKey], in place of the per-engine
 * keys a host wrote before the unified chain. The document is read first and rewritten as one value; the keys
 * it replaces stay readable and untouched, so a build that predates it keeps reading them.
 *
 * A host therefore reads one value, not a set of switches that can disagree with each other, and the equalizer,
 * normalization and preamp it shows are the ones the chain applies.
 */
object DspSettingsDocument {
    /** The document [raw] holds. Null means this host has not written one yet and its legacy keys must be read. */
    fun read(raw: String?): DspSettings? = if (raw.isNullOrBlank()) null else DspSettingsCodec.decode(raw)

    /** The value to use: the stored document, or the legacy keys migrated once when there is none. */
    fun of(
        raw: String?,
        legacy: PersistedDspValues,
        bandCentresHz: List<Double> = GraphicEqualizer.BAND_CENTRES_HZ,
    ): DspSettings = read(raw) ?: DspSettingsMapper.fromPersisted(legacy, bandCentresHz)

    /** The stored form of [settings]. */
    fun write(settings: DspSettings): String = DspSettingsCodec.encode(settings)

    /** The document in [snapshot], when the initial read has completed. */
    fun rawOf(snapshot: PreferenceSnapshot?): String? = snapshot?.get(DspSettingsDocumentKey)

    /**
     * What a host stored before the document existed, read from the shared keys the equalizer panel already
     * wrote: the switch, the millibel levels, the normalization switch and the user preamp while its own
     * switch is on. A host that never wrote them reads the neutral value, which keeps today's behaviour.
     */
    fun legacyOf(snapshot: PreferenceSnapshot?): PersistedDspValues = PersistedDspValues(
        audioNormalization = snapshot?.get(AudioNormalizationKey) ?: false,
        equalizerEnabled = snapshot?.get(EqualizerEnabledKey) ?: false,
        equalizerBandLevelsMb = snapshot?.get(EqualizerBandLevelsMbKey),
        equalizerOutputGainMb = if (snapshot?.get(EqualizerOutputGainEnabledKey) == true) {
            snapshot.get(EqualizerOutputGainMbKey) ?: 0
        } else {
            0
        },
    )
}
