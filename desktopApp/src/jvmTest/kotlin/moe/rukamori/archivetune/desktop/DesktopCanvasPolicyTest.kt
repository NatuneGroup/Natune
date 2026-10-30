package moe.rukamori.archivetune.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkDirectory

class DesktopCanvasPolicyTest {
    @Test
    fun positionsFollowTheAudioClockModuloTheContainerDuration() {
        assertEquals(0L, desktopCanvasPosition(0, 0, 8_000L))
        assertEquals(3_000L, desktopCanvasPosition(11_000, 0, 8_000L))
        assertEquals(1_000L, desktopCanvasPosition(9_000, 2_000, 6_000L))
        assertEquals(0L, desktopCanvasPosition(9_000, 2_000, 7_000L))
        // Without a real container duration no position is invented.
        assertEquals(5_000L, desktopCanvasPosition(5_000, 0, null))
        assertEquals(5_000L, desktopCanvasPosition(5_000, 0, 0L))
        // A position behind the anchor wraps inside the container instead of going negative.
        assertEquals(5_000L, desktopCanvasPosition(1_000, 2_000, 6_000L))
        assertEquals(0L, desktopCanvasPosition(-1_000, 500, null))
    }

    @Test
    fun onlyPlainHttpsUrlsPass() {
        assertTrue(desktopCanvasHttpsUrl("https://video.example/canvas.mp4"))
        assertFalse(desktopCanvasHttpsUrl("http://video.example/canvas.mp4"))
        assertFalse(desktopCanvasHttpsUrl("https://user@video.example/canvas.mp4"))
        assertFalse(desktopCanvasHttpsUrl("https:///canvas.mp4"))
        assertFalse(desktopCanvasHttpsUrl("not a url"))
    }

    @Test
    fun resolverEndpointsAreValidatedSplitAndBounded() {
        assertEquals(
            listOf("https://a.example", "http://127.0.0.1:8080"),
            parseDesktopCanvasEndpoints(" https://a.example/\nhttp://127.0.0.1:8080, https://a.example "),
        )
        assertEquals(emptyList(), parseDesktopCanvasEndpoints(null))
        assertEquals(emptyList(), parseDesktopCanvasEndpoints("ftp://x.example, https://user@h.example"))
        assertEquals(8, parseDesktopCanvasEndpoints((1..20).joinToString("\n") { "https://h$it.example" }).size)
    }

    @Test
    fun canvasFilesStayInsideTheOverrideDirectory() {
        val instance = Files.createTempDirectory("desktop-canvas-policy-test")
        try {
            val dir = instance.resolve(CustomArtworkDirectory.CANVASES.directoryName)
            Files.createDirectories(dir)
            val file = dir.resolve("a.mp4")
            Files.write(file, byteArrayOf(1))
            assertEquals(file.toRealPath(), validatedDesktopCanvasFile(instance, file.toUri().toString()))
            // A nested or missing file and anything outside the folder are refused.
            val nestedDir = dir.resolve("nested")
            Files.createDirectory(nestedDir)
            val nested = nestedDir.resolve("b.mp4")
            Files.write(nested, byteArrayOf(1))
            assertFailsWith<IllegalArgumentException> { validatedDesktopCanvasFile(instance, nested.toUri().toString()) }
            val empty = dir.resolve("empty.mp4").also { Files.write(it, byteArrayOf()) }
            assertFailsWith<IllegalArgumentException> { validatedDesktopCanvasFile(instance, empty.toUri().toString()) }
            assertFailsWith<java.nio.file.NoSuchFileException> {
                validatedDesktopCanvasFile(instance, dir.resolve("missing.mp4").toUri().toString())
            }
            val outside = Files.createTempFile("desktop-canvas-policy-outside", ".mp4")
            try {
                assertFailsWith<IllegalArgumentException> { validatedDesktopCanvasFile(instance, outside.toUri().toString()) }
            } finally {
                Files.deleteIfExists(outside)
            }
        } finally {
            instance.toFile().deleteRecursively()
        }
    }
}
