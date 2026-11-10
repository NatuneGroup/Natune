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

    @Test fun theManualChoiceReadsBackAsItsOwnClientWithoutGuessingAtUnknownValues() {
        PlaybackClientChoice.entries.forEach { choice ->
            assertEquals(choice, PlaybackClientChoice.of(choice.preference))
            // A saved value is matched as stored words, so case and stray blanks from an older build still name a client.
            assertEquals(choice, PlaybackClientChoice.of(" ${choice.preference.lowercase()} "), choice.name)
        }
        assertNull(PlaybackClientChoice.of(null))
        assertNull(PlaybackClientChoice.of(""))
        assertNull(PlaybackClientChoice.of("HI_RES_LOSSLESS"))

        val editor = PreferenceSnapshot.of(PlayerStreamClientKey to "WEB_REMIX").toEditor()
        assertNull(editor.snapshot().manualPlaybackClientChoice())
        editor[AutoChoosePlaybackClientKey] = false
        assertEquals(PlaybackClientChoice.WEB_REMIX, editor.snapshot().manualPlaybackClientChoice())
        editor[PlayerStreamClientKey] = "HI_RES_LOSSLESS"
        assertNull(editor.snapshot().manualPlaybackClientChoice())
        assertEquals("HI_RES_LOSSLESS", editor[PlayerStreamClientKey])
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
    @Test fun directDriverRequiresItsParentAndPreservesOtherSelections() {
        val editor = PreferenceSnapshot.EMPTY.toEditor()
        editor.setPlaybackOutputPreference(DirectUsbDriverKey, true)
        assertNull(editor[DirectUsbDriverKey])
        editor.setPlaybackOutputPreference(BitPerfectUsbOutputKey, true)
        editor.setPlaybackOutputPreference(DirectUsbDriverKey, true, directUsbDriverAvailable = true)
        assertEquals(true, editor[BitPerfectUsbOutputKey])
        assertEquals(true, editor[DirectUsbDriverKey])
        editor.setPlaybackOutputPreference(DirectUsbDriverKey, false)
        assertEquals(true, editor[BitPerfectUsbOutputKey])
    }

    @Test fun olderPlatformsCanSelectTheDirectDriverBeforeBitPerfectWithoutChangingOtherModes() {
        val editor = PreferenceSnapshot.of(UsbExclusiveAudioKey to true).toEditor()
        editor.setPlaybackOutputPreference(DirectUsbDriverKey, true, false, true)
        assertEquals(true, editor[DirectUsbDriverKey])
        assertNull(editor[BitPerfectUsbOutputKey])
        assertEquals(true, editor[UsbExclusiveAudioKey])
        assertTrue(bitPerfectOutputSupported(false, editor[DirectUsbDriverKey, false], true))
        editor.setPlaybackOutputPreference(BitPerfectUsbOutputKey, true)
        assertTrue(editor.snapshot().bitPerfectOutputSelected())
        assertEquals(false, editor[UsbExclusiveAudioKey])
        editor.setPlaybackOutputPreference(DirectUsbDriverKey, false, false, true)
        assertEquals(true, editor[BitPerfectUsbOutputKey])
        assertFalse(bitPerfectOutputSupported(false, editor[DirectUsbDriverKey, false], true))
    }

    @Test fun directDriverAndParentAvailabilityUsePlatformAndNativeCapabilities() {
        for (platform in listOf(false, true)) {
            for (native in listOf(false, true)) {
                for (parent in listOf(false, true)) {
                    val preferences = PreferenceSnapshot.of(BitPerfectUsbOutputKey to parent)
                    assertEquals(native && (parent || !platform), preferences.directUsbDriverSelectable(platform, native))
                    for (direct in listOf(false, true)) {
                        assertEquals(platform || (direct && native), bitPerfectOutputSupported(platform, direct, native))
                    }
                    val editor = preferences.toEditor()
                    editor.setPlaybackOutputPreference(DirectUsbDriverKey, true, platform, native)
                    assertEquals(if (native && (parent || !platform)) true else null, editor[DirectUsbDriverKey])
                    assertEquals(parent, editor[BitPerfectUsbOutputKey])
                }
            }
        }
        val conflicting = PreferenceSnapshot.of(BitPerfectUsbOutputKey to true, CrossfadeEnabledKey to true)
        assertFalse(conflicting.directUsbDriverSelectable(true, true))
        assertTrue(conflicting.directUsbDriverSelectable(false, true))
    }

}
