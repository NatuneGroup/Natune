package moe.rukamori.archivetune.desktop.integration

import java.awt.EventQueue
import java.awt.Robot
import java.awt.event.KeyEvent
import java.util.concurrent.TimeUnit
import javax.swing.JFrame
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.NativeLongByReference
import com.sun.jna.ptr.PointerByReference
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.*
import kotlin.test.*
import org.junit.Assume.assumeTrue
import moe.rukamori.archivetune.desktop.integration.linux.LinuxWindowVisibility

class DesktopLifecycleWindowTest {
    @Test fun realAwtHiddenWindowAndX11OcclusionAreObserved() {
        assumeTrue("Enable on an actual X11 test display", System.getProperty("archivetune.desktop.window.tests") == "true")
        lateinit var frame: JFrame
        lateinit var cover: JFrame
        lateinit var activity: DesktopWindowActivityObserver
        lateinit var native: LinuxWindowVisibility
        EventQueue.invokeAndWait {
            frame = JFrame().apply { isUndecorated = true; setBounds(80, 80, 320, 240); isVisible = true }
            activity = DesktopWindowActivityObserver(frame)
            native = LinuxWindowVisibility(frame, activity::nativeActivity)
            cover = JFrame().apply { isUndecorated = true; setBounds(60, 60, 400, 300); isVisible = true; toFront() }
        }
        try {
            await { activity.activity.value.occluded }
            assertFalse(activity.activity.value.animate)
            EventQueue.invokeAndWait { cover.isVisible = false }
            await { !activity.activity.value.occluded }
            EventQueue.invokeAndWait { frame.isVisible = false }
            await { !activity.activity.value.visible }
            assertFalse(activity.activity.value.animate)
        } finally {
            EventQueue.invokeAndWait { native.close(); activity.close(); cover.dispose(); frame.dispose() }
        }
    }

    @Test fun realGtkFileAndFolderDialogsCancelWithoutFakeSelections() {
        assumeTrue("Enable on an actual X11 test display", System.getProperty("archivetune.desktop.window.tests") == "true")
        DesktopFilePickers().use { picker ->
            for (folder in listOf(false, true)) {
                val selection = CompletableFuture.supplyAsync { runBlocking { if (folder) picker.folder() else picker.files() } }
                // Robot goes through the actual native dialog's event loop, not a mocked picker.
                // Xvfb has no window manager: focus the real dialog explicitly before sending input.
                focusDialog(DesktopStrings[if (folder) "open_folder" else "open_file"])
                Robot().apply { keyPress(KeyEvent.VK_ESCAPE); keyRelease(KeyEvent.VK_ESCAPE) }
                val result = selection.get(5, TimeUnit.SECONDS)
                if (folder) assertNull(result) else assertTrue((result as List<*>).isEmpty())
            }
        }
    }

    private interface Xlib : Library {
        fun XOpenDisplay(name: String?): Pointer?
        fun XDefaultRootWindow(display: Pointer): NativeLong
        fun XQueryTree(display: Pointer, window: NativeLong, root: NativeLongByReference,
            parent: NativeLongByReference, children: PointerByReference, count: IntByReference): Int
        fun XFetchName(display: Pointer, window: NativeLong, name: PointerByReference): Int
        fun XSetInputFocus(display: Pointer, window: NativeLong, revert: Int, time: NativeLong): Int
        fun XRaiseWindow(display: Pointer, window: NativeLong): Int
        fun XFlush(display: Pointer): Int
        fun XFree(pointer: Pointer): Int
        fun XCloseDisplay(display: Pointer): Int
    }
    private fun focusDialog(title: String) {
        val x = Native.load("libX11.so.6", Xlib::class.java)
        val display = checkNotNull(x.XOpenDisplay(null))
        fun find(window: NativeLong, depth: Int): NativeLong? {
            if (depth > 16) return null
            val name = PointerByReference()
            if (x.XFetchName(display, window, name) != 0 && name.value != null) {
                val found = try { name.value.getString(0, "UTF-8") == title } finally { x.XFree(name.value) }
                if (found) return window
            }
            val children = PointerByReference()
            val count = IntByReference()
            if (x.XQueryTree(display, window, NativeLongByReference(), NativeLongByReference(), children, count) == 0) return null
            try {
                if (children.value != null) for (i in 0 until count.value.coerceAtMost(4096)) {
                    find(children.value.getNativeLong(i.toLong() * Native.LONG_SIZE), depth + 1)?.let { return it }
                }
            } finally { children.value?.let { x.XFree(it) } }
            return null
        }
        try {
            var dialog: NativeLong? = null
            await { find(x.XDefaultRootWindow(display), 0).also { dialog = it } != null }
            x.XRaiseWindow(display, dialog!!)
            x.XSetInputFocus(display, dialog!!, 1, NativeLong(0))
            x.XFlush(display)
        } finally { x.XCloseDisplay(display) }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(30)
        assertTrue(condition())
    }
}
