package moe.rukamori.archivetune.desktop.integration.windows

import com.sun.jna.CallbackReference
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.BaseTSD
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.Window
import java.util.concurrent.atomic.AtomicReference
import moe.rukamori.archivetune.desktop.DesktopDiagnosticLog
import moe.rukamori.archivetune.shared.domain.diagnostics.DiagnosticLevel

/**
 * The Windows frame the app draws itself: the non-client area is removed in `WM_NCCALCSIZE`, the
 * caption and the resize edges are reported from `WM_NCHITTEST`, and the maximize button answers
 * `HTMAXBUTTON` so Windows 11 offers Snap Layouts over it. The window keeps `WS_THICKFRAME`, so
 * Aero Snap, the system menu and per-monitor DPI keep working exactly as they do for a native
 * window.
 *
 * Every native call is optional: a Windows build that rejects an attribute, a missing DWM or a
 * stripped JNA leaves the stock frame rather than failing the window. The Compose caption publishes
 * the pixel rectangles it drew and this class only answers hit tests for them, so the two cannot
 * disagree about where a button is.
 */
internal object WindowsCustomFrame {
    // Window messages the frame procedure answers.
    private const val WM_NCCALCSIZE = 0x0083
    private const val WM_NCHITTEST = 0x0084
    private const val WM_NCACTIVATE = 0x0086
    private const val WM_GETMINMAXINFO = 0x0024
    private const val WM_NCRBUTTONUP = 0x00A5
    private const val WM_SETTINGCHANGE = 0x001A
    private const val WM_DWMCOMPOSITIONCHANGED = 0x031E

    // Hit test codes, from winuser.h.
    private const val HTCLIENT = 1
    private const val HTCAPTION = 2
    private const val HTMINBUTTON = 8
    private const val HTMAXBUTTON = 9
    private const val HTLEFT = 10
    private const val HTRIGHT = 11
    private const val HTTOP = 12
    private const val HTTOPLEFT = 13
    private const val HTTOPRIGHT = 14
    private const val HTBOTTOM = 15
    private const val HTBOTTOMLEFT = 16
    private const val HTBOTTOMRIGHT = 17
    private const val HTCLOSE = 20

    private const val GWL_WNDPROC = -4
    private const val SC_KEYMENU = 0xF100
    private const val SM_CXSIZEFRAME = 32
    private const val SM_CYSIZEFRAME = 33
    private const val SM_CXPADDEDBORDER = 92
    /** A resize edge narrow enough not to eat the caption buttons, wide enough to grab. */
    private const val RESIZE_EDGE_DP = 8

    // DWM attributes (dwmapi.h). BORDER_COLOR is 34 and CAPTION_COLOR is 35: writing the accent to
    // 35, as an older
    // revision did, recolours the caption instead of the frame and leaves the border at the system
    // default.
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE_PRE_20H1 = 19
    private const val DWMWA_BORDER_COLOR = 34
    private const val DWMWA_CAPTION_COLOR = 35
    private const val DWMWA_SYSTEMBACKDROP_TYPE = 38
    private const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
    private const val DWMWCP_ROUND = 2
    private const val DWMSBT_MAINWINDOW_MICA = 2
    private const val DWMWA_COLOR_NONE = -2
    // 0xFFFFFFFF tells DWM to use its default colour, so an app-drawn caption is never tinted
    // behind our Mica band.
    private const val DWMWA_COLOR_DEFAULT = -1
    private const val S_OK = 0

    internal interface DwmApi : com.sun.jna.Library {
        fun DwmSetWindowAttribute(window: Pointer, attribute: Int, value: Pointer, size: Int): Int
    }

