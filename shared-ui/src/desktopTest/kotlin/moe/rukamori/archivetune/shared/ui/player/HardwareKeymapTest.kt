package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import moe.rukamori.archivetune.shared.ui.darkThemeFor

class HardwareKeymapTest {
    @Test fun transportKeysResolve() {
        assertEquals(KeymapAction.TogglePlayback, keymapActionOf(Key.Spacebar, ctrlOrMeta = false, alt = false, shift = false))
        assertEquals(KeymapAction.SeekBack, keymapActionOf(Key.DirectionLeft, false, false, false))
        assertEquals(KeymapAction.SeekForward, keymapActionOf(Key.DirectionRight, false, false, false))
        assertEquals(KeymapAction.VolumeUp, keymapActionOf(Key.DirectionUp, false, false, false))
        assertEquals(KeymapAction.VolumeDown, keymapActionOf(Key.DirectionDown, false, false, false))
        assertEquals(KeymapAction.VolumeUp, keymapActionOf(Key.Equals, false, false, false))
        assertEquals(KeymapAction.VolumeUp, keymapActionOf(Key.Plus, false, false, false))
        // '+' types as Shift+'=' on common layouts.
        assertEquals(KeymapAction.VolumeUp, keymapActionOf(Key.Equals, false, false, shift = true))
        assertEquals(KeymapAction.VolumeDown, keymapActionOf(Key.Minus, false, false, false))
        assertEquals(KeymapAction.VolumeDown, keymapActionOf(Key.NumPadSubtract, false, false, false))
        assertEquals(KeymapAction.Mute, keymapActionOf(Key.M, false, false, false))
        assertEquals(KeymapAction.Shuffle, keymapActionOf(Key.S, false, false, false))
        assertEquals(KeymapAction.Repeat, keymapActionOf(Key.R, false, false, false))
        assertEquals(KeymapAction.Next, keymapActionOf(Key.N, false, false, shift = true))
        assertEquals(KeymapAction.Previous, keymapActionOf(Key.P, false, false, shift = true))
        assertEquals(KeymapAction.Back, keymapActionOf(Key.Escape, false, false, false))
        assertEquals(KeymapAction.ExpandPlayer, keymapActionOf(Key.T, false, false, false))
        assertEquals(KeymapAction.FullscreenLyrics, keymapActionOf(Key.L, false, false, false))
        assertEquals(KeymapAction.ToggleFullscreen, keymapActionOf(Key.F, false, false, false))
    }

    @Test fun modifiersAndDigitsResolve() {
        assertEquals(KeymapAction.Search, keymapActionOf(Key.K, ctrlOrMeta = true, alt = false, shift = false))
        assertEquals(KeymapAction.Search, keymapActionOf(Key.F, ctrlOrMeta = true, alt = false, shift = false))
        // Alt stays reserved for the host's own bindings (Alt+Left is the window's back gesture).
        assertNull(keymapActionOf(Key.DirectionLeft, false, alt = true, shift = false))
        // Ctrl/Shift/Alt on an ordinary binding never fires a transport action.
        assertNull(keymapActionOf(Key.S, ctrlOrMeta = true, alt = false, shift = false))
        assertNull(keymapActionOf(Key.S, false, false, shift = true))
        assertNull(keymapActionOf(Key.N, false, false, false))
        assertNull(keymapActionOf(Key.Spacebar, true, false, false))
        assertNull(keymapActionOf(Key.A, false, false, false))
        // Digits seek to tenths, on the main row and the numpad.
        assertEquals(KeymapAction.SeekFraction(0.5f), keymapActionOf(Key.Five, false, false, false))
        assertEquals(KeymapAction.SeekFraction(0f), keymapActionOf(Key.Zero, false, false, false))
        assertEquals(KeymapAction.SeekFraction(0.9f), keymapActionOf(Key.NumPad9, false, false, false))
    }

    @Test fun fractionSeekTargetsTenthsInsideTheDuration() {
        assertEquals(5000L, fractionSeekTargetMs(10_000L, 0.5f))
        assertEquals(0L, fractionSeekTargetMs(10_000L, 0f))
        assertEquals(10_000L, fractionSeekTargetMs(10_000L, 1.2f))
        assertNull(fractionSeekTargetMs(0L, 0.5f))
        assertNull(fractionSeekTargetMs(10_000L, Float.NaN))
    }

    @Test fun muteRemembersAndRestoresTheLevel() {
        assertEquals(MuteTarget(0f, 0.4f), muteVolumeTarget(0.4f, null))
        assertEquals(MuteTarget(0.4f, null), muteVolumeTarget(0f, 0.4f))
        assertEquals(MuteTarget(1f, null), muteVolumeTarget(0f, null))
        assertEquals(MuteTarget(0f, 1f), muteVolumeTarget(1.5f, null))
    }

    @Test fun storedDarkModeResolvesOverThePlatformReading() {
        assertEquals(true, darkThemeFor("ON", systemDark = false))
        assertEquals(false, darkThemeFor("OFF", systemDark = true))
        assertEquals(true, darkThemeFor("AUTO", systemDark = true))
        assertEquals(false, darkThemeFor("AUTO", systemDark = false))
        assertEquals(true, darkThemeFor(null, systemDark = true))
        assertEquals(false, darkThemeFor("anything", systemDark = false))
    }
}
