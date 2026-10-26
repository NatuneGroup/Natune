/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.domain.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Characterizes the one post-commit restart prompt. Only a committed change that differs from what is running prompts,
 * a later dismissal never replays for the same value, and a restart is consumed once.
 */
class SettingsRestartPromptPolicyTest {
    private val running = restartSettingValues(PreferenceSnapshot.EMPTY, bitPerfectSupported = true)

    private fun changed(reason: RestartReason, value: String) = running + (reason to value)

    @Test
    fun initialAndNoOpCommitDoNotPrompt() {
        val policy = RestartPromptPolicy()
        assertFalse(policy.visible)

        policy.committed(running, running, running)

        assertFalse(policy.visible)
        assertTrue(policy.reasons.isEmpty())
    }

    @Test
    fun laterHandlesTheExactValueAndReentryDoesNotReplayIt() {
        val policy = RestartPromptPolicy()
        val first = changed(RestartReason.UI_SCALE, "1.1")

        policy.committed(running, first, running)
        assertTrue(policy.visible)

        policy.later()
        assertFalse(policy.visible)

        policy.committed(first, first, running)
        assertFalse(policy.visible)
        assertEquals(setOf(RestartReason.UI_SCALE), policy.reasons)

        policy.committed(first, changed(RestartReason.UI_SCALE, "1.2"), running)
        assertTrue(policy.visible)
    }

    @Test
    fun reasonsMergeAndRevertRemovingOnlyTheObsoleteReason() {
        val policy = RestartPromptPolicy()
        val scale = changed(RestartReason.UI_SCALE, "1.1")
        val both = scale + (RestartReason.GLASS to "true")

        policy.committed(running, scale, running)
        policy.committed(scale, both, running)
        assertEquals(setOf(RestartReason.UI_SCALE, RestartReason.GLASS), policy.reasons)

        policy.later()
        policy.committed(both, changed(RestartReason.GLASS, "true"), running)
        assertFalse(policy.visible)
        assertEquals(setOf(RestartReason.GLASS), policy.reasons)

        policy.committed(changed(RestartReason.GLASS, "true"), running, running)
        assertTrue(policy.reasons.isEmpty())

        policy.committed(running, scale, running)
        assertTrue(policy.visible)
    }

    @Test
    fun confirmWhileBusyDoesNotConsumeAndADoubleConfirmConsumesOnce() {
        val policy = RestartPromptPolicy()
        policy.committed(running, changed(RestartReason.TABLET_MODE, "true"), running)

        assertFalse(policy.consumeRestart(busy = true))
        assertTrue(policy.visible)

        var restarts = 0
        repeat(2) { if (policy.consumeRestart(busy = false)) restarts++ }

        assertEquals(1, restarts)
        assertFalse(policy.visible)
    }

    @Test
    fun laterNeverConfirmsAndStorageIdentityPromptsOnlyForANewOperation() {
        val policy = RestartPromptPolicy()

        policy.storageCommitted("migration-a")
        assertTrue(policy.visible)

        policy.later()
        assertFalse(policy.consumeRestart(busy = false))

        policy.storageCommitted("migration-a")
        assertFalse(policy.visible)

        policy.storageCommitted("migration-b")
        assertTrue(policy.visible)
        assertEquals(setOf(RestartReason.STORAGE), policy.reasons)
    }

    @Test
    fun normalizedFontMigrationClampedScaleAndUnusedUriAreNoOps() {
        val legacy =
            PreferenceSnapshot.of(
                UseSystemFontKey to true,
                UiScaleFactorKey to 9f,
                CustomFontUriKey to "old",
            )
        val normalized =
            legacy.toEditor().apply {
                this[FontPreferenceKey] = AppFontPreference.SYSTEM.name
                this[UiScaleFactorKey] = RestartScopedSettings.UI_SCALE_MAX
                this[CustomFontUriKey] = "unused-new-uri"
            }.snapshot()

        assertEquals(restartSettingValues(legacy, true), restartSettingValues(normalized, true))

        val policy = RestartPromptPolicy()
        policy.committed(restartSettingValues(legacy, true), restartSettingValues(normalized, true), running)
        assertFalse(policy.visible)
    }

    @Test
    fun customFontIdentityUsesUriAndSelectionButNotTheDisplayName() {
        val custom = PreferenceSnapshot.EMPTY.toEditor().apply { setCustomFont("content://font/a", "a.ttf") }.snapshot()
        val renamed = custom.toEditor().apply { setCustomFont("content://font/a", "renamed.ttf") }.snapshot()
        val replaced = custom.toEditor().apply { setCustomFont("content://font/b", "a.ttf") }.snapshot()

        assertEquals(restartSettingValues(custom, true), restartSettingValues(renamed, true))
        assertNotEquals(
            restartSettingValues(custom, true)[RestartReason.FONT],
            restartSettingValues(replaced, true)[RestartReason.FONT],
        )
    }

    @Test
    fun usbUsesTheSupportedEffectiveChoiceAndTheActualRunningSinkFacts() {
        val enabled = PreferenceSnapshot.of(BitPerfectUsbOutputKey to true)
        assertEquals("false|false", restartSettingValues(enabled, false)[RestartReason.USB_OUTPUT])
        assertEquals("true|false", restartSettingValues(enabled, true)[RestartReason.USB_OUTPUT])

        val conflicts = enabled.toEditor().apply { this[CrossfadeEnabledKey] = true }.snapshot()
        assertEquals("false|false", restartSettingValues(conflicts, true)[RestartReason.USB_OUTPUT])

        val exclusive = PreferenceSnapshot.of(UsbExclusiveAudioKey to true)
        assertEquals("false|true", restartSettingValues(exclusive, true)[RestartReason.USB_OUTPUT])

        val policy = RestartPromptPolicy()
        // The sink may have started after the activity. Disabling it differs from that real sink.
        val actualRunning = running + (RestartReason.USB_OUTPUT to "true|false")
        policy.committed(restartSettingValues(enabled, true), restartSettingValues(conflicts, true), actualRunning)
        assertEquals(setOf(RestartReason.USB_OUTPUT), policy.reasons)
        assertTrue(policy.visible)

        policy.committed(restartSettingValues(conflicts, true), restartSettingValues(enabled, true), actualRunning)
        assertFalse(policy.visible)
    }

    @Test
    fun regionChangesPromptWithTheirOwnReason() {
        val policy = RestartPromptPolicy()
        val region = PreferenceSnapshot.of(YouTubeMusicRegionKey to "JP")

        policy.committed(running, restartSettingValues(region, true), running)

        assertEquals(setOf(RestartReason.REGION), policy.reasons)
        assertTrue(policy.visible)
    }
}
