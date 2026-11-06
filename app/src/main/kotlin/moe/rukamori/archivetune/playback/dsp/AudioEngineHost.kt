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
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import moe.rukamori.archivetune.constants.AudioProcessingEnabledKey
import moe.rukamori.archivetune.utils.PreferenceStore
import moe.rukamori.archivetune.utils.dataStore

/**
 * Owns the shared DSP chain for every player sink.
 *
 * The unified Media3 processor reads its settings from [unified]; this class only watches the stored
 * preferences and publishes each new value, off the playback thread. The retired Tryptify, LastWave and
 * float-DSP engines built their own processors here and no longer exist; their stored keys stay readable
 * because they are where the chain reads its tone and clarity presets from.
 */
@Singleton
class AudioEngineHost @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appScope: CoroutineScope,
) {
    /** The last preferences this host saw, so a refresh never races the shared snapshot's own collector. */
    @Volatile
    private var lastPreferences: Preferences? = PreferenceStore.snapshot()

    /** One settings source for every sink: the chain reads it, the playback thread never loads a preference. */
    val unified: UnifiedDspSettingsSource =
        UnifiedDspSettingsSource(
            readValues = { UnifiedDspSettingsSource.readPersistedDspValues(lastPreferences) },
            processingEnabled = { lastPreferences?.get(AudioProcessingEnabledKey) != false },
            bitPerfectActive = { BitPerfectRuntime.chainBypassActive },
        )

    init {
        context.applicationContext.dataStore.data
            .onEach { preferences ->
                lastPreferences = preferences
                runCatching { unified.refresh() }
                    .onFailure { Log.w(TAG, "Unified DSP settings refresh failed", it) }
            }
            .launchIn(appScope)
    }

    /** A fresh chain processor for one sink, already following [unified]. */
    fun newUnifiedProcessor(): UnifiedDspAudioProcessor = UnifiedDspAudioProcessor().also(unified::attach)

    /** True while the chain would alter samples, which the bit-perfect gate needs to know. */
    fun unifiedProcessingActive(): Boolean = unified.isProcessing()

    private companion object {
        const val TAG = "AudioEngineHost"
    }
}