    /**
     * The `user32` entry points this frame needs, declared here instead of taken from JNA's
     * `User32` interface: the four calls a maximized or per-monitor-DPI window makes (`IsZoomed`,
     * `ScreenToClient`, `GetDpiForWindow`, `GetSystemMetricsForDpi`) are not declared there, and
     * the window-procedure handles stay [Pointer] because that is what the 64-bit entry points take
     * and return. JNA's `User32.SetWindowLongPtr` already returns `Pointer`; a `LONG_PTR` there is
     * a 5.6-era signature and truncates the procedure address on 64-bit Windows.
     *
     * The plain Win32 names are deliberate: `W32APIOptions.DEFAULT_OPTIONS` maps each one to its
     * `W` (Unicode) form and falls back to the undecorated export, which is how the four calls
     * without an `A`/`W` pair are found.
     */
    private interface NatuneUser32 : StdCallLibrary {
        fun GetWindowLongPtr(hwnd: WinDef.HWND, index: Int): BaseTSD.LONG_PTR

        fun SetWindowLongPtr(hwnd: WinDef.HWND, index: Int, value: Pointer): Pointer?

        fun CallWindowProc(
            previous: Pointer,
            hwnd: WinDef.HWND,
            message: Int,
            wParam: WinDef.WPARAM,
            lParam: WinDef.LPARAM,
        ): WinDef.LRESULT

        fun DefWindowProc(
            hwnd: WinDef.HWND,
            message: Int,
            wParam: WinDef.WPARAM,
            lParam: WinDef.LPARAM,
        ): WinDef.LRESULT

        fun SetWindowPos(
            hwnd: WinDef.HWND,
            after: WinDef.HWND?,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            flags: Int,
        ): Int

        fun PostMessage(
            hwnd: WinDef.HWND,
            message: Int,
            wParam: WinDef.WPARAM,
            lParam: WinDef.LPARAM,
        ): Int

        fun GetClientRect(hwnd: WinDef.HWND, rect: WinDef.RECT): Int

        fun ScreenToClient(hwnd: WinDef.HWND, point: WinDef.POINT): Int

        fun IsZoomed(hwnd: WinDef.HWND): Int

        fun GetDpiForWindow(hwnd: WinDef.HWND): Int

        fun GetSystemMetricsForDpi(index: Int, dpi: Int): Int
    }

    private val dwm: DwmApi? by lazy {
        if (!isSupported()) null
        else runCatching { Native.load("dwmapi", DwmApi::class.java) }.getOrNull()
    }

    private val user32: NatuneUser32? by lazy {
        if (!isSupported()) {
            null
        } else {
            runCatching {
                Native.load("user32", NatuneUser32::class.java, W32APIOptions.DEFAULT_OPTIONS)
            }
                .getOrNull()
        }
    }

    /** One caption control the Compose side drew, in client pixels. */
    internal data class CaptionArea(
        val hitTest: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom
    }

    /**
     * Hit test codes this procedure exposes to the Compose caption, so both sides name the same
     * thing.
     */
    const val CLIENT = HTCLIENT
    const val CAPTION = HTCAPTION
    const val MINIMIZE_BUTTON = HTMINBUTTON
    const val MAXIMIZE_BUTTON = HTMAXBUTTON
    const val CLOSE_BUTTON = HTCLOSE

    private val captionAreas = AtomicReference<List<CaptionArea>>(emptyList())
    private val clientHoles = AtomicReference<List<CaptionArea>>(emptyList())
    private val buttons = AtomicReference<List<CaptionArea>>(emptyList())
    private val installed = AtomicReference<Installed?>(null)

    /**
     * The installation of one window. [procedure] is the strong reference JNA requires: the
     * callback is a Java object whose function pointer is only valid while the object is reachable,
     * so it is held here for as long as the window procedure is installed and released with it.
     */
    private class Installed(
        val hwnd: WinDef.HWND,
        val previous: Pointer?,
        val procedure: WinUser.WindowProc,
    )

    fun isSupported(): Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    /** True once the procedure answers this window; a window without it keeps the stock frame. */
    fun isInstalled(): Boolean = installed.get() != null

