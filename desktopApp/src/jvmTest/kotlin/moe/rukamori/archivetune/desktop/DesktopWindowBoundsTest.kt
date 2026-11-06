package moe.rukamori.archivetune.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import java.awt.Rectangle
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopWindowBoundsTest {
    private class TestWindowState(
        override var placement: WindowPlacement,
        override var position: WindowPosition,
        override var size: DpSize,
    ) : WindowState {
        override var isMinimized = false
    }

    private fun space(
        screens: List<Rectangle>,
        workArea: Rectangle?,
        scale: Float,
    ) = DesktopScreenSpace(screens, workArea, scale)

    /** 1920x1080 at 150 % is the common laptop case: the logical screen is 1280x720, the taskbar eats 40 px. */
    private fun laptopAt150Percent() = space(
        screens = listOf(Rectangle(0, 0, 1920, 1080)),
        workArea = Rectangle(0, 0, 1920, 1040),
        scale = 1.5f,
    )

    @Test
    fun saveFallsBackWhenAtomicRenameIsUnsupported() {
        val directory = Files.createTempDirectory("archivetune-window-bounds")
        try {
            val state = TestWindowState(
                placement = WindowPlacement.Maximized,
                position = WindowPosition(40.dp, 60.dp),
                size = DpSize(900.dp, 700.dp),
            )
            var atomicMoves = 0
            var replacingMoves = 0
            DesktopWindowBounds.save(directory, state) { staged, target, atomic ->
                if (atomic) {
                    atomicMoves++
                    throw AtomicMoveNotSupportedException(staged.toString(), target.toString(), "test filesystem")
                }
                replacingMoves++
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING)
            }

            assertEquals(1, atomicMoves)
            assertEquals(1, replacingMoves)
            val saved = DesktopWindowBounds.load(directory, DesktopScreenSpace.UNKNOWN)
            assertEquals(900f, saved.size.width.value)
            assertEquals(700f, saved.size.height.value)
            assertEquals(WindowPlacement.Maximized, saved.placement)
            assertTrue(Files.notExists(directory.resolve("window-bounds.properties.tmp")))
        } finally {
            Files.deleteIfExists(directory.resolve("window-bounds.properties"))
            Files.deleteIfExists(directory.resolve("window-bounds.properties.tmp"))
            Files.delete(directory)
        }
    }

    @Test
    fun defaultWindowFitsA150PercentLaptopAboveTheTaskbar() {
        val fitted = DesktopWindowBounds.fitToWorkArea(DpSize(1024.dp, 720.dp), laptopAt150Percent())

        assertEquals(1024f, fitted.width.value)
        // 1040 device px of work area is 693.33 dp at 150 %, so the transport strip stays visible.
        assertEquals(1040f / 1.5f, fitted.height.value, 0.01f)
    }

    @Test
    fun defaultWindowFits1366x768At125Percent() {
        val fitted = DesktopWindowBounds.fitToWorkArea(
            DpSize(1024.dp, 720.dp),
            space(listOf(Rectangle(0, 0, 1366, 768)), Rectangle(0, 0, 1366, 728), 1.25f),
        )

        assertEquals(1024f, fitted.width.value)
        assertEquals(728f / 1.25f, fitted.height.value, 0.01f)
    }

    @Test
    fun defaultWindowIsNarrowedOnA200Percent4kScreen() {
        // 3840x2160 at 200 % is 1920x1080 logical, which the default already fits; a 1280x720 device work area is 640x360.
        val fitted = DesktopWindowBounds.fitToWorkArea(
            DpSize(1024.dp, 720.dp),
            space(listOf(Rectangle(0, 0, 1280, 720)), Rectangle(0, 0, 1280, 680), 2f),
        )

        assertEquals(640f, fitted.width.value, 0.01f)
        assertEquals(340f, fitted.height.value, 0.01f)
    }

    @Test
    fun minimumSizeIsScaledOnceAndNeverExceedsTheWorkArea() {
        val minimum = DesktopWindowBounds.minimumWindowSize(laptopAt150Percent())

        // 800x500 dp is 1200x750 device px at 150 %, and 750 px is still below the 1040 px work area height.
        assertEquals(1200, minimum.width)
        assertEquals(750, minimum.height)
    }

    @Test
    fun minimumSizeIsCappedByAScaledSmallScreen() {
        val minimum = DesktopWindowBounds.minimumWindowSize(
            space(listOf(Rectangle(0, 0, 1366, 768)), Rectangle(0, 0, 1366, 728), 1.25f),
        )

        assertEquals(1000, minimum.width)
        // 500 dp at 125 % is 625 px, still inside the 728 px work area, so the layout minimum is what is applied.
        assertEquals(625, minimum.height)
    }

    @Test
    fun savedPositionIsComparedInDevicePixelsNotDp() {
        val doubled = space(listOf(Rectangle(0, 0, 1280, 800)), Rectangle(0, 0, 1280, 760), 2f)

        // 600 dp at scale 2 is 1200 px, so only 80 px of the window would remain on a 1280 px screen: rejected.
        // The old dp-as-px check found 680 px of intersection here and kept a mostly off-screen position.
        assertFalse(DesktopWindowBounds.positionIsUsable(600f, 100f, DpSize(1024.dp, 720.dp), doubled))
        assertTrue(DesktopWindowBounds.positionIsUsable(0f, 0f, DpSize(1024.dp, 720.dp), doubled))
    }

    @Test
    fun savedPositionOnASecondNegativeMonitorIsKept() {
        val twoScreens = space(
            screens = listOf(Rectangle(0, 0, 1920, 1080), Rectangle(-1920, 0, 1920, 1080)),
            workArea = Rectangle(0, 0, 1920, 1040),
            scale = 1f,
        )

        assertTrue(DesktopWindowBounds.positionIsUsable(-1600f, 40f, DpSize(1024.dp, 720.dp), twoScreens))
    }

    @Test
    fun savedPositionAtUnitScaleKeepsTheExistingThreshold() {
        val single = space(listOf(Rectangle(0, 0, 1280, 800)), Rectangle(0, 0, 1280, 760), 1f)

        assertTrue(DesktopWindowBounds.positionIsUsable(1160f, 40f, DpSize(1024.dp, 720.dp), single))
        assertFalse(DesktopWindowBounds.positionIsUsable(1200f, 40f, DpSize(1024.dp, 720.dp), single))
    }

    @Test
    fun savedPositionIsDroppedWhenNoScreenIsKnown() {
        assertFalse(DesktopWindowBounds.positionIsUsable(40f, 40f, DpSize(1024.dp, 720.dp), DesktopScreenSpace.UNKNOWN))
    }

    @Test
    fun savedWindowIsFittedAndKeepsItsRememberedPosition() {
        val values = Properties().apply {
            setProperty("width", "1024")
            setProperty("height", "720")
            setProperty("x", "40")
            setProperty("y", "60")
            setProperty("maximized", "false")
        }

        val saved = assertNotNull(DesktopWindowBounds.decodeSavedWindow(values, laptopAt150Percent()))

        assertEquals(1040f / 1.5f, saved.size.height.value, 0.01f)
        val position = assertIs<WindowPosition.Absolute>(saved.position)
        assertEquals(40f, position.x.value)
        assertEquals(60f, position.y.value)
    }

    @Test
    fun savedWindowWithoutASizeFallsBackToThePlatformDefault() {
        assertNull(DesktopWindowBounds.decodeSavedWindow(Properties().apply { setProperty("x", "40") }, laptopAt150Percent()))
        assertNull(
            DesktopWindowBounds.decodeSavedWindow(
                Properties().apply { setProperty("width", "not-a-number"); setProperty("height", "720") },
                laptopAt150Percent(),
            ),
        )
    }

    @Test
    fun aSavedPositionOffTheScreenFallsBackToThePlatformDefault() {
        val values = Properties().apply {
            setProperty("width", "1024")
            setProperty("height", "720")
            setProperty("x", "5000")
            setProperty("y", "5000")
        }

        val saved = assertNotNull(DesktopWindowBounds.decodeSavedWindow(values, laptopAt150Percent()))

        assertEquals(WindowPosition.PlatformDefault, saved.position)
    }

    /** A 1080p primary next to a 4K secondary, both at 100 %; the primary's taskbar is the only inset AWT reports. */
    private fun twoMonitors() = DesktopScreenSpace(
        screens = listOf(Rectangle(0, 0, 1920, 1080), Rectangle(1920, 0, 3840, 2160)),
        workArea = Rectangle(0, 0, 1920, 1040),
        scale = 1f,
        defaultScreen = Rectangle(0, 0, 1920, 1080),
    )

    @Test
    fun aWindowRestoredOnASecondMonitorIsFittedToThatMonitor() {
        val values = Properties().apply {
            setProperty("width", "3000")
            setProperty("height", "1500")
            setProperty("x", "2000")
            setProperty("y", "100")
        }

        val saved = assertNotNull(DesktopWindowBounds.decodeSavedWindow(values, twoMonitors()))

        // On the primary's work area this would have been cut to 1920x1040 and lost its position.
        assertEquals(3000f, saved.size.width.value, 0.01f)
        assertEquals(1500f, saved.size.height.value, 0.01f)
        val position = assertIs<WindowPosition.Absolute>(saved.position)
        assertEquals(2000f, position.x.value)
        assertEquals(100f, position.y.value)
    }

    @Test
    fun aWindowRestoredOnThePrimaryMonitorIsFittedToThePrimaryWorkArea() {
        val values = Properties().apply {
            setProperty("width", "3000")
            setProperty("height", "1500")
            setProperty("x", "100")
            setProperty("y", "100")
        }

        val saved = assertNotNull(DesktopWindowBounds.decodeSavedWindow(values, twoMonitors()))

        assertEquals(1920f, saved.size.width.value, 0.01f)
        assertEquals(1040f, saved.size.height.value, 0.01f)
    }
}
