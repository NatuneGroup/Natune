package moe.rukamori.archivetune.desktop.playback

import kotlin.test.*
import moe.rukamori.archivetune.shared.domain.dsp.DspSettings
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBand
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBandType
import moe.rukamori.archivetune.shared.domain.settings.*

class DesktopExclusiveOutputPolicyTest {
    private val direct = DesktopOutputState(requested = true, exclusiveActive = true, directPcm = true,
        inputRateHz = 96_000, inputBits = 24, inputChannels = 2, inputFormat = DesktopPcmFormat.S24,
        outputRateHz = 96_000, outputBits = 24, outputChannels = 2, outputFormat = DesktopPcmFormat.S24)

    @Test fun requestDefaultsOffAndBothSavedOutputModesRespectTransitionExclusions() {
        assertFalse(desktopExclusiveRequested(PreferenceSnapshot.EMPTY))
        for (key in listOf(BitPerfectUsbOutputKey, UsbExclusiveAudioKey)) {
            assertTrue(desktopExclusiveRequested(PreferenceSnapshot.of(key to true)))
            assertFalse(desktopExclusiveRequested(PreferenceSnapshot.of(key to true, AutomixEnabledKey to true)))
            assertFalse(desktopExclusiveRequested(PreferenceSnapshot.of(key to true, CrossfadeEnabledKey to true)))
        }
    }

    @Test fun exactExclusivePcmBypassesEverySampleAlteringStage() {
        val config = StageConfig(request = PLAIN_REQUEST.copy(normalizationEnabled = true, equalizerEnabled = true,
            speed = 1.5, pitchSemitones = 3.0, silenceSkippingEnabled = true, twoDeckMixingEnabled = true))
        val decision = DesktopExclusiveOutputPolicy.decision(direct)
        assertTrue(decision.verifiedBitPerfect)
        val plan = DesktopExclusiveOutputPolicy.plan(direct, config)
        assertTrue(plan.bypass)
        assertFalse(plan.normalization || plan.equalizer || plan.tempoPitch || plan.resampling ||
            plan.twoDeckMixing || plan.silenceSkipping || plan.softwareVolume)
    }

    @Test fun busyOrConvertedSinkNeverClaimsBitPerfect() {
        for (state in listOf(direct.copy(exclusiveActive = false, reason = "Device busy; shared fallback"),
            direct.copy(outputRateHz = 48_000), direct.copy(outputFormat = DesktopPcmFormat.F32),
            direct.copy(outputBits = 16), direct.copy(outputChannels = 1), direct.copy(directPcm = false))) {
            val decision = DesktopExclusiveOutputPolicy.decision(state)
            assertFalse(decision.bypass)
            assertFalse(decision.verifiedBitPerfect)
        }
    }

    @Test fun pendingUnknownFormatCannotClaimAnActiveSink() {
        assertFalse(DesktopExclusiveOutputPolicy.decision(DesktopOutputState(requested = true)).verifiedBitPerfect)
        assertFalse(DesktopExclusiveOutputPolicy.decision(direct.copy(inputFormat = DesktopPcmFormat.UNKNOWN)).verifiedBitPerfect)
    }

    @Test fun outputMuteWithholdsTheBitPerfectClaim() {
        assertFalse(DesktopExclusiveOutputPolicy.decision(direct.copy(muted = true)).verifiedBitPerfect)
    }

    @Test fun losslessIntegerPaddingCanUseA32BitNativeSlot() {
        assertTrue(DesktopExclusiveOutputPolicy.decision(direct.copy(outputFormat = DesktopPcmFormat.S32, outputBits = 32)).verifiedBitPerfect)
        assertTrue(DesktopExclusiveOutputPolicy.decision(direct.copy(inputFormat = DesktopPcmFormat.S16, inputBits = 16,
            outputFormat = DesktopPcmFormat.S32, outputBits = 32)).verifiedBitPerfect)
        assertFalse(DesktopExclusiveOutputPolicy.decision(direct.copy(inputFormat = DesktopPcmFormat.F32,
            outputFormat = DesktopPcmFormat.S32, outputBits = 32)).verifiedBitPerfect)
    }

    @Test fun sharedModeRestoresTheStoredSoftwareStages() {
        val config = StageConfig(request = PLAIN_REQUEST.copy(speed = 1.5, normalizationEnabled = true))
        val plan = DesktopExclusiveOutputPolicy.plan(direct.copy(requested = false), config)
        assertFalse(plan.bypass)
        assertTrue(plan.tempoPitch)
        assertTrue(plan.normalization)
    }

    @Test fun refusedExclusiveRouteKeepsTheStoredProcessingInItsSharedFallback() {
        val config = StageConfig(request = PLAIN_REQUEST.copy(normalizationEnabled = true, equalizerEnabled = true,
            speed = 1.5, pitchSemitones = 3.0, silenceSkippingEnabled = true, twoDeckMixingEnabled = true))
        val fallback = direct.copy(directPcm = false, exclusiveActive = false, outputRateHz = 48_000,
            reason = "Exclusive output refused")
        val plan = DesktopExclusiveOutputPolicy.plan(fallback, config)
        assertFalse(plan.bypass)
        assertTrue(plan.normalization)
        assertTrue(plan.equalizer)
        // A requested direct route still pins tempo/pitch, mixing and silence skipping; only the sink changed.
        assertFalse(plan.tempoPitch || plan.twoDeckMixing || plan.silenceSkipping)
        assertFalse(DesktopExclusiveOutputPolicy.decision(fallback).verifiedBitPerfect)
    }

    @Test fun onlyAnActiveDirectLaneStripsTheStoredProcessingSettings() {
        val bands = listOf(EqualizerBand(EqualizerBandType.PEAKING, 1_000.0, 4.0))
        val stored = DspSettings(normalizationEnabled = true, equalizerEnabled = true, equalizerBands = bands)
        val directSettings = desktopOutputSettings(direct, stored, bands, 3.0, null)
        assertFalse(directSettings.equalizerEnabled)
        assertTrue(directSettings.equalizerBands.isEmpty())
        assertEquals(1f, directSettings.linearGain)
        val fallbackSettings = desktopOutputSettings(direct.copy(directPcm = false), stored, bands, 3.0, null)
        assertTrue(fallbackSettings.equalizerEnabled)
        assertEquals(bands, fallbackSettings.equalizerBands)
        assertTrue(fallbackSettings.linearGain > 1f)
    }
}
