package moe.rukamori.archivetune.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The frame this launch draws, and the preference mirror the next launch reads. */
class DesktopWindowFramePolicyTest {
    @Test
    fun windowsAndX11DrawTheirOwnCaptionByDefault() {
        assertEquals(DesktopFrameMode.Custom, DesktopWindowFramePolicy.resolve("Windows 11", wayland = false, null))
        assertEquals(DesktopFrameMode.Custom, DesktopWindowFramePolicy.resolve("Linux", wayland = false, null))
    }

    @Test
    fun macosAndWaylandAlwaysKeepTheSystemFrame() {
        assertEquals(DesktopFrameMode.System, DesktopWindowFramePolicy.resolve("Mac OS X", wayland = false, null))
        assertEquals(DesktopFrameMode.System, DesktopWindowFramePolicy.resolve("Linux", wayland = true, false))
        assertEquals(DesktopFrameMode.System, DesktopWindowFramePolicy.resolve("FreeBSD", wayland = false, false))
    }

    @Test
    fun theStoredChoiceWinsWhereTheAppCanDrawTheFrame() {
        assertEquals(DesktopFrameMode.System, DesktopWindowFramePolicy.resolve("Windows 10", false, true))
        assertEquals(DesktopFrameMode.Custom, DesktopWindowFramePolicy.resolve("Windows 10", false, false))
        assertEquals(DesktopFrameMode.System, DesktopWindowFramePolicy.resolve("Linux", false, true))
    }

    @Test
    fun onlyAnX11CustomCaptionNeedsAnUndecoratedWindow() {
        assertTrue(DesktopWindowFramePolicy.requiresUndecorated(DesktopFrameMode.Custom, "Linux"))
        assertFalse(DesktopWindowFramePolicy.requiresUndecorated(DesktopFrameMode.Custom, "Windows 11"))
        assertFalse(DesktopWindowFramePolicy.requiresUndecorated(DesktopFrameMode.System, "Linux"))
    }

    @Test
    fun waylandIsDetectedFromTheEnvironmentOrTheSessionType() {
        assertTrue(DesktopWindowFramePolicy.isWayland(mapOf("WAYLAND_DISPLAY" to "wayland-0"), null))
        assertTrue(DesktopWindowFramePolicy.isWayland(emptyMap(), "wayland"))
        assertFalse(DesktopWindowFramePolicy.isWayland(emptyMap(), "x11"))
        assertFalse(DesktopWindowFramePolicy.isWayland(mapOf("WAYLAND_DISPLAY" to ""), null))
    }

    @Test
    fun theChoiceIsMirroredForTheNextLaunch() {
        val directory = Files.createTempDirectory("natune-frame")
        try {
            assertNull(DesktopWindowFramePolicy.read(directory), "a first launch has no stored choice")
            DesktopWindowFramePolicy.write(directory, true)
            assertEquals(true, DesktopWindowFramePolicy.read(directory))
            DesktopWindowFramePolicy.write(directory, false)
            assertEquals(false, DesktopWindowFramePolicy.read(directory))
            assertTrue(Files.notExists(directory.resolve("window-frame.properties.tmp")))
        } finally {
            Files.deleteIfExists(directory.resolve("window-frame.properties"))
            Files.deleteIfExists(directory.resolve("window-frame.properties.tmp"))
            Files.deleteIfExists(directory)
        }
    }
}
