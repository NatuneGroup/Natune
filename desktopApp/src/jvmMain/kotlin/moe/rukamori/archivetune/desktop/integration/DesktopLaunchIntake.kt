package moe.rukamori.archivetune.desktop.integration

import java.awt.Desktop
import java.awt.EventQueue
import java.awt.Frame
import java.awt.Window

object DesktopLaunchIntake {
    val links = DesktopDeepLinks(setOf("archivetune"), setOf("open"))
    @Volatile private var window: Window? = null
    fun receive(uri: String?) {
        if (uri != null) links.accept(uri)
        if (window != null) EventQueue.invokeLater {
            window?.let { it.isVisible = true; if (it is Frame) it.extendedState = Frame.NORMAL; it.toFront() }
        }
    }
    fun attachWindow(value: Window) { window = value }
    fun detachWindow(value: Window) { if (window === value) window = null }
    fun registerAppleEvents() {
        if (!Desktop.isDesktopSupported()) return
        val desktop = Desktop.getDesktop()
        if (desktop.isSupported(Desktop.Action.APP_OPEN_URI)) desktop.setOpenURIHandler { receive(it.uri.toString()) }
        if (desktop.isSupported(Desktop.Action.APP_EVENT_REOPENED)) desktop.addAppEventListener(
            java.awt.desktop.AppReopenedListener { receive(null) },
        )
    }
}
