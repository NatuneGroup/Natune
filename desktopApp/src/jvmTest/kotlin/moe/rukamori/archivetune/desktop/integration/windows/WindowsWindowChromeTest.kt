package moe.rukamori.archivetune.desktop.integration.windows

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsWindowChromeTest {
    @Test
    fun theDarkTitleBarAttributeIsAWin32Bool() {
        // DWMWA_USE_IMMERSIVE_DARK_MODE reads a 32-bit BOOL; a JNA boolean would be one byte and be rejected.
        assertEquals(1, WindowsWindowChrome.darkModeValue(true))
        assertEquals(0, WindowsWindowChrome.darkModeValue(false))
    }
}
