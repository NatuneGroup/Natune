package moe.rukamori.archivetune.shared.ui.navigation

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
internal fun SemanticsNodeInteraction.writeM4Screenshot(name: String) {
    val directory = File(requireNotNull(System.getProperty("archivetune.screenshotDir")))
    directory.mkdirs()
    assertTrue(ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, name)))
}
