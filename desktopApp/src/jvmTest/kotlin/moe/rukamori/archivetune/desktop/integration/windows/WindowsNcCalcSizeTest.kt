package moe.rukamori.archivetune.desktop.integration.windows

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsNcCalcSizeTest {
    @Test
    fun aMinimizedWindowPassesNcCalcSizeThrough() {
        assertEquals(
            WindowsCustomFrame.NcCalcSizeResponse.PassThrough,
            WindowsCustomFrame.ncCalcSizeResponse(minimized = true, maximized = false),
        )
    }

    @Test
    fun aMinimizedMaximizedWindowStillPassesThrough() {
        // IsZoomed keeps reporting true for a window minimized from the maximized state; the
        // minimized branch must win or the restore bounds are corrupted.
        assertEquals(
            WindowsCustomFrame.NcCalcSizeResponse.PassThrough,
            WindowsCustomFrame.ncCalcSizeResponse(minimized = true, maximized = true),
        )
    }

    @Test
    fun aMaximizedWindowIsInsetByTheFrame() {
        assertEquals(
            WindowsCustomFrame.NcCalcSizeResponse.CoverWindowInset,
            WindowsCustomFrame.ncCalcSizeResponse(minimized = false, maximized = true),
        )
    }

    @Test
    fun aNormalWindowLetsTheClientCoverTheWindow() {
        assertEquals(
            WindowsCustomFrame.NcCalcSizeResponse.CoverWindow,
            WindowsCustomFrame.ncCalcSizeResponse(minimized = false, maximized = false),
        )
    }
}
