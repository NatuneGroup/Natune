package moe.rukamori.archivetune.shared.domain.settings

val playbackOutputPreferenceKeys = setOf(CrossfadeEnabledKey, AutomixEnabledKey, AudioOffload, BitPerfectUsbOutputKey, UsbExclusiveAudioKey)
val audioProcessingPreferenceKeys = setOf(AudioProcessingEnabledKey)

/** Baseline RestartPreferenceActions: output choices and their exclusions commit together. */
fun PreferenceEditor.setPlaybackOutputPreference(
    key: BooleanPreferenceKey,
    enabled: Boolean,
    bitPerfectPlatformSupported: Boolean = true,
    directUsbDriverAvailable: Boolean = false,
) {
    require(key in playbackOutputPreferenceKeys || key == DirectUsbDriverKey)
    // Also guard a stale dialog/search action. Never silently turn off the user's rejection policy.
    if (key == AudioOffload && enabled && this[RejectSuspectedUpscaledAudioKey, false]) return
    if (key == DirectUsbDriverKey && enabled &&
        !snapshot().directUsbDriverSelectable(bitPerfectPlatformSupported, directUsbDriverAvailable)
    ) return
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
            this[AudioProcessingEnabledKey] = false
        }
        BitPerfectUsbOutputKey, UsbExclusiveAudioKey -> {
            this[AudioOffload] = false
            this[CrossfadeEnabledKey] = false
            this[AutomixEnabledKey] = false
            this[if (key == BitPerfectUsbOutputKey) UsbExclusiveAudioKey else BitPerfectUsbOutputKey] = false
        }
    }
}

/**
 * The single audio-processing switch shares one atomic owner with the direct rows and search. Turning it
 * on excludes offload, because an offloaded stream decodes in hardware and nothing can process it in
 * software; the preset formats the retired engines stored are untouched either way.
 */
fun PreferenceEditor.setAudioProcessingPreference(enabled: Boolean) {
    if (enabled && snapshot().bitPerfectOutputSelected()) return
    this[AudioProcessingEnabledKey] = enabled
    if (enabled) this[AudioOffload] = false
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

fun bitPerfectOutputSupported(platformSupported: Boolean, directPreference: Boolean, directDriverAvailable: Boolean): Boolean =
    platformSupported || (directPreference && directDriverAvailable)

fun PreferenceSnapshot.directUsbDriverSelectable(platformSupported: Boolean, directDriverAvailable: Boolean): Boolean =
    directDriverAvailable && (bitPerfectOutputSelected() || !platformSupported)

/** The caller additionally checks the actual active native sink, after persistence succeeds. */
fun SettingsChange.needsBitPerfectSinkRelease(): Boolean =
    after[BitPerfectUsbOutputKey] != true &&
        (BitPerfectUsbOutputKey in changedKeys ||
            listOf(CrossfadeEnabledKey, AutomixEnabledKey, AudioOffload, UsbExclusiveAudioKey)
                .any { it in changedKeys && after[it] == true })
