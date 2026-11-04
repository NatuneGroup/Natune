package moe.rukamori.archivetune.shared.domain.settings

import kotlin.test.*

class PlaybackConflictsTest {
    @Test fun automaticClientSelectionDoesNotApplyOrEraseTheDormantManualChoice() {
        val editor = PreferenceSnapshot.of(PlayerStreamClientKey to "ANDROID_VR").toEditor()
        assertNull(editor.snapshot().manualPlaybackClient())
        editor[AutoChoosePlaybackClientKey] = false
        assertEquals("ANDROID_VR", editor.snapshot().manualPlaybackClient())
        editor[AutoChoosePlaybackClientKey] = true
        assertNull(editor.snapshot().manualPlaybackClient())
        assertEquals("ANDROID_VR", editor[PlayerStreamClientKey])
    }

    @Test fun everyOutputChoiceClearsItsExclusionsAtomically() {
        playbackOutputPreferenceKeys.forEach { selected ->
            val editor = PreferenceSnapshot.EMPTY.toEditor()
            playbackOutputPreferenceKeys.forEach { editor[it] = true }
            editor.setPlaybackOutputPreference(selected, true)
            playbackOutputPreferenceKeys.forEach { assertEquals(it == selected, editor[it], "${selected.name}: ${it.name}") }
        }
    }

    @Test fun disablingAnOutputDoesNotTurnOnOrClearAnotherMode() {
        playbackOutputPreferenceKeys.forEach { selected ->
            val editor = PreferenceSnapshot.EMPTY.toEditor()
            playbackOutputPreferenceKeys.forEach { editor[it] = true }
            editor.setPlaybackOutputPreference(selected, false)
            playbackOutputPreferenceKeys.forEach { assertEquals(it != selected, editor[it]) }
        }
    }

    @Test fun rejectionOwnsOffloadButDisablingRejectionDoesNotReenableIt() {
        val editor = PreferenceSnapshot.of(AudioOffload to true).toEditor()
        editor.setAuthenticityRejection(true)
        assertEquals(false, editor[AudioOffload])
        val before = editor.snapshot()
        editor.setPlaybackOutputPreference(AudioOffload, true)
        assertTrue(SettingsChange(before, editor.snapshot()).changedKeys.isEmpty())
        editor.setAuthenticityRejection(false)
        assertEquals(false, editor[AudioOffload])
    }

    @Test fun liveProcessingAndSilenceActionsCannotBypassTheBitPerfectSelection() {
        val editor = PreferenceSnapshot.of(BitPerfectUsbOutputKey to true).toEditor()
        val before = editor.snapshot()
        audioProcessingPreferenceKeys.forEach { editor.setAudioProcessingPreference(true) }
        editor.setPcmProcessingPreference(SkipSilenceKey, true)
        editor.setPcmProcessingPreference(AudioNormalizationKey, true)
        assertTrue(SettingsChange(before, editor.snapshot()).changedKeys.isEmpty())
        editor.setPlaybackOutputPreference(CrossfadeEnabledKey, true)
        editor.setAudioProcessingPreference(true)
        assertEquals(true, editor[AudioProcessingEnabledKey])
    }

    @Test fun processingExcludesOffloadAndLeavesTheRetiredPresetFormatsReadable() {
        val preset = StringPreferenceKey("retained_dsp_preset_fixture")
        // The three retired switches are preset-format input now, so a processing edit must not touch them.
        val editor = PreferenceSnapshot.of(AudioOffload to true, TryptifyAudioProcessingKey to true,
            LastwaveAudioProcessingKey to true, FloatDspEnabledKey to true, preset to "saved-preset").toEditor()
        editor.setAudioProcessingPreference(true)
        assertEquals(true, editor[AudioProcessingEnabledKey])
        assertEquals(false, editor[AudioOffload])
        assertEquals(true, editor[TryptifyAudioProcessingKey])
        assertEquals(true, editor[LastwaveAudioProcessingKey])
        assertEquals(true, editor[FloatDspEnabledKey])
        editor.setPlaybackOutputPreference(AudioOffload, true)
        audioProcessingPreferenceKeys.forEach { assertEquals(false, editor[it]) }
        assertEquals(true, editor[TryptifyAudioProcessingKey])
        assertEquals("saved-preset", editor[preset])
    }

    @Test fun staleOutputCombinationsNormalizeToExistingRuntimePrecedenceAndAreIdempotent() {
        val priority = listOf(AutomixEnabledKey, CrossfadeEnabledKey, BitPerfectUsbOutputKey, UsbExclusiveAudioKey, AudioOffload)
        for (mask in 0 until (1 shl priority.size)) {
            val editor = PreferenceSnapshot.EMPTY.toEditor()
            priority.forEachIndexed { index, key -> editor[key] = (mask and (1 shl index)) != 0 }
            val winner = priority.firstOrNull { editor[it] == true }
            editor.normalizeSettingsConflicts()
            priority.forEach { assertEquals(it == winner, editor[it], "mask=$mask ${it.name}") }
            val before = editor.snapshot()
            editor.normalizeSettingsConflicts()
            assertTrue(SettingsChange(before, editor.snapshot()).changedKeys.isEmpty())
        }
    }

    @Test fun effectiveOffloadChecksOneCompleteConfigurationAndNormalizationNeverTouchesBitPerfect() {
        for (mask in 0 until 64) {
            val flags = (0..5).map { (mask and (1 shl it)) != 0 }
            assertEquals(flags[0] && flags.drop(1).none { it },
                resolveAudioOffloadRequest(flags[0], flags[1], flags[2], flags[3], flags[4], flags[5]))
        }
        assertFalse(audioNormalizationApplies(true, true))
        assertTrue(audioNormalizationApplies(true, false))
        assertFalse(audioNormalizationApplies(false, false))
    }
}
