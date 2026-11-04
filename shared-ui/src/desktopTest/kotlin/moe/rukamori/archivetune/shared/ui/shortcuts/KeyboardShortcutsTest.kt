package moe.rukamori.archivetune.shared.ui.shortcuts

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.ui.player.KeymapAction

class KeyboardShortcutsTest {
    private fun action(
        key: Key,
        ctrl: Boolean = false,
        meta: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
        platform: ShortcutPlatform = ShortcutPlatform.WINDOWS_LINUX,
        typing: Boolean = false,
    ) = KeyboardShortcuts.actionFor(key, ctrl, meta, alt, shift, platform, typing)

    @Test fun transportKeysAnswer() {
        assertEquals(KeymapAction.TogglePlayback, action(Key.Spacebar))
        assertEquals(KeymapAction.SeekBack, action(Key.DirectionLeft))
        assertEquals(KeymapAction.SeekForward, action(Key.DirectionRight))
        assertEquals(KeymapAction.VolumeUp, action(Key.DirectionUp))
        assertEquals(KeymapAction.VolumeDown, action(Key.DirectionDown))
        assertEquals(KeymapAction.VolumeUp, action(Key.Equals))
        assertEquals(KeymapAction.VolumeUp, action(Key.Plus))
        // '+' types as Shift+'=' on common layouts.
        assertEquals(KeymapAction.VolumeUp, action(Key.Equals, shift = true))
        assertEquals(KeymapAction.VolumeDown, action(Key.Minus))
        assertEquals(KeymapAction.VolumeDown, action(Key.NumPadSubtract))
        assertEquals(KeymapAction.Mute, action(Key.M))
        assertEquals(KeymapAction.Shuffle, action(Key.S))
        assertEquals(KeymapAction.Repeat, action(Key.R))
        assertEquals(KeymapAction.Back, action(Key.Escape))
        assertEquals(KeymapAction.FullscreenLyrics, action(Key.L))
        assertEquals(KeymapAction.TogglePlayer, action(Key.T))
        assertEquals(KeymapAction.ToggleFullscreen, action(Key.F))
    }

    @Test fun digitsSeekToTheirTenth() {
        assertEquals(KeymapAction.SeekFraction(0.5f), action(Key.Five))
        assertEquals(KeymapAction.SeekFraction(0f), action(Key.Zero))
        assertEquals(KeymapAction.SeekFraction(0.9f), action(Key.NumPad9))
    }

    @Test fun thePrimaryModifierFollowsThePlatform() {
        // Ctrl is the primary modifier on Windows, Linux and Android; Cmd is the primary modifier on Apple hardware.
        assertEquals(KeymapAction.Search, action(Key.K, ctrl = true))
        assertEquals(KeymapAction.Search, action(Key.K, meta = true, platform = ShortcutPlatform.MAC))
        // The other platform's primary key is not a shortcut modifier here, so neither fires.
        assertNull(action(Key.K, meta = true))
        assertNull(action(Key.K, ctrl = true, platform = ShortcutPlatform.MAC))
    }

    @Test fun spotifyNavigationChordsAnswer() {
        assertEquals(KeymapAction.Library, action(Key.Zero, alt = true, shift = true))
        assertEquals(KeymapAction.Playlists, action(Key.One, alt = true, shift = true))
        assertEquals(KeymapAction.Podcasts, action(Key.Two, alt = true, shift = true))
        assertEquals(KeymapAction.Home, action(Key.H, alt = true, shift = true))
        assertEquals(KeymapAction.NewReleases, action(Key.N, alt = true, shift = true))
        assertEquals(KeymapAction.Charts, action(Key.C, alt = true, shift = true))
        assertEquals(KeymapAction.Queue, action(Key.Q, alt = true, shift = true))
        assertEquals(KeymapAction.NowPlaying, action(Key.J, alt = true, shift = true))
        assertEquals(KeymapAction.TogglePlayer, action(Key.R, alt = true, shift = true))
        assertEquals(KeymapAction.Like, action(Key.B, alt = true, shift = true))
        assertEquals(KeymapAction.Settings, action(Key.Comma, ctrl = true))
        assertEquals(KeymapAction.Help, action(Key.Slash, ctrl = true))
        assertEquals(KeymapAction.Help, action(Key.Slash, ctrl = true, shift = true))
        assertEquals(KeymapAction.Search, action(Key.L, ctrl = true))
        assertEquals(KeymapAction.Search, action(Key.L, ctrl = true, shift = true))
    }

