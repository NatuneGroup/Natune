package moe.rukamori.archivetune.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopWindowBoundsTest {
    private class TestWindowState(
        override var placement: WindowPlacement,
        override var position: WindowPosition,
        override var size: DpSize,
    ) : WindowState {
        override var isMinimized = false
    }

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
            val saved = DesktopWindowBounds.load(directory)
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
}
