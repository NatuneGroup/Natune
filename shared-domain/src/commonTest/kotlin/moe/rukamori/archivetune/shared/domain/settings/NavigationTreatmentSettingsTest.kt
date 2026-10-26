package moe.rukamori.archivetune.shared.domain.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The navigation bar has one treatment at a time: frosted blur, tinted frosted blur or liquid glass. Choosing one is a
 * single edit that clears the others, while switching one off leaves the others as they were.
 */
class NavigationTreatmentSettingsTest {
    private val treatmentKeys =
        listOf(NavigationBarFrostedBlurKey, NavigationBarTintFrostedBlurKey, LiquidGlassNavBarEnabledKey)

    @Test
    fun theTreatmentKeysAreTheThreeNavigationSwitches() {
        assertEquals(treatmentKeys.toSet(), navigationTreatmentKeys)
    }

    @Test
    fun enablingOneTreatmentClearsTheOthersInOneEdit() {
        for (chosen in treatmentKeys) {
            val editor =
                PreferenceSnapshot
                    .of(
                        NavigationBarFrostedBlurKey to true,
                        NavigationBarTintFrostedBlurKey to true,
                        LiquidGlassNavBarEnabledKey to true,
                    ).toEditor()

            editor.applyNavigationTreatment(chosen, true)

            val after = editor.snapshot()
            for (key in treatmentKeys) {
                assertEquals(key == chosen, after[key], "treatment ${key.name} after choosing ${chosen.name}")
            }
        }
    }

    @Test
    fun disablingOneTreatmentPreservesTheOthers() {
        val editor = PreferenceSnapshot.of(LiquidGlassNavBarEnabledKey to true).toEditor()

        editor.applyNavigationTreatment(LiquidGlassNavBarEnabledKey, false)

        assertFalse(editor[LiquidGlassNavBarEnabledKey] ?: false)
        assertFalse(editor[NavigationBarFrostedBlurKey] ?: false)
        assertFalse(editor[NavigationBarTintFrostedBlurKey] ?: false)

        val frosted = PreferenceSnapshot.of(NavigationBarTintFrostedBlurKey to true).toEditor()
        frosted.applyNavigationTreatment(NavigationBarFrostedBlurKey, false)

        assertTrue(frosted[NavigationBarTintFrostedBlurKey] ?: false)
    }

    @Test
    fun aChosenTreatmentIsTheOnlyOneOn() {
        val editor = PreferenceSnapshot.of(NavigationBarFrostedBlurKey to true).toEditor()

        editor.applyNavigationTreatment(NavigationBarTintFrostedBlurKey, true)

        assertFalse(editor[NavigationBarFrostedBlurKey] ?: false)
        assertTrue(editor[NavigationBarTintFrostedBlurKey] ?: false)
        assertFalse(editor[LiquidGlassNavBarEnabledKey] ?: false)
    }
}
