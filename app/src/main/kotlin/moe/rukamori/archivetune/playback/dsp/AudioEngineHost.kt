/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 * Portions © 4nx3b — github.com/4nx3b/ArchiveTune (GPL-3.0)
 */

@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package moe.rukamori.archivetune.playback.dsp

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import com.lastwave.app.data.local.EqualizerPreferences
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.playback.NativeAudioEngine
import com.lastwave.app.playback.NativePcmAudioProcessor
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import moe.rukamori.archivetune.utils.PreferenceStore
import moe.rukamori.archivetune.utils.dataStore
import tf.monochrome.android.audio.dsp.ChannelDetectorProcessor
import tf.monochrome.android.audio.dsp.DownmixProcessor
import tf.monochrome.android.audio.dsp.DspEngineManager
import tf.monochrome.android.audio.dsp.MixBusProcessor
import tf.monochrome.android.audio.dsp.crossfeed.CrossfeedEffect
import tf.monochrome.android.audio.dsp.oxford.CompressorEffect
import tf.monochrome.android.audio.dsp.oxford.InflatorEffect
import tf.monochrome.android.audio.eq.AutoEqProcessor
import tf.monochrome.android.audio.eq.ParametricEqProcessor
import tf.monochrome.android.audio.eq.SpectrumAnalyzerTap
import tf.monochrome.android.audio.eq.SystemAudioEqController
import tf.monochrome.android.audio.usb.AudioProcessorChain
import tf.monochrome.android.audio.usb.ToFloatPcmAudioProcessor
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.performance.PerformanceProfile

/**
 * Owns the shared DSP chain for every player sink.
 *
 * The unified Media3 processor reads its settings from [unified]; this class only watches the stored
 * preferences and publishes each new value, off the playback thread. The legacy Tryptify and LastWave
 * switches no longer select or build an engine — the shared chain is the only one — and their stored
 * keys stay read because they name the preset formats to prefer.
 *
 * A chain carries filter and limiter state, so only one sink may run one at a time. A crossfade keeps
 * two players alive; the one that holds the lease keeps the chain and the other plays through the
 * stock path until the lease is released (on its next track boundary, or when its player is released).
 */
