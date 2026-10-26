package moe.rukamori.archivetune.shared.ui.glass

import kotlin.test.*
import moe.rukamori.archivetune.shared.domain.settings.*

class BlurPolicyTest {
    @Test fun everyEligibilityInputIsRequired() {
        for (master in listOf(false, true)) for (blur in listOf(false, true)) {
            for (source in listOf(false, true)) for (visible in listOf(false, true)) {
                assertEquals(master && blur && source && visible, liquidGlassAllowed(master, blur, source, visible))
            }
        }
    }
    @Test fun lensClampsToGeometry() {
        assertEquals(0f, liquidGlassRefractionHeight(-1f, 20f))
        assertEquals(0f, liquidGlassRefractionHeight(10f, -1f))
        assertEquals(2f, liquidGlassRefractionHeight(9f, 4f))
        assertEquals(3f, liquidGlassRefractionHeight(3f, 100f))
    }
    @Test fun sourceRecordingStopsForOcclusionAndResumesWithoutPreferenceMutation() {
        val prefs = PreferenceSnapshot.of(LiquidGlassEnabledKey to true, NavigationBarFrostedBlurKey to true)
        val caps = OpticalCapabilities(blur = true, refraction = true)
        assertTrue(opticalPolicy(prefs, caps).recordNavigation)
        assertFalse(opticalPolicy(prefs, caps, playerExpanded = true).recordNavigation)
        assertFalse(opticalPolicy(prefs, caps, lyricsExpanded = true).recordLiquidGlass)
        assertFalse(opticalPolicy(prefs, caps, resumed = false).recordLiquidGlass)
        assertTrue(opticalPolicy(prefs, caps).recordLiquidGlass)
    }
    @Test fun liveDisableBlurOverridesAllThreePathsWithoutClearingSelections() {
        val prefs = PreferenceSnapshot.of(LiquidGlassEnabledKey to true, NavigationBarFrostedBlurKey to true)
        val off = prefs.toEditor().apply { this[DisableBlurKey] = true }.snapshot()
        val policy = opticalPolicy(off, OpticalCapabilities(true, true))
        assertFalse(policy.recordLiquidGlass); assertFalse(policy.recordNavigation); assertFalse(policy.artworkBlur)
        assertTrue(off[LiquidGlassEnabledKey, false])
    }
    @Test fun reducedMotionDoesNotDisableStaticGlass() {
        val policy = opticalPolicy(PreferenceSnapshot.of(LiquidGlassEnabledKey to true, DisableAnimationsKey to true),
            OpticalCapabilities(true, true))
        assertTrue(policy.recordLiquidGlass); assertFalse(policy.motion)
    }
    @Test fun unsupportedCapabilitiesCannotBeReenabledByStoredSelections() {
        val policy = opticalPolicy(PreferenceSnapshot.of(LiquidGlassEnabledKey to true), OpticalCapabilities(false, false))
        assertFalse(policy.recordLiquidGlass); assertFalse(policy.artworkBlur)
    }
}
