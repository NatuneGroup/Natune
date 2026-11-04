package moe.rukamori.archivetune.shared.domain.settings

/**
 * Idempotent normalization at load/restore boundaries, not a new default or a per-frame writer.
 * Stale output choices follow the existing sink/transition precedence. Dormant DSP/EQ presets,
 * normalization, blur materials and style-specific controls are retained.
 */
fun PreferenceEditor.normalizeSettingsConflicts() {
    migrateHistoryDuration()
    if (this[UseSystemFontKey] != null) {
        setFontPreference(RestartScopedSettings.resolveFontPreference(this[FontPreferenceKey], this[UseSystemFontKey]))
    }
    if (this[DynamicThemeKey, true] && this[RandomThemeOnStartupKey] == true) this[RandomThemeOnStartupKey] = false
    // Both retired selectors already resolve to Web Remix; do not offer a second name for it.
    if (this[PlayerStreamClientKey] in setOf("ARCHIVETUNE_EXTRACTOR", "HI_RES_LOSSLESS"))
        this[PlayerStreamClientKey] = "WEB_REMIX"
    snapshot().selectedNavigationTreatment()?.let { selected ->
        navigationTreatmentKeys.filter { it != selected && this[it] == true }.forEach { this[it] = false }
    }
    when {
        this[AutomixEnabledKey] == true -> clearEnabled(CrossfadeEnabledKey, BitPerfectUsbOutputKey, UsbExclusiveAudioKey, AudioOffload)
        this[CrossfadeEnabledKey] == true -> clearEnabled(BitPerfectUsbOutputKey, UsbExclusiveAudioKey, AudioOffload)
        this[BitPerfectUsbOutputKey] == true -> clearEnabled(UsbExclusiveAudioKey, AudioOffload)
        this[UsbExclusiveAudioKey] == true -> clearEnabled(AudioOffload)
    }
    // Rejection already requires decoded PCM at runtime, so a restored offload flag cannot win.
    if (this[RejectSuspectedUpscaledAudioKey] == true) clearEnabled(AudioOffload)
    if (this[AudioOffload] == true) clearEnabled(SkipSilenceKey, AudioProcessingEnabledKey)
    if (this[LyricsBackgroundStyleKey] == "CUSTOM" && this[PlayerBackgroundStyleKey] != "CUSTOM")
        this[LyricsBackgroundStyleKey] = "DEFAULT"
    if (!snapshot().smartTrimmerAvailable()) clearEnabled(SmartTrimmerKey)
}

private fun PreferenceEditor.clearEnabled(vararg keys: BooleanPreferenceKey) {
    keys.filter { this[it] == true }.forEach { this[it] = false }
}
