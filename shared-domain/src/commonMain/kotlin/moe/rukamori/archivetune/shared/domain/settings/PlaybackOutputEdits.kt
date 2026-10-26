package moe.rukamori.archivetune.shared.domain.settings

/** Baseline RestartPreferenceActions: output choices and their exclusions commit together. */
fun PreferenceEditor.setPlaybackOutputPreference(key: BooleanPreferenceKey, enabled: Boolean) {
    require(key in setOf(CrossfadeEnabledKey, AutomixEnabledKey, AudioOffload, BitPerfectUsbOutputKey, UsbExclusiveAudioKey))
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

/** The caller additionally checks the actual active native sink, after persistence succeeds. */
fun SettingsChange.needsBitPerfectSinkRelease(): Boolean =
    after[BitPerfectUsbOutputKey] != true &&
        (BitPerfectUsbOutputKey in changedKeys ||
            listOf(CrossfadeEnabledKey, AutomixEnabledKey, AudioOffload, UsbExclusiveAudioKey)
                .any { it in changedKeys && after[it] == true })