    /**
     * Publishes the rectangles the title bar drew: the caption stretches drag the window, the
     * client holes stay clicks and the button areas answer the three caption hit tests. Called from
     * the Compose layout on every placement change. A button area is stored inside the caption list
     * with its own hit test, and is matched first.
     */
    fun publish(caption: List<CaptionArea>, clientHoles: List<CaptionArea>) {
        captionAreas.set(caption)
        this.clientHoles.set(clientHoles)
    }

    /**
     * One caption button, published from its own layout; the newest rectangle of a kind replaces
     * the previous one.
     */
    fun publishButton(area: CaptionArea) {
        buttons.updateAndGet { current -> current.filter { it.hitTest != area.hitTest } + area }
    }

    /**
     * Installs the frame procedure on [window]. The window must be displayable; a peer that is not
     * created yet is not an error, and the caller simply keeps the system frame for this window.
     */
    fun install(window: Window): Boolean {
        if (!isSupported()) return false
        if (installed.get() != null) return true
        val api = user32 ?: return false
        val hwnd =
            runCatching { WinDef.HWND(Native.getComponentPointer(window)) }.getOrNull()
                ?: return false
        return runCatching {
            val previous = api.GetWindowLongPtr(hwnd, GWL_WNDPROC).toPointer()
            val procedure = Procedure(api, previous)
            val pointer = CallbackReference.getFunctionPointer(procedure)
            val replaced = api.SetWindowLongPtr(hwnd, GWL_WNDPROC, pointer)
            if (replaced == null) {
                false
            } else {
                installed.set(Installed(hwnd, previous, procedure))
                // The client area changed from frame to app-drawn: a window-position change makes
                // Windows re-query it.
                api.SetWindowPos(
                    hwnd,
                    null,
                    0,
                    0,
                    0,
                    0,
                    WinUser.SWP_NOMOVE or
                        WinUser.SWP_NOSIZE or
                        WinUser.SWP_NOZORDER or
                        WinUser.SWP_FRAMECHANGED,
                )
                DesktopDiagnosticLog.host(
                    "window frame: Windows procedure installed",
                    DiagnosticLevel.INFO,
                )
                true
            }
        }
            .getOrElse { failure ->
                DesktopDiagnosticLog.host(
                    "window frame: procedure unavailable (${failure.javaClass.simpleName})",
                )
                false
            }
    }

    /**
     * Restores the original procedure, so a window that switches back to the system frame hit-tests
     * normally.
     */
    fun uninstall() {
        val current = installed.getAndSet(null) ?: return
        val api = user32 ?: return
        runCatching {
            current.previous?.let { api.SetWindowLongPtr(current.hwnd, GWL_WNDPROC, it) }
            api.SetWindowPos(
                current.hwnd,
                null,
                0,
                0,
                0,
                0,
                WinUser.SWP_NOMOVE or
                    WinUser.SWP_NOSIZE or
                    WinUser.SWP_NOZORDER or
                    WinUser.SWP_FRAMECHANGED,
            )
        }
    }

    /**
     * The dark title bar, Mica behind the client area and the Windows accent on the frame border.
     */
    fun applyBackdrop(window: Window, dark: Boolean, accentFrame: Boolean = true) {
        val api = dwm ?: return
        if (!window.isDisplayable) return
        val handle = runCatching { Native.getComponentPointer(window) }.getOrNull() ?: return
        val memory = Memory(4L)
        fun attribute(id: Int, value: Int): Boolean = runCatching {
            memory.setInt(0, value)
            api.DwmSetWindowAttribute(handle, id, memory, 4) == S_OK
        }
            .getOrDefault(false)
        val darkApplied =
            attribute(DWMWA_USE_IMMERSIVE_DARK_MODE, if (dark) 1 else 0) ||
                attribute(DWMWA_USE_IMMERSIVE_DARK_MODE_PRE_20H1, if (dark) 1 else 0)
        val mica = attribute(DWMWA_SYSTEMBACKDROP_TYPE, DWMSBT_MAINWINDOW_MICA)
        attribute(DWMWA_WINDOW_CORNER_PREFERENCE, DWMWCP_ROUND)
        // The app draws its own caption, so DWM's caption color must stay at its default; tinted
        // captions clash with
        // the Compose title bar's Mica surface. The accent goes on the *border* (34), not the
        // caption (35).
        attribute(DWMWA_CAPTION_COLOR, DWMWA_COLOR_DEFAULT)
        val border = if (accentFrame) accentColor() else null
        val borderApplied =
            if (border == null) {
                attribute(DWMWA_BORDER_COLOR, DWMWA_COLOR_NONE)
            } else {
                attribute(DWMWA_BORDER_COLOR, border)
            }
        DesktopDiagnosticLog.host(
            "window chrome: dark=$darkApplied mica=$mica accentBorder=$borderApplied",
            DiagnosticLevel.INFO,
        )
    }

