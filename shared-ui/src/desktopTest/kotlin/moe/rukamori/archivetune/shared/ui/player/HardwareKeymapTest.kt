package moe.rukamori.archivetune.shared.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import moe.rukamori.archivetune.shared.ui.darkThemeFor

class HardwareKeymapTest {
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
