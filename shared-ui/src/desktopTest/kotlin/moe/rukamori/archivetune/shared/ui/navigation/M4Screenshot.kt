/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
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
