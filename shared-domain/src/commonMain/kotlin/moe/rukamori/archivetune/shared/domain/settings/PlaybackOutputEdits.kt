package moe.rukamori.archivetune.shared.domain.settings

val playbackOutputPreferenceKeys = setOf(CrossfadeEnabledKey, AutomixEnabledKey, AudioOffload, BitPerfectUsbOutputKey, UsbExclusiveAudioKey)
val audioProcessingPreferenceKeys = setOf(TryptifyAudioProcessingKey, LastwaveAudioProcessingKey, FloatDspEnabledKey)

/** Baseline RestartPreferenceActions: output choices and their exclusions commit together. */
fun PreferenceEditor.setPlaybackOutputPreference(key: BooleanPreferenceKey, enabled: Boolean) {
    require(key in playbackOutputPreferenceKeys)
    // Also guard a stale dialog/search action. Never silently turn off the user's rejection policy.
    if (key == AudioOffload && enabled && this[RejectSuspectedUpscaledAudioKey, false]) return
    this[key] = enabled
    if (!enabled) return
    when (key) {
        CrossfadeEnabledKey, AutomixEnabledKey -> {
            this[AudioOffload] = false
            this[if (key == CrossfadeEnabledKey) AutomixEnabledKey else CrossfadeEnabledKey] = false
            this[BitPerfectUsbOutputKey] = false
            this[UsbExclusiveAudioKey] = false
        }
        AudioOffload -> {
            this[SkipSilenceKey] = false
            this[CrossfadeEnabledKey] = false
            this[AutomixEnabledKey] = false
            this[BitPerfectUsbOutputKey] = false
            this[UsbExclusiveAudioKey] = false
            this[TryptifyAudioProcessingKey] = false
            this[LastwaveAudioProcessingKey] = false
            this[FloatDspEnabledKey] = false
        }
        BitPerfectUsbOutputKey, UsbExclusiveAudioKey -> {
            this[AudioOffload] = false
            this[CrossfadeEnabledKey] = false
            this[AutomixEnabledKey] = false
            this[if (key == BitPerfectUsbOutputKey) UsbExclusiveAudioKey else BitPerfectUsbOutputKey] = false
        }
    }
}

/** Live DSP engines share one atomic owner with direct rows and search. Preset contents are untouched. */
fun PreferenceEditor.setAudioProcessingPreference(key: BooleanPreferenceKey, enabled: Boolean) {
    require(key in audioProcessingPreferenceKeys)
    if (enabled && snapshot().bitPerfectOutputSelected()) return
    this[key] = enabled
    if (enabled) {
        this[AudioOffload] = false
        if (key == TryptifyAudioProcessingKey) this[LastwaveAudioProcessingKey] = false
        if (key == LastwaveAudioProcessingKey) this[TryptifyAudioProcessingKey] = false
    }
}

fun PreferenceEditor.setAuthenticityRejection(enabled: Boolean) {
    this[RejectSuspectedUpscaledAudioKey] = enabled
    if (enabled) this[AudioOffload] = false
}

fun PreferenceEditor.setPcmProcessingPreference(key: BooleanPreferenceKey, enabled: Boolean) {
    require(key == SkipSilenceKey || key == AudioNormalizationKey)
    if (enabled && (snapshot().bitPerfectOutputSelected() || key == SkipSilenceKey && this[AudioOffload, false])) return
    this[key] = enabled
}

/** Native latched sink state must be checked in addition to this persisted selection. */
fun PreferenceSnapshot.bitPerfectOutputSelected(): Boolean =
    this[BitPerfectUsbOutputKey, false] && !this[CrossfadeEnabledKey, false] && !this[AutomixEnabledKey, false]

/** The caller additionally checks the actual active native sink, after persistence succeeds. */
fun SettingsChange.needsBitPerfectSinkRelease(): Boolean =
    after[BitPerfectUsbOutputKey] != true &&
        (BitPerfectUsbOutputKey in changedKeys ||
            listOf(CrossfadeEnabledKey, AutomixEnabledKey, AudioOffload, UsbExclusiveAudioKey)
                .any { it in changedKeys && after[it] == true })
