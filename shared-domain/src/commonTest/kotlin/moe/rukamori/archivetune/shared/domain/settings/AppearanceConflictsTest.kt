package moe.rukamori.archivetune.shared.domain.settings

import kotlin.test.*

class AppearanceConflictsTest {
    @Test fun paletteSourcesAreAtomicAndTurningOneOffDoesNotSelectAnother() {
        val editor = PreferenceSnapshot.of(DynamicThemeKey to true, RandomThemeOnStartupKey to true).toEditor()
        editor.setRandomThemeOnStartup(true)
        assertEquals(false, editor[DynamicThemeKey])
        assertEquals(true, editor[RandomThemeOnStartupKey])
        editor.setDynamicTheme(true)
        assertEquals(true, editor[DynamicThemeKey])
        assertEquals(false, editor[RandomThemeOnStartupKey])
        editor.setDynamicTheme(false)
        assertEquals(false, editor[RandomThemeOnStartupKey])
        editor.setCustomThemeColor("fixed-palette")
        assertEquals("fixed-palette", editor[CustomThemeColorKey])
        assertEquals(false, editor[DynamicThemeKey])
        assertEquals(false, editor[RandomThemeOnStartupKey])
    }

    @Test fun deferredStartupRandomizationCannotUndoANewerPaletteOrSource() {
        fun randomEditor() = PreferenceSnapshot.of(DynamicThemeKey to false, RandomThemeOnStartupKey to true).toEditor()
        val allowed = randomEditor().apply { applyStartupRandomTheme(null, "generated") }
        assertEquals("generated", allowed[CustomThemeColorKey])
        val changed = randomEditor().apply {
            setCustomThemeColor("user-choice")
            applyStartupRandomTheme(null, "late-generated")
        }
        assertEquals("user-choice", changed[CustomThemeColorKey])
        val dynamic = randomEditor().apply {
            setDynamicTheme(true)
            applyStartupRandomTheme(null, "late-generated")
        }
        assertNull(dynamic[CustomThemeColorKey])
        val newer = randomEditor().apply {
            this[CustomThemeColorKey] = "newer-random"
            applyStartupRandomTheme(null, "late-generated")
        }
        assertEquals("newer-random", newer[CustomThemeColorKey])
    }

    @Test fun canonicalFontWinsAndLegacyMigrationKeepsTheRunningIdentity() {
        for (font in AppFontPreference.entries) {
            val editor = PreferenceSnapshot.of(UseSystemFontKey to true, CustomFontUriKey to "saved-font").toEditor()
            editor.setFontPreference(font)
            assertEquals(font.name, editor[FontPreferenceKey])
            assertNull(editor[UseSystemFontKey])
            assertEquals("saved-font", editor[CustomFontUriKey])
        }
        for (legacy in listOf(false, true)) {
            for (stored in listOf(null, "CUSTOM", "invalid-font")) {
                val editor = PreferenceSnapshot.of(UseSystemFontKey to legacy).toEditor()
                editor[FontPreferenceKey] = stored
                val running = RestartScopedSettings.from(editor.snapshot())
                editor.normalizeSettingsConflicts()
                assertFalse(PendingRestart.between(running, RestartScopedSettings.from(editor.snapshot())).any)
                assertNull(editor[UseSystemFontKey])
            }
        }
    }

    @Test fun customBackgroundAndLyricsCommitTogetherWithoutTouchingOtherAppearance() {
        val editor = PreferenceSnapshot.of(PlayerDesignStyleKey to "FLAMINGO", LibraryStyleKey to "APPLE_MUSIC").toEditor()
        editor.setPlayerBackgroundStyle("CUSTOM")
        assertEquals("CUSTOM", editor[LyricsBackgroundStyleKey])
        editor.setPlayerBackgroundStyle("GRADIENT")
        assertEquals("DEFAULT", editor[LyricsBackgroundStyleKey])
        editor[LyricsBackgroundStyleKey] = "MOVING_BLUR"
        editor.setPlayerBackgroundStyle("BLUR")
        assertEquals("MOVING_BLUR", editor[LyricsBackgroundStyleKey])
        assertEquals("FLAMINGO", editor[PlayerDesignStyleKey])
        assertEquals("APPLE_MUSIC", editor[LibraryStyleKey])
    }

    @Test fun materialDependenciesNeverChangeTheSavedSelection() {
        for (style in MiniPlayerBackgroundStyle.entries) {
            val needsBlur = style == MiniPlayerBackgroundStyle.FROSTED || style == MiniPlayerBackgroundStyle.LIQUID_GLASS
            assertEquals(if (needsBlur) MiniPlayerMaterialBlock.BLUR_DISABLED else null,
                miniPlayerMaterialBlock(style, true, true, true))
            assertEquals(if (needsBlur) MiniPlayerMaterialBlock.UNSUPPORTED else null,
                miniPlayerMaterialBlock(style, false, false, true))
            assertEquals(if (style == MiniPlayerBackgroundStyle.LIQUID_GLASS) MiniPlayerMaterialBlock.GLASS_DISABLED else null,
                miniPlayerMaterialBlock(style, false, true, false))
            assertNull(miniPlayerMaterialBlock(style, false, true, true))
        }
    }

    @Test fun mergedLegacyRestoreDoesNotInheritTheInstallationsMigrationMarker() {
        val editor = PreferenceSnapshot.of(AppleMusicSettingsMigratedKey to true, AppleMusicExperienceKey to true,
            AppleMusicMenusKey to false, AppleMusicSearchBarKey to false,
            AppleMusicPaletteKey to false, LibraryStyleKey to "DEFAULT", PlayerDesignStyleKey to "FLAMINGO").toEditor()
        editor.normalizeRestoredSettings(setOf(AppleMusicExperienceKey.name))
        assertEquals(true, editor[AppleMusicMenusKey])
        assertEquals(true, editor[AppleMusicSearchBarKey])
        assertEquals(true, editor[AppleMusicPaletteKey])
        assertEquals("APPLE_MUSIC", editor[NavigationBarStyleKey])
        // XML restore remains a merge: an explicitly saved style from this installation is retained.
        assertEquals("FLAMINGO", editor[PlayerDesignStyleKey])
        assertEquals("DEFAULT", editor[LibraryStyleKey])
        editor[AppleMusicSearchBarKey] = false
        val before = editor.snapshot()
        editor.normalizeRestoredSettings(setOf(AppleMusicExperienceKey.name, AppleMusicSettingsMigratedKey.name))
        assertTrue(SettingsChange(before, editor.snapshot()).changedKeys.isEmpty())
    }
}
