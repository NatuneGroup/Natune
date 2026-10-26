package moe.rukamori.archivetune.shared.domain.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The process latch for restart-scoped settings. The first read of the stored snapshot is the startup value for the
 * whole process, so later stored changes never move what is running, they only mark a restart as pending.
 */
class StartupSettingsLatchTest {
    @Test
    fun theStoredSnapshotIsReadOnlyOnce() {
        var reads = 0
        val latch =
            StartupSettingsLatch {
                reads++
                PreferenceSnapshot.of(UiScaleFactorKey to 1.2f)
            }

        assertEquals(1.2f, latch.uiScale)
        assertEquals(1.2f, latch.values.uiScale)
        assertEquals(1.2f, latch.uiScale)
        assertEquals(1, reads)
    }

    @Test
    fun laterStoredChangesDoNotMoveTheRunningValues() {
        var stored = PreferenceSnapshot.of(TabletModeEnabledKey to false)
        val latch = StartupSettingsLatch { stored }
        assertFalse(latch.tabletModeEnabled)

        stored = PreferenceSnapshot.of(TabletModeEnabledKey to true)

        assertFalse(latch.tabletModeEnabled)
        assertTrue(latch.pending(stored).tabletMode)
    }

    @Test
    fun aMissingStartupSnapshotLatchesTheDefaults() {
        val latch = StartupSettingsLatch { null }

        assertEquals(RestartScopedSettings.Defaults, latch.values)
    }

    @Test
    fun pendingComparesStoredValuesAgainstTheLatchedStartup() {
        val latch = StartupSettingsLatch { PreferenceSnapshot.EMPTY }

        assertFalse(latch.pending(PreferenceSnapshot.EMPTY).any)
        assertTrue(latch.pending(PreferenceSnapshot.of(LiquidGlassEnabledKey to true)).liquidGlass)
    }

    @Test
    fun aRevertedValueLeavesNothingPending() {
        val latch = StartupSettingsLatch { PreferenceSnapshot.of(LiquidGlassEnabledKey to true) }

        assertTrue(latch.pending(PreferenceSnapshot.EMPTY).liquidGlass)
        assertFalse(latch.pending(PreferenceSnapshot.of(LiquidGlassEnabledKey to true)).any)
    }
}