    /**
     * `HKCU\...\DWM`: a COLORREF plus the ColorPrevalence flag that says whether the accent is on
     * any window edge.
     */
    private fun accentColor(): Int? = runCatching {
        val path = "Software\\Microsoft\\Windows\\DWM"
        val enabled =
            Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, path, "ColorPrevalence") == 1
        if (!enabled) return null
        val abgr = Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, path, "AccentColor")
        // The registry stores AABBGGRR; DWM wants 0x00BBGGRR.
        abgr and 0x00FFFFFF
    }
        .getOrNull()

    /**
     * `NCCALCSIZE_PARAMS`: the three candidate client rectangles and the window position the
     * message carries. JNA needs the field order declared because the native layout is `rgrc[3]`
     * followed by `lppos` (56 bytes on 64-bit), while JNA's implicit order would sort `lppos`
     * before `rgrc` and read every rectangle from the wrong offset.
     */
    @Structure.FieldOrder("rgrc", "lppos")
    internal class NcCalcSizeParams(pointer: Pointer) : Structure(pointer) {
        @JvmField
        var rgrc: Array<WinDef.RECT> = arrayOf(WinDef.RECT(), WinDef.RECT(), WinDef.RECT())

        @JvmField var lppos: Pointer? = null

        init {
            read()
        }
    }

    /** The procedure itself: it calls the previous one for every message it does not own. */
    private class Procedure(
        private val api: NatuneUser32,
        private val previous: Pointer?,
    ) : WinUser.WindowProc {

        override fun callback(
            hwnd: WinDef.HWND,
            uMsg: Int,
            wParam: WinDef.WPARAM,
            lParam: WinDef.LPARAM,
        ): WinDef.LRESULT {
            return when (uMsg) {
                WM_NCCALCSIZE ->
                    if (wParam.toInt() == 1) {
                        // The client area becomes the whole window. A maximized window would then
                        // cover the taskbar, so
                        // its bounds are inset by the frame the window manager keeps.
                        val zoomed = runCatching { api.IsZoomed(hwnd) != 0 }.getOrDefault(false)
                        if (zoomed) {
                            runCatching {
                                val params = NcCalcSizeParams(Pointer(lParam.toLong()))
                                val edge = frameEdge(hwnd)
                                params.rgrc[0].left += edge
                                params.rgrc[0].top += edge
                                params.rgrc[0].right -= edge
                                params.rgrc[0].bottom -= edge
                                params.write()
                            }
                        }
                        WinDef.LRESULT(0L)
                    } else {
                        pass(hwnd, uMsg, wParam, lParam)
                    }
                WM_NCHITTEST -> WinDef.LRESULT(hitTest(hwnd, lParam.toLong()).toLong())
                WM_GETMINMAXINFO -> pass(hwnd, uMsg, wParam, lParam)
                WM_NCACTIVATE -> pass(hwnd, uMsg, wParam, lParam)
                WM_SETTINGCHANGE,
                WM_DWMCOMPOSITIONCHANGED -> {
                    // A theme or DWM change re-applies the backdrop on the next window message. The
                    // paint itself is
                    // Compose's, so no invalidation is needed here.
                    pass(hwnd, uMsg, wParam, lParam)
                }
                WM_NCRBUTTONUP -> {
                    // A right click on the app's caption opens the system menu where Windows would.
                    if (wParam.toInt() == HTCAPTION) {
                        api.PostMessage(
                            hwnd,
                            0x0112,
                            WinDef.WPARAM(SC_KEYMENU.toLong()),
                            WinDef.LPARAM(0L),
                        )
                    }
                    WinDef.LRESULT(0L)
                }
                else -> pass(hwnd, uMsg, wParam, lParam)
            }
        }

        private fun pass(
            hwnd: WinDef.HWND,
            uMsg: Int,
            wParam: WinDef.WPARAM,
            lParam: WinDef.LPARAM,
        ): WinDef.LRESULT =
            if (previous == null) {
                api.DefWindowProc(hwnd, uMsg, wParam, lParam)
            } else {
                api.CallWindowProc(previous, hwnd, uMsg, wParam, lParam)
            }

        private fun hitTest(hwnd: WinDef.HWND, packed: Long): Int {
            // lParam carries the signed screen position in its two halves.
            val screenX = (packed and 0xFFFF).toShort().toInt()
            val screenY = ((packed shr 16) and 0xFFFF).toShort().toInt()
            val point = WinDef.POINT(screenX, screenY)
            if (api.ScreenToClient(hwnd, point) == 0) return HTCLIENT
            val x = point.x
            val y = point.y
            buttons
                .get()
                .firstOrNull { it.contains(x, y) }
                ?.let {
                    return it.hitTest
                }
            clientHoles
                .get()
                .firstOrNull { it.contains(x, y) }
                ?.let {
                    return HTCLIENT
                }
            captionAreas
                .get()
                .firstOrNull { it.contains(x, y) }
                ?.let {
                    return HTCAPTION
                }
            val client = WinDef.RECT()
            if (api.GetClientRect(hwnd, client) == 0) return HTCLIENT
            val edge = resizeEdge(hwnd)
            val atLeft = x < edge
            val atRight = x >= client.right - edge
            val atTop = y < edge
            val atBottom = y >= client.bottom - edge
            return when {
                atTop && atLeft -> HTTOPLEFT
                atTop && atRight -> HTTOPRIGHT
                atBottom && atLeft -> HTBOTTOMLEFT
                atBottom && atRight -> HTBOTTOMRIGHT
                atLeft -> HTLEFT
                atRight -> HTRIGHT
                atTop -> HTTOP
                atBottom -> HTBOTTOM
                // Everything the caption did not claim stays a client area: the page keeps its own
                // clicks.
                else -> HTCLIENT
            }
        }

        /**
         * The frame width Windows would have drawn: the resize border plus its padding, at this
         * window's DPI.
         */
        private fun frameEdge(hwnd: WinDef.HWND): Int {
            val dpi = runCatching { api.GetDpiForWindow(hwnd) }.getOrDefault(96)
            return runCatching {
                    api.GetSystemMetricsForDpi(SM_CXSIZEFRAME, dpi) +
                        api.GetSystemMetricsForDpi(SM_CXPADDEDBORDER, dpi)
                }
                .getOrDefault(8)
        }

        private fun resizeEdge(hwnd: WinDef.HWND): Int {
            val dpi = runCatching { api.GetDpiForWindow(hwnd) }.getOrDefault(96)
            val frame = runCatching {
                api.GetSystemMetricsForDpi(SM_CXSIZEFRAME, dpi) +
                    api.GetSystemMetricsForDpi(SM_CXPADDEDBORDER, dpi)
            }
                .getOrDefault(8)
            // One edge in device pixels, never zero, never wider than a quarter of a small window.
            return frame.coerceAtLeast((RESIZE_EDGE_DP * dpi) / 96).coerceAtMost(24)
        }
    }
}
