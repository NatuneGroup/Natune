package moe.rukamori.archivetune.shared.domain.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Characterizes the Apple Music experience as one atomic edit. The experience switch, the library style and the player
 * style move together, and turning it off gives back only the player style the experience displaced, unless a style
 * was picked by hand while it was on.
 */
class AppleMusicExperienceInterfaceStyleTest {
    @Test
    fun materialDefaultLeavesIndependentPresentationChoicesUnchanged() {
        val editor = PreferenceSnapshot.of(PlayerDesignStyleKey to PlayerDesignStyle.SIMPMUSIC.name).toEditor()

        editor.applyInterfaceStyle(InterfaceStyle.MATERIAL_EXPRESSIVE)

        assertNull(editor[AppleMusicExperienceKey])
        assertEquals(PlayerDesignStyle.SIMPMUSIC.name, editor[PlayerDesignStyleKey])
    }

    @Test
    fun appleMusicSetsTheCompleteExperienceFromFreshPreferences() {
        val editor = PreferenceSnapshot.EMPTY.toEditor()

        editor.applyInterfaceStyle(InterfaceStyle.APPLE_MUSIC)

        assertEquals(true, editor[AppleMusicExperienceKey])
        assertEquals(LibraryStyle.APPLE_MUSIC.name, editor[LibraryStyleKey])
        assertEquals(PlayerDesignStyle.APPLE_MUSIC.name, editor[PlayerDesignStyleKey])
        assertEquals(PlayerDesignStyle.Default.name, editor[StyleBeforeAppleMusicKey])
    }

    @Test
    fun reselectingAppleMusicPreservesTheDisplacedPlayerStyle() {
        val editor = PreferenceSnapshot.of(PlayerDesignStyleKey to PlayerDesignStyle.SIMPMUSIC.name).toEditor()

        editor.applyInterfaceStyle(InterfaceStyle.APPLE_MUSIC)
        editor.applyInterfaceStyle(InterfaceStyle.APPLE_MUSIC)
        editor.applyInterfaceStyle(InterfaceStyle.MATERIAL_EXPRESSIVE)

        assertEquals(false, editor[AppleMusicExperienceKey])
        assertEquals(LibraryStyle.DEFAULT.name, editor[LibraryStyleKey])
        assertEquals(PlayerDesignStyle.SIMPMUSIC.name, editor[PlayerDesignStyleKey])
    }

    @Test
    fun disablingAppleMusicKeepsAPlayerStyleChosenMeanwhile() {
        val editor = PreferenceSnapshot.EMPTY.toEditor()
        editor.applyInterfaceStyle(InterfaceStyle.APPLE_MUSIC)
        editor[PlayerDesignStyleKey] = PlayerDesignStyle.TIKTOK.name

        editor.applyInterfaceStyle(InterfaceStyle.MATERIAL_EXPRESSIVE)

        assertEquals(false, editor[AppleMusicExperienceKey])
        assertEquals(PlayerDesignStyle.TIKTOK.name, editor[PlayerDesignStyleKey])
    }

    @Test
    fun invalidSavedPlayerStyleFallsBackToTheV4Player() {
        for (savedStyle in listOf("removed-style", PlayerDesignStyle.APPLE_MUSIC.name)) {
            val editor =
                PreferenceSnapshot
                    .of(
                        AppleMusicExperienceKey to true,
                        PlayerDesignStyleKey to PlayerDesignStyle.APPLE_MUSIC.name,
                        StyleBeforeAppleMusicKey to savedStyle,
                    ).toEditor()

            editor.applyInterfaceStyle(InterfaceStyle.MATERIAL_EXPRESSIVE)

            assertEquals(PlayerDesignStyle.Default.name, editor[PlayerDesignStyleKey], "saved style $savedStyle")
        }
    }

    @Test
    fun roundTripFromTheDefaultPlayerReturnsToV4() {
        val editor = PreferenceSnapshot.EMPTY.toEditor()

        editor.applyInterfaceStyle(InterfaceStyle.APPLE_MUSIC)
        editor.applyInterfaceStyle(InterfaceStyle.MATERIAL_EXPRESSIVE)

        assertEquals(PlayerDesignStyle.V4.name, editor[PlayerDesignStyleKey])
        assertEquals(LibraryStyle.DEFAULT.name, editor[LibraryStyleKey])
        assertEquals(false, editor[AppleMusicExperienceKey])
    }

