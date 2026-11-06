package moe.rukamori.archivetune.desktop.integration.linux

import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.platform.unix.X11
import java.awt.Component
import java.awt.Toolkit
import java.awt.Window
import java.lang.reflect.Method
import moe.rukamori.archivetune.desktop.DesktopDiagnosticLog

/** The two Xlib calls JNA's X11 interface does not declare, needed to hand a drag to the window manager. */
internal interface LinuxWindowMoveX11 : X11 {
    fun XUngrabPointer(display: Display, time: NativeLong): Int
}

/**
 * Moves and resizes the window through the window manager, the way a title bar does on X11.
 *
 * An app-drawn caption cannot drag an AWT window on its own: the compositor owns the move, and `_NET_WM_MOVERESIZE` is
 * the protocol that asks it to start one. The request is sent from the pointer's current root position after releasing
 * the implicit grab, exactly as a toolkit's own title bar does, so the move is the compositor's interactive one (snap,
 * tiling and edge resistance included) rather than a synthetic bounds loop.
 *
 * Wayland has no such protocol; there the app uses the system frame instead (see `DesktopWindowFramePolicy`).
 */
internal object LinuxWindowDrag {
    /** `_NET_WM_MOVERESIZE` directions, from the EWMH specification. */
    object Direction {
        const val TOP_LEFT = 0
        const val TOP = 1
        const val TOP_RIGHT = 2
        const val RIGHT = 3
        const val BOTTOM_RIGHT = 4
        const val BOTTOM = 5
        const val BOTTOM_LEFT = 6
        const val LEFT = 7
        const val MOVE = 8
    }

    private const val CLIENT_MESSAGE = 33
    private const val SUBSTRUCTURE_NOTIFY = 1L shl 19
    private const val SUBSTRUCTURE_REDIRECT = 1L shl 20

    private val x11: LinuxWindowMoveX11 by lazy { Native.load("X11", LinuxWindowMoveX11::class.java) }

    /** The AWT toolkit reports whether this is an X11 session; a Wayland toolkit answers false and keeps the system frame. */
    fun isX11Toolkit(): Boolean = Toolkit.getDefaultToolkit().javaClass.name == "sun.awt.X11.XToolkit"

    fun startMove(window: Window): Boolean = send(window, Direction.MOVE)

    fun startResize(window: Window, direction: Int): Boolean = send(window, direction)

    private fun send(window: Window, direction: Int): Boolean {
        if (!isX11Toolkit()) return false
        return try {
            val toolkit = Toolkit.getDefaultToolkit().javaClass
            // AWT's own lock guards the display connection; the call must not run without it.
            val lock: Method = toolkit.getMethod("awtLock")
            val unlock: Method = toolkit.getMethod("awtUnlock")
            lock.invoke(null)
            try {
                val accessor = Class.forName("sun.awt.AWTAccessor")
                val componentAccessor = Class.forName("sun.awt.AWTAccessor\$ComponentAccessor")
                val getPeer = accessor.getMethod("getComponentAccessor").invoke(null)
                val peer = componentAccessor.getMethod("getPeer", Component::class.java).invoke(getPeer, window) ?: return false
                val windowId = Class.forName("sun.awt.X11.XBaseWindow").getMethod("getWindow").invoke(peer) as Long
                val display = X11.Display()
                display.setPointer(Pointer(toolkit.getMethod("getDisplay").invoke(null) as Long))
                requestMove(display, X11.Window(windowId), direction)
            } finally {
                unlock.invoke(null)
            }
        } catch (failure: Throwable) {
            // A stripped JRE without the X11 internals or a toolkit that renamed them: the caption still drags by
            // asking the compositor on the next click, and the window keeps the system frame option.
            DesktopDiagnosticLog.host("window move: unavailable (${failure.javaClass.simpleName})")
            false
        }
    }

    private fun requestMove(display: X11.Display, window: X11.Window, direction: Int): Boolean {
        val atom = x11.XInternAtom(display, "_NET_WM_MOVERESIZE", true) ?: return false
        val root = x11.XDefaultRootWindow(display)
        val rootX = com.sun.jna.ptr.IntByReference()
        val rootY = com.sun.jna.ptr.IntByReference()
        val ok = x11.XQueryPointer(
            display, root, X11.WindowByReference(), X11.WindowByReference(), rootX, rootY,
            com.sun.jna.ptr.IntByReference(), com.sun.jna.ptr.IntByReference(), com.sun.jna.ptr.IntByReference(),
        )
        if (!ok) return false
        val event = X11.XEvent()
        event.setType(X11.XClientMessageEvent::class.java)
        event.xclient.type = CLIENT_MESSAGE
        event.xclient.display = display
        event.xclient.window = window
        event.xclient.message_type = atom
        event.xclient.format = 32
        event.xclient.data.setType(Array<NativeLong>::class.java)
        event.xclient.data.l = arrayOf(
            NativeLong(rootX.value.toLong()),
            NativeLong(rootY.value.toLong()),
            NativeLong(direction.toLong()),
            NativeLong(1L),
            NativeLong(1L),
        )
        // The pointer's implicit grab from the press would deliver the composite event to us, not to the manager.
        x11.XUngrabPointer(display, NativeLong(0L))
        x11.XUngrabKeyboard(display, NativeLong(0L))
        val sent = x11.XSendEvent(display, root, 0, NativeLong(SUBSTRUCTURE_NOTIFY or SUBSTRUCTURE_REDIRECT), event) != 0
        x11.XFlush(display)
        return sent
    }
}
