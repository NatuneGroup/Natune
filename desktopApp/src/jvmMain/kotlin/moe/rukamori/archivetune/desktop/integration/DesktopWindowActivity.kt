/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.desktop.integration

import java.awt.Frame
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class DesktopWindowActivity(val visible: Boolean, val minimized: Boolean, val occluded: Boolean, val resumed: Boolean) {
    val animate: Boolean get() = visible && !minimized && !occluded && resumed
}

fun shouldCloseToTray(preferTray: Boolean, trayAvailable: Boolean): Boolean = preferTray && trayAvailable

/** AWT owns show/iconify; native window adapters supply occlusion/session suspension. Audio is untouched. */
class DesktopWindowActivityObserver(private val window: Window) : AutoCloseable {
    private val state = MutableStateFlow(DesktopWindowActivity(window.isShowing, false, false, true))
    val activity: StateFlow<DesktopWindowActivity> = state
    private val component = object : ComponentAdapter() {
        override fun componentShown(e: ComponentEvent) = refresh()
        override fun componentHidden(e: ComponentEvent) = refresh()
    }
    private val events = object : WindowAdapter() {
        override fun windowIconified(e: WindowEvent) = refresh()
        override fun windowDeiconified(e: WindowEvent) = refresh()
        override fun windowStateChanged(e: WindowEvent) = refresh()
    }
    init { window.addComponentListener(component); window.addWindowListener(events); window.addWindowStateListener(events) }
    @Synchronized fun nativeActivity(occluded: Boolean, resumed: Boolean) {
        state.value = state.value.copy(occluded = occluded, resumed = resumed)
    }
    @Synchronized private fun refresh() {
        state.value = state.value.copy(
            visible = window.isShowing,
            minimized = window is Frame && window.extendedState and Frame.ICONIFIED != 0,
        )
    }
    override fun close() {
        window.removeComponentListener(component); window.removeWindowListener(events); window.removeWindowStateListener(events)
        state.value = state.value.copy(visible = false)
    }
}
