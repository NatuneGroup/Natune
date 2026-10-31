package moe.rukamori.archivetune.shared.domain.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IndependentAppleMusicSettingsTest {
    private val surfaceEdits: List<PreferenceEditor.(Boolean) -> Unit> = listOf(
        { this[PlayerDesignStyleKey] = if (it) "APPLE_MUSIC" else "V4" },
        { setLibraryStyle(if (it) LibraryStyle.APPLE_MUSIC else LibraryStyle.DEFAULT) },
        { this[NavigationBarStyleKey] = if (it) "APPLE_MUSIC" else "DEFAULT" },
        { this[AppleMusicHeadersKey] = it },
        { this[AppleMusicMenusKey] = it },
        { this[AppleMusicSearchBarKey] = it },
        { this[AppleMusicPaletteKey] = it },
    )

    private fun selections(snapshot: PreferenceSnapshot) = listOf(
        snapshot.selectedPlayerDesignStyle() == PlayerDesignStyle.APPLE_MUSIC,
        snapshot.libraryStyle() == LibraryStyle.APPLE_MUSIC,
        snapshot.selectedNavigationBarStyle() == NavigationBarStyle.APPLE_MUSIC,
        snapshot.appleMusicHeaders(), snapshot.appleMusicMenus(), snapshot.appleMusicSearchBar(), snapshot.appleMusicPalette(),
    )

    @Test fun eachIndividualOptionChangesOnlyItsOwnSurfaceFromMaterial() {
        surfaceEdits.forEachIndexed { index, edit ->
            val editor = PreferenceSnapshot.of(AppleMusicMenusKey to false).toEditor()
            edit(editor, true)
            editor.migrateAppleMusicSettings()
            assertEquals(List(7) { it == index }, selections(editor.snapshot()), "surface $index")
            assertEquals(InterfaceStyle.CUSTOM, editor.snapshot().interfaceStyle())
            assertNull(editor[AppleMusicExperienceKey])
        }
    }

    @Test fun eachIndividualOptionCanLeaveThePresetWithoutChangingTheOtherSurfaces() {
        surfaceEdits.forEachIndexed { index, edit ->
            val editor = PreferenceSnapshot.EMPTY.toEditor()
            editor.applyInterfaceStyle(InterfaceStyle.APPLE_MUSIC)
            edit(editor, false)
            editor.migrateAppleMusicSettings()
            assertEquals(List(7) { it != index }, selections(editor.snapshot()), "surface $index")
            assertEquals(InterfaceStyle.CUSTOM, editor.snapshot().interfaceStyle())
            assertTrue(editor[AppleMusicExperienceKey] == true)
        }
    }

    @Test fun legacyMigrationPreservesTheVisibleBarAndIndependentLibraryAndPlayerChoices() {
        val editor = PreferenceSnapshot.of(
            AppleMusicExperienceKey to true,
            NavigationBarStyleKey to "FLOATING",
            PlayerDesignStyleKey to "TIKTOK",
            LibraryStyleKey to "DEFAULT",
        ).toEditor()
        editor.migrateAppleMusicSettings()
        assertEquals("APPLE_MUSIC", editor[NavigationBarStyleKey])
        assertEquals("FLOATING", editor[NavigationStyleBeforeAppleMusicKey])
        assertEquals("TIKTOK", editor[PlayerDesignStyleKey])
        assertEquals("DEFAULT", editor[LibraryStyleKey])
        assertTrue(editor.snapshot().appleMusicHeaders())
        editor[NavigationBarStyleKey] = "SUKISU"
        editor[AppleMusicHeadersKey] = false
        val afterManualEdit = editor.snapshot()
        editor.migrateAppleMusicSettings()
        assertTrue(SettingsChange(afterManualEdit, editor.snapshot()).changedKeys.isEmpty())
        assertFalse(editor.snapshot().appleMusicHeaders())
    }

    @Test fun presetRestoresThePreviousBarButNeverOverwritesANewerManualBar() {
        val editor = PreferenceSnapshot.of(NavigationBarStyleKey to "SUKISU").toEditor()
        editor.applyInterfaceStyle(InterfaceStyle.APPLE_MUSIC)
        editor.applyInterfaceStyle(InterfaceStyle.MATERIAL_EXPRESSIVE)
        assertEquals("SUKISU", editor[NavigationBarStyleKey])
        editor.applyInterfaceStyle(InterfaceStyle.APPLE_MUSIC)
        editor[NavigationBarStyleKey] = "FLOATING"
        editor.applyInterfaceStyle(InterfaceStyle.MATERIAL_EXPRESSIVE)
        assertEquals("FLOATING", editor[NavigationBarStyleKey])
    }

    @Test fun customStatusIsNotAPresetActionAndAnimationOptionsNeverActivateOtherStyles() {
        val editor = PreferenceSnapshot.of(AppleMusicAnimatedArtworkKey to false, AppleMusicPauseScaleKey to true).toEditor()
        val before = editor.snapshot()
        editor.applyInterfaceStyle(InterfaceStyle.CUSTOM)
        assertTrue(SettingsChange(before, editor.snapshot()).changedKeys.isEmpty())
        assertEquals(InterfaceStyle.MATERIAL_EXPRESSIVE, editor.snapshot().interfaceStyle())
        editor.applyInterfaceStyle(InterfaceStyle.APPLE_MUSIC)
        editor[AppleMusicBackdropWanderKey] = false
        assertEquals(InterfaceStyle.APPLE_MUSIC, editor.snapshot().interfaceStyle())
        assertFalse(editor[AppleMusicAnimatedArtworkKey, true])
    }

    @Test fun freshInstallRendersAppleMenusButStillReadsAsTheDefaultStyle() {
        assertEquals(PlayerMenuStyle.APPLE_MUSIC, PreferenceSnapshot.EMPTY.playerMenuStyle())
        assertTrue(PreferenceSnapshot.EMPTY.appleMusicMenus())
        assertEquals(InterfaceStyle.MATERIAL_EXPRESSIVE, PreferenceSnapshot.EMPTY.interfaceStyle())
    }

    @Test fun storedLegacyMenusChoiceDecidesTheMenuStyle() {
        assertEquals(PlayerMenuStyle.MD3, PreferenceSnapshot.of(AppleMusicMenusKey to false).playerMenuStyle())
        assertEquals(
            PlayerMenuStyle.APPLE_MUSIC,
            PreferenceSnapshot.of(AppleMusicMenusKey to true, PlayerMenuStyleKey to "MD3").playerMenuStyle(),
        )
    }

    @Test fun choosingAMenuStyleWritesBothKeysAndCountsTowardTheIndicator() {
        val editor = PreferenceSnapshot.EMPTY.toEditor()
        editor.setPlayerMenuStyle(PlayerMenuStyle.MD3)
        assertEquals(PlayerMenuStyle.MD3, editor.snapshot().playerMenuStyle())
        assertEquals(false, editor[AppleMusicMenusKey])
        assertEquals(InterfaceStyle.MATERIAL_EXPRESSIVE, editor.snapshot().interfaceStyle())
        editor.setPlayerMenuStyle(PlayerMenuStyle.APPLE_MUSIC)
        assertEquals(InterfaceStyle.CUSTOM, editor.snapshot().interfaceStyle())
    }
}
