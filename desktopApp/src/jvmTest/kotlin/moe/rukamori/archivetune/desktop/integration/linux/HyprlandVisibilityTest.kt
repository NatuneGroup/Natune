package moe.rukamori.archivetune.desktop.integration.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

class HyprlandVisibilityTest {
    private val pid = 4242L

    @Test fun ownWindowOnAnActiveWorkspaceReadsVisible() {
        val clients = clients(pid = pid, workspace = 1)
        assertTrue(hyprlandWindowVisible(pid, "Natune", clients, monitors(active = 1)))
    }

    @Test fun windowOnAnUnseenWorkspaceReadsHidden() {
        assertFalse(hyprlandWindowVisible(pid, "Natune", clients(pid = pid, workspace = 2), monitors(active = 1)))
        // A special workspace shown on any monitor keeps the window visible.
        assertTrue(hyprlandWindowVisible(pid, "Natune", clients(pid = pid, workspace = -99), monitors(active = 1, special = -99)))
    }

    @Test fun unmappedHiddenAndMissingCompositorsResolve() {
        assertFalse(hyprlandWindowVisible(pid, "Natune", clients(pid = pid, workspace = 1, mapped = false), monitors(active = 1)))
        assertFalse(hyprlandWindowVisible(pid, "Natune", clients(pid = pid, workspace = 1, hidden = true), monitors(active = 1)))
        // Pinned windows stay visible even on a workspace no monitor shows.
        assertTrue(hyprlandWindowVisible(pid, "Natune", clients(pid = pid, workspace = 2, pinned = true), monitors(active = 1)))
        // A window the compositor has not listed is never reported hidden.
        assertTrue(hyprlandWindowVisible(pid, "Natune", "[]", monitors(active = 1)))
        // Someone else's window matches neither our pid nor our title, so it says nothing about us.
        assertTrue(hyprlandWindowVisible(pid, "Natune", clients(pid = 1, title = "Other", workspace = 2), monitors(active = 1)))
        // A helper's pid carrying our title is our window, so the unseen workspace still hides it.
        assertFalse(hyprlandWindowVisible(pid, "Natune", clients(pid = 1, workspace = 2), monitors(active = 1)))
    }

    @Test fun theTitleSelectsTheWindowWhenThePidDoesNotMatch() {
        val foreign = """[{"pid":9,"title":"Natune","mapped":true,"hidden":false,"pinned":false,"workspace":{"id":2}}]"""
        assertFalse(hyprlandWindowVisible(pid, "Natune", foreign, monitors(active = 1)))
    }

    private fun clients(
        pid: Long = 0, workspace: Int = 1, title: String = "Natune",
        mapped: Boolean = true, hidden: Boolean = false, pinned: Boolean = false,
    ) = """[{"pid":$pid,"title":"$title","mapped":$mapped,"hidden":$hidden,"pinned":$pinned,"workspace":{"id":$workspace}}]"""

    private fun monitors(active: Int, special: Int = -1) =
        """[{"id":0,"activeWorkspace":{"id":$active},"specialWorkspace":{"id":$special}}]"""
}

class PortalColorSchemeTest {
    @Test fun freedesktopColorSchemeUnwrapsVariants() {
        assertEquals(true, colorSchemeDark(Variant(1)))
        assertEquals(true, colorSchemeDark(Variant(UInt32(1L))))
        assertEquals(false, colorSchemeDark(Variant(Variant(2))))
        assertNull(colorSchemeDark(Variant(0)))
        assertNull(colorSchemeDark(Variant("dark")))
        assertNull(colorSchemeDark(null))
    }
}