@Singleton
class AudioEngineHost @Inject constructor(
    @ApplicationContext private val context: Context,
    private val channelDetector: ChannelDetectorProcessor,
    private val appScope: CoroutineScope,
) {
    private class TryptifyStack(
        val chain: AudioProcessorChain,
        val controller: TryptifyEngineController,
    )

    private val lease = AtomicReference<Any?>(null)

    /** Bumped on every preference change, as the legacy router used to re-route mid-track. */
    @Volatile
    var settingsGeneration: Int = 0
        private set

    /** The last preferences this host saw, so a refresh never races the shared snapshot's own collector. */
    @Volatile
    private var lastPreferences: Preferences? = PreferenceStore.snapshot()

    /** One settings source for every sink: the chain reads it, the playback thread never loads a preference. */
    val unified: UnifiedDspSettingsSource =
        UnifiedDspSettingsSource(
            readValues = { UnifiedDspSettingsSource.readPersistedDspValues(lastPreferences) },
            bitPerfectActive = { BitPerfectRuntime.chainBypassActive },
        )

    @Volatile
    private var tryptifyStackBuilt = false

    @Volatile
    private var lastwaveBuilt = false

    private val tryptifyStack: TryptifyStack? by lazy {
        runCatching {
            if (!AudioEngineRouterProcessor.tryptifyNativeAvailable()) return@runCatching null
            tf.monochrome.android.audio.eq.FrequencyTargets.init(context)
            val preferences = PreferencesManager(context)
            val compressor = CompressorEffect()
            val mixBus = MixBusProcessor(InflatorEffect(), compressor, CrossfeedEffect())
            val autoEq = AutoEqProcessor()
            val paramEq = ParametricEqProcessor()
            val downmix = DownmixProcessor()
            val controller =
                TryptifyEngineController(
                    scope = appScope,
                    mixBus = mixBus,
                    autoEq = autoEq,
                    paramEq = paramEq,
                    preferences = preferences,
                    dspManager = DspEngineManager(mixBus, preferences),
                    systemEq = SystemAudioEqController(context, preferences),
                )
            controller.start()
            preferences.multichannelDownmixEnabled
                .distinctUntilChanged()
                .onEach { downmix.setEnabled(it) }
                .launchIn(appScope)
            val chain =
                AudioProcessorChain(
                    listOf(
                        ToFloatPcmAudioProcessor(),
                        channelDetector,
                        downmix,
                        mixBus,
                        autoEq,
                        paramEq,
                        SpectrumAnalyzerTap(PerformanceProfile.HIGH),
                    ),
                )
            EngineRuntime.publishCompressorEffect(compressor)
            TryptifyStack(chain, controller)
        }.onFailure { Log.w(TAG, "Tryptify engine unavailable", it) }
            .getOrNull()
            .also { tryptifyStackBuilt = true }
    }

    private val lastwaveProcessor: NativePcmAudioProcessor? by lazy {
        runCatching {
            val dataStore = context.applicationContext.dataStore
            val engine =
                NativeAudioEngine(SettingsPreferences(dataStore), EqualizerPreferences(dataStore), appScope)
            if (engine.isAvailable) NativePcmAudioProcessor(engine) else null
        }.onFailure { Log.w(TAG, "LastWave engine unavailable", it) }
            .getOrNull()
            .also { lastwaveBuilt = true }
    }

    init {
        context.applicationContext.dataStore.data
            .onEach { preferences ->
                lastPreferences = preferences
                runCatching { unified.refresh() }
                    .onFailure { Log.w(TAG, "Unified DSP settings refresh failed", it) }
                runCatching { publishUnifiedEngine() }
                    .onFailure { Log.w(TAG, "Audio engine switch handling failed", it) }
            }
            .launchIn(appScope)
    }

    /**
     * No legacy engine is selected, built or gated any more: the shared chain is the only one, and the
     * stored switches only choose which preset format to prefer. An engine name would misreport that, so
     * nothing is reported as wanted or available here.
     */
    private fun publishUnifiedEngine() {
        EngineRuntime.publishWantedEngine(AudioEngineRouterProcessor.Engine.NONE)
        EngineRuntime.publishEngineAvailability(tryptify = false, lastwave = false)
        settingsGeneration++
    }

    /** Read on the playback thread from the in-memory snapshot, never from disk. */
    fun wantedEngine(): AudioEngineRouterProcessor.Engine =
        when {
            PreferenceStore.get(TryptifyAudioProcessingKey) == true ->
                AudioEngineRouterProcessor.Engine.TRYPTIFY
            PreferenceStore.get(LastwaveAudioProcessingKey) == true ->
                AudioEngineRouterProcessor.Engine.LASTWAVE
            else -> AudioEngineRouterProcessor.Engine.NONE
        }

    fun floatDspWanted(): Boolean = PreferenceStore.get(FloatDspEnabledKey) == true

    internal fun tryptifyChain(): AudioProcessorChain? = tryptifyStack?.chain

    internal fun builtTryptifyChain(): AudioProcessorChain? =
        if (tryptifyStackBuilt) tryptifyStack?.chain else null

    internal fun lastwave(): NativePcmAudioProcessor? = lastwaveProcessor

    internal fun builtLastwave(): NativePcmAudioProcessor? = if (lastwaveBuilt) lastwaveProcessor else null

    fun acquire(owner: Any): Boolean = lease.compareAndSet(null, owner) || lease.get() === owner

    fun release(owner: Any) {
        lease.compareAndSet(owner, null)
    }

    fun owns(owner: Any): Boolean = lease.get() === owner

    /** Diagnostics follow the sink that holds the engines, or any sink while none does. */
    fun mayPublish(owner: Any): Boolean = lease.get().let { it == null || it === owner }

    /** A fresh chain processor for one sink, already following [unified]. */
    fun newUnifiedProcessor(): UnifiedDspAudioProcessor = UnifiedDspAudioProcessor().also(unified::attach)

    /** True while the chain would alter samples, which the bit-perfect gate needs to know. */
    fun unifiedProcessingActive(): Boolean = unified.isProcessing()

    /** The legacy router factory, kept only for the engine classes it still references. */
    fun newRouter(): AudioEngineRouterProcessor = AudioEngineRouterProcessor(this, FloatDspProcessor())

    private companion object {
        const val TAG = "AudioEngineHost"
    }
}
