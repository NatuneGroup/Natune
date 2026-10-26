package moe.rukamori.archivetune.shared.ui

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ArchiveTuneRootTest {
    @Test
    fun rootShowsTheAppNameAndFoundationStatus() = runComposeUiTest {
        setContent { ArchiveTuneRoot() }

        onNodeWithText("ArchiveTune").assertIsDisplayed()
        onNodeWithText("Kotlin Multiplatform foundation").assertIsDisplayed()
    }

    @Test
    fun rootPaintsItsSurfaceAndWritesAScreenshot() = runComposeUiTest {
        setContent { ArchiveTuneRoot() }

        val image = onRoot().captureToImage().toAwtImage()

        assertTrue(distinctColors(image) > 1, "root rendered as one flat colour")
        val directory = File(requireNotNull(System.getProperty(SCREENSHOT_DIR_PROPERTY)))
        directory.mkdirs()
        val target = File(directory, "archivetune-root.png")
        assertTrue(ImageIO.write(image, "png", target), "no PNG writer for ${target.path}")
        assertTrue(target.length() > 0L, "empty screenshot at ${target.path}")
    }

    private fun distinctColors(image: BufferedImage): Int {
        val colors = HashSet<Int>()
        for (y in 0 until image.height step SAMPLE_STEP) {
            for (x in 0 until image.width step SAMPLE_STEP) {
                colors += image.getRGB(x, y)
            }
        }
        return colors.size
    }

    private companion object {
        const val SCREENSHOT_DIR_PROPERTY = "archivetune.screenshotDir"
        const val SAMPLE_STEP = 4
    }
}