    @Test
    fun roundTripRestoresEveryNumberedAndNamedPlayerStyle() {
        for (style in PlayerDesignStyle.entries.filterNot { it == PlayerDesignStyle.APPLE_MUSIC }) {
            val editor = PreferenceSnapshot.of(PlayerDesignStyleKey to style.name).toEditor()

            editor.applyInterfaceStyle(InterfaceStyle.APPLE_MUSIC)
            assertEquals(PlayerDesignStyle.APPLE_MUSIC.name, editor[PlayerDesignStyleKey], style.name)

            editor.applyInterfaceStyle(InterfaceStyle.MATERIAL_EXPRESSIVE)
            assertEquals(style.name, editor[PlayerDesignStyleKey], "round trip for ${style.name}")
        }
    }

    @Test
    fun repeatedRoundTripsKeepTheOriginalStyleNotTheAppleMusicPlayer() {
        val editor = PreferenceSnapshot.of(PlayerDesignStyleKey to PlayerDesignStyle.BITCHORD.name).toEditor()

        repeat(3) {
            editor.applyInterfaceStyle(InterfaceStyle.APPLE_MUSIC)
            editor.applyInterfaceStyle(InterfaceStyle.MATERIAL_EXPRESSIVE)
        }

        assertEquals(PlayerDesignStyle.BITCHORD.name, editor[PlayerDesignStyleKey])
        assertEquals(PlayerDesignStyle.BITCHORD.name, editor[StyleBeforeAppleMusicKey])
    }

    @Test
    fun manualStyleWhileOnIsKeptAcrossReselectAndDisable() {
        val editor = PreferenceSnapshot.of(PlayerDesignStyleKey to PlayerDesignStyle.SIMPMUSIC.name).toEditor()
        editor.applyInterfaceStyle(InterfaceStyle.APPLE_MUSIC)
        editor[PlayerDesignStyleKey] = PlayerDesignStyle.LOOPER.name

        editor.applyInterfaceStyle(InterfaceStyle.APPLE_MUSIC)
        assertEquals(PlayerDesignStyle.LOOPER.name, editor[PlayerDesignStyleKey])

        editor.applyInterfaceStyle(InterfaceStyle.MATERIAL_EXPRESSIVE)
        assertEquals(PlayerDesignStyle.LOOPER.name, editor[PlayerDesignStyleKey])
    }

    @Test
    fun interfaceStyleReadsTheExperienceSwitch() {
        assertEquals(InterfaceStyle.MATERIAL_EXPRESSIVE, PreferenceSnapshot.EMPTY.interfaceStyle())
        assertEquals(
            InterfaceStyle.APPLE_MUSIC,
            PreferenceSnapshot.of(AppleMusicExperienceKey to true).interfaceStyle(),
        )
    }

    @Test
    fun libraryStyleSeedsFromTheLegacySwitchOnlyWhenNotStored() {
        assertEquals(LibraryStyle.DEFAULT, PreferenceSnapshot.EMPTY.libraryStyle())
        assertEquals(
            LibraryStyle.APPLE_MUSIC,
            PreferenceSnapshot.of(AppleMusicExperienceKey to true).libraryStyle(),
        )
        assertEquals(
            LibraryStyle.DEFAULT,
            PreferenceSnapshot.of(AppleMusicExperienceKey to true, LibraryStyleKey to LibraryStyle.DEFAULT.name)
                .libraryStyle(),
        )
    }

    @Test
    fun choosingALibraryStyleNeverWritesTheExperienceSwitch() {
        val editor = PreferenceSnapshot.EMPTY.toEditor()

        editor.setLibraryStyle(LibraryStyle.APPLE_MUSIC)

        assertEquals(LibraryStyle.APPLE_MUSIC.name, editor[LibraryStyleKey])
        assertNull(editor[AppleMusicExperienceKey])
        assertNull(editor[PlayerDesignStyleKey])
        assertTrue(editor.snapshot().keys.contains(LibraryStyleKey))
    }
}