    @Test fun aliasesReachTheSameAction() {
        assertEquals(KeymapAction.Shuffle, action(Key.S, alt = true))
        assertEquals(KeymapAction.Shuffle, action(Key.S, ctrl = true))
        assertEquals(KeymapAction.Repeat, action(Key.R, alt = true))
        assertEquals(KeymapAction.Repeat, action(Key.R, ctrl = true))
        assertEquals(KeymapAction.Next, action(Key.N, shift = true))
        assertEquals(KeymapAction.Next, action(Key.DirectionRight, ctrl = true))
        assertEquals(KeymapAction.Previous, action(Key.P, shift = true))
        assertEquals(KeymapAction.Previous, action(Key.DirectionLeft, ctrl = true))
        assertEquals(KeymapAction.VolumeUp, action(Key.DirectionUp, ctrl = true))
        assertEquals(KeymapAction.VolumeDown, action(Key.DirectionDown, ctrl = true))
    }

    @Test fun modifiersMustMatchExactly() {
        assertNull(action(Key.Spacebar, ctrl = true))
        assertNull(action(Key.Spacebar, alt = true))
        assertNull(action(Key.M, shift = true))
        assertNull(action(Key.M, alt = true))
        assertNull(action(Key.K, ctrl = true, alt = true))
        // Shift+N skips; New Releases needs the Alt too, so Alt+N alone binds nothing.
        assertEquals(KeymapAction.NewReleases, action(Key.N, shift = true, alt = true))
        assertNull(action(Key.N, alt = true))
        assertNull(action(Key.S, alt = true, shift = true))
        assertNull(action(Key.A))
    }

    @Test fun aFocusedTextFieldSilencesUnmodifiedChords() {
        // Typing a space, a letter or an arrow must reach the field, not the player.
        assertNull(action(Key.Spacebar, typing = true))
        assertNull(action(Key.M, typing = true))
        assertNull(action(Key.S, typing = true))
        assertNull(action(Key.R, typing = true))
        assertNull(action(Key.L, typing = true))
        assertNull(action(Key.F, typing = true))
        assertNull(action(Key.DirectionLeft, typing = true))
        assertNull(action(Key.DirectionUp, typing = true))
        assertNull(action(Key.Five, typing = true))
        assertNull(action(Key.N, shift = true, typing = true))
    }

    @Test fun aFocusedTextFieldKeepsEscapeAndEveryModifierChord() {
        assertEquals(KeymapAction.Back, action(Key.Escape, typing = true))
        assertEquals(KeymapAction.Search, action(Key.K, ctrl = true, typing = true))
        assertEquals(KeymapAction.Help, action(Key.Slash, ctrl = true, typing = true))
        assertEquals(KeymapAction.Settings, action(Key.Comma, ctrl = true, typing = true))
        assertEquals(KeymapAction.Queue, action(Key.Q, alt = true, shift = true, typing = true))
        assertEquals(KeymapAction.Like, action(Key.B, alt = true, shift = true, typing = true))
        assertEquals(KeymapAction.Shuffle, action(Key.S, ctrl = true, typing = true))
    }

    @Test fun noTwoChordsClaimTheSamePress() {
        for (platform in ShortcutPlatform.entries) {
            val seen = mutableMapOf<String, KeymapAction>()
            for (entry in KeyboardShortcuts.entries) {
                for (chord in entry.chords) {
                    val resolved = chord.displayText(platform)
                    val previous = seen.put(resolved, entry.action)
                    assertNull(previous, "two shortcuts claim $resolved on $platform")
                }
            }
        }
    }

    @Test fun everyListedEntryCanBePressed() {
        assertTrue(KeyboardShortcuts.listed.isNotEmpty())
        for (entry in KeyboardShortcuts.listed) {
            assertTrue(entry.chords.isNotEmpty(), "a listed shortcut needs a chord")
        }
        // Every group carries at least one row, so no header renders empty.
        for (group in ShortcutGroup.entries) {
            assertTrue(KeyboardShortcuts.listed.any { it.group == group }, "empty group $group")
        }
    }

    @Test fun displayTextFollowsThePlatform() {
        val search = ShortcutChord(Key.K, "K", primary = true)
        assertEquals("⌘K", search.displayText(ShortcutPlatform.MAC))
        assertEquals("Ctrl + K", search.displayText(ShortcutPlatform.WINDOWS_LINUX))
        assertEquals("⌥⇧Q", ShortcutChord(Key.Q, "Q", alt = true, shift = true).displayText(ShortcutPlatform.MAC))
        assertEquals("Alt + Shift + Q", ShortcutChord(Key.Q, "Q", alt = true, shift = true).displayText(ShortcutPlatform.WINDOWS_LINUX))
        assertEquals("Esc", ShortcutChord(Key.Escape, "Esc").displayText(ShortcutPlatform.MAC))
    }
}
