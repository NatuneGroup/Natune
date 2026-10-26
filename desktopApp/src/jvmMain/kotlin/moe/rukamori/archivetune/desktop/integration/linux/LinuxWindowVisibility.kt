package moe.rukamori.archivetune.desktop.integration.linux

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.awt.Window
import javax.swing.Timer

/** A separate X connection observes VisibilityNotify without stealing AWT's events. XWayland works too. */
class LinuxWindowVisibility(window: Window, private val activity: (Boolean, Boolean) -> Unit) : AutoCloseable {
    private interface Xlib : Library {
        fun XOpenDisplay(name: String?): Pointer?
        fun XCloseDisplay(display: Pointer): Int
        fun XSelectInput(display: Pointer, window: NativeLong, mask: NativeLong): Int
        fun XCheckWindowEvent(display: Pointer, window: NativeLong, mask: NativeLong, event: Pointer): Int
        fun XFlush(display: Pointer): Int
    }
    @Structure.FieldOrder("type", "serial", "sendEvent", "display", "window", "state")
    class VisibilityEvent(pointer: Pointer) : Structure(pointer) {
        @JvmField var type = 0
        @JvmField var serial = NativeLong()
        @JvmField var sendEvent = 0
        @JvmField var display: Pointer? = null
        @JvmField var window = NativeLong()
        @JvmField var state = 0
    }
    private val xlib = Native.load("libX11.so.6", Xlib::class.java)
    private val display = checkNotNull(xlib.XOpenDisplay(null)) { "No X11 display; compositor occlusion unavailable" }
    private val id = NativeLong(Native.getComponentID(window))
    private val mask = NativeLong(1L shl 16)
    private val event = Memory(24L * Native.LONG_SIZE)
    private val timer = Timer(250) {
        while (xlib.XCheckWindowEvent(display, id, mask, event) != 0) {
            val visibility = VisibilityEvent(event).apply { read() }
            if (visibility.type == 15) activity(visibility.state == 2, true)
        }
    }
    init { xlib.XSelectInput(display, id, mask); xlib.XFlush(display); timer.start() }
    override fun close() { timer.stop(); xlib.XCloseDisplay(display); event.close() }
}
