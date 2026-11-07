package moe.rukamori.archivetune.desktop.integration.linux

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.NativeLongByReference
import java.awt.Dimension
import java.awt.Window
import javax.swing.Timer

/**
 * The size the X server and the compositor actually hold for this window, read on a second X connection.
 *
 * AWT's X11 peer tracks the window size from `ConfigureNotify` plus its own resize requests. Once the app has asked
 * for a size the compositor ignored — a tiling compositor overrides every request, and a floating one may keep its
 * own float size — the peer can stay at the size the *app* asked for while the X window is another size. Compose then
 * lays the whole UI out for the AWT size, paints only that much, and the rest of the window is never painted: it shows
 * the window background, which is black. Nothing inside AWT can notice, because the divergence is exactly what AWT
 * got wrong, so the geometry is read from the server and handed back to the owner, which re-applies it.
 *
 * A poll is enough and costs nothing: the alternative is subscribing to `ConfigureNotify` here, which would mean
 * reading events from a connection that must not interpret them (the one AWT owns).
 */
internal class LinuxWindowGeometry(
    private val window: Window,
    private val intervalMs: Int = 400,
    private val report: (Dimension) -> Unit,
) : AutoCloseable {
    private interface Xlib : Library {
        fun XOpenDisplay(name: String?): Pointer?
        fun XCloseDisplay(display: Pointer): Int
        fun XGetGeometry(
            display: Pointer,
            drawable: NativeLong,
            root: NativeLongByReference,
            x: IntByReference,
            y: IntByReference,
            width: IntByReference,
            height: IntByReference,
            borderWidth: IntByReference,
            depth: IntByReference,
        ): Int
    }

    private val xlib = Native.load("libX11.so.6", Xlib::class.java)
    private val display = checkNotNull(xlib.XOpenDisplay(null)) { "No X11 display; window geometry unavailable" }
    private val id = NativeLong(Native.getComponentID(window))
    private var last: Dimension? = null
    private val timer = Timer(intervalMs) { poll() }

    init {
        poll()
        timer.start()
    }

    /** The window's size as the server holds it, or null while the window has no X window or left it. */
    fun read(): Dimension? {
        if (!window.isDisplayable) return null
        val root = NativeLongByReference()
        val x = IntByReference()
        val y = IntByReference()
        val width = IntByReference()
        val height = IntByReference()
        val border = IntByReference()
        val depth = IntByReference()
        val status = xlib.XGetGeometry(display, id, root, x, y, width, height, border, depth)
        if (status == 0 || width.value <= 0 || height.value <= 0) return null
        return Dimension(width.value, height.value)
    }

    private fun poll() {
        val size = read() ?: return
        // Report transitions only: the owner re-applies the size, and repeating it every tick would be a resize
        // request per tick against a compositor that refuses it.
        if (size == last) return
        last = size
        report(size)
    }

    override fun close() {
        timer.stop()
        xlib.XCloseDisplay(display)
    }
}

/**
 * The window size AWT has to take so its client area matches the X window, or null when the two already agree.
 *
 * The compositor's size wins: it is the one on screen, and the size the UI must be laid out for. A divergence of a
 * pixel is a real divergence — the uncovered strip is exactly as tall as the difference — so it is not coalesced.
 */
internal fun reconciledWindowSize(awtSize: Dimension, serverSize: Dimension): Dimension? =
    if (awtSize.width == serverSize.width && awtSize.height == serverSize.height) null else Dimension(serverSize)
