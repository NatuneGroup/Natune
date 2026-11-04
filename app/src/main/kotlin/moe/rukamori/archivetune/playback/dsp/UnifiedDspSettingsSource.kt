package moe.rukamori.archivetune.playback.dsp

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import moe.rukamori.archivetune.constants.AudioNormalizationKey
import moe.rukamori.archivetune.constants.EqualizerBandLevelsMbKey
import moe.rukamori.archivetune.constants.EqualizerEnabledKey
import moe.rukamori.archivetune.constants.EqualizerOutputGainMbKey
import moe.rukamori.archivetune.constants.FloatDspEnabledKey
import moe.rukamori.archivetune.constants.PlaybackPitchKey
import moe.rukamori.archivetune.constants.PlaybackSpeedKey
import moe.rukamori.archivetune.constants.TryptifyAudioProcessingKey
import moe.rukamori.archivetune.shared.domain.dsp.DspSettings
import moe.rukamori.archivetune.shared.domain.dsp.DspSettingsMapper
import moe.rukamori.archivetune.shared.domain.dsp.PersistedDspValues
import moe.rukamori.archivetune.shared.domain.dsp.bitperfect.SoftwareStagePlan

/** One settings value with the route plan it runs under, published whole so a reader never sees half a change. */
data class UnifiedDspRequest(val settings: DspSettings, val plan: SoftwareStagePlan)

/**
 * The one place Android reads DSP preferences into the shared chain.
 *
 * Every stored engine format is read through [DspSettingsMapper], so the unified chain picks up whatever
 * the Tryptify, LastWave and float-DSP switches left on disk without a migration: the parametric and
 * graphic band JSON, the legacy fifteen gains, the shared millibel levels and preamp, the clarity preset,
 * normalization, and the dynamics/dither trio the float chain owned. The old engine switches no longer
 * select an engine — the shared chain is the only one — but their stored keys stay read, because they are
 * where those presets live.
 *
 * Processors are attached weakly and receive each new snapshot directly, so publishing a settings change
 * touches no disk and takes no lock on the playback thread.
 */
class UnifiedDspSettingsSource(
    private val readValues: () -> PersistedDspValues,
    private val bitPerfectActive: () -> Boolean,
) {
    private val processors = CopyOnWriteArrayList<WeakReference<UnifiedDspAudioProcessor>>()

    /** The settings and plan a new processor starts with, and what every attached one last received. */
    @Volatile
    var current: UnifiedDspRequest = build()
        private set

    fun attach(processor: UnifiedDspAudioProcessor) {
        processors.removeAll { it.get() == null }
        processors.add(WeakReference(processor))
        processor.submit(current.settings, current.plan)
    }

    /** Re-reads the stored values. Cheap enough to run on every preference emission. */
    fun refresh() {
        val next = build()
        current = next
        processors.removeAll { it.get() == null }
        for (reference in processors) {
            reference.get()?.submit(next.settings, next.plan)
        }
    }

    /** True while the chain would change samples: the bit-perfect gate needs to know before it claims a route. */
    fun isProcessing(): Boolean = !current.plan.bypass && !current.settings.isNeutral

    private fun build(): UnifiedDspRequest {
        val settings = DspSettingsMapper.fromPersisted(readValues(), BAND_CENTRES_HZ)
        return UnifiedDspRequest(settings, if (bitPerfectActive()) BIT_PERFECT_PLAN else STANDARD_PLAN)
    }

    companion object {
        /**
         * The centres the millibel reader resamples onto. The stored levels are ordered low to high, so
         * mapping them by index onto the same fifteen ISO centres the legacy reader uses keeps the shape a
         * device equalizer wrote without knowing that device's own band centres here.
         */
        val BAND_CENTRES_HZ: List<Double> = DspSettingsMapper.LAST_WAVE_BAND_FREQUENCIES_HZ

        /** LastWave's stored equalizer keys, kept readable: the unified chain reads them, nothing writes them anew. */
        private val LastWaveEqualizerEnabledKey = booleanPreferencesKey("lw_eq_enabled")
        private val LastWaveGainsKey = stringPreferencesKey("lw_eq_gains")

        /** LastWave's clarity switch and preset index. */
        private val LastWaveClarityEnabledKey = booleanPreferencesKey("lw_music_enhancer")
        private val LastWaveClarityPresetKey = intPreferencesKey("lw_clarity_preset")

        /** Tryptify's stored band graphs, read for the presets they hold, not for its bus routing. */
        private val TryptifyGraphicBandsKey = stringPreferencesKey("eq_bands_json")
        private val TryptifyParametricBandsKey = stringPreferencesKey("param_eq_bands_json")

        /**
         * A bit-perfect route allows no stage: the graph copies bit for bit and clears every history, and
         * the adapter never asks for an altering stage while the gate holds the bypass.
         */
        private val BIT_PERFECT_PLAN = SoftwareStagePlan(
            bypass = true,
            normalization = false,
            equalizer = false,
            tempoPitch = false,
            resampling = false,
            twoDeckMixing = false,
            silenceSkipping = false,
            softwareVolume = false,
        )

        /**
         * The standard route allows tone and normalization; the settings still decide whether they run.
         *
         * Tempo, pitch, resampling and silence skipping stay out: ExoPlayer's PlaybackParameters and
         * SonicAudioProcessor already own them on this route, and running the graph's stretch stage as well
         * would apply the user's speed twice. Crossfade and automix gain rides the graph's own transition
         * ramp instead of a mixer, so two-deck mixing is not requested here either.
         */
        private val STANDARD_PLAN = SoftwareStagePlan(
            bypass = false,
            normalization = true,
            equalizer = true,
            tempoPitch = false,
            resampling = false,
            twoDeckMixing = false,
            silenceSkipping = false,
            softwareVolume = false,
        )

        /**
         * The stored DSP values, read from one preferences snapshot. A null snapshot reads as defaults, so a
         * cold process can publish neutral settings instead of waiting on the store.
         */
        fun readPersistedDspValues(preferences: Preferences?): PersistedDspValues =
            if (preferences == null) {
                PersistedDspValues()
            } else {
                PersistedDspValues(
                    audioNormalization = preferences[AudioNormalizationKey] == true,
                    equalizerEnabled = preferences[EqualizerEnabledKey] == true,
                    equalizerBandLevelsMb = preferences[EqualizerBandLevelsMbKey],
                    equalizerOutputGainMb = preferences[EqualizerOutputGainMbKey] ?: 0,
                    lastWaveEqualizerEnabled = preferences[LastWaveEqualizerEnabledKey] == true,
                    lastWaveGainsDb = preferences[LastWaveGainsKey],
                    tryptifyEqualizerEnabled = preferences[TryptifyAudioProcessingKey] == true,
                    tryptifyGraphicBandsJson = preferences[TryptifyGraphicBandsKey],
                    tryptifyParametricBandsJson = preferences[TryptifyParametricBandsKey],
                    clarityEnabled = preferences[LastWaveClarityEnabledKey] == true,
                    clarityPresetIndex = preferences[LastWaveClarityPresetKey] ?: 0,
                    floatDspEnabled = preferences[FloatDspEnabledKey] == true,
                    playbackSpeed = preferences[PlaybackSpeedKey] ?: 1f,
                    playbackPitch = preferences[PlaybackPitchKey] ?: 1f,
                )
            }
    }
}
