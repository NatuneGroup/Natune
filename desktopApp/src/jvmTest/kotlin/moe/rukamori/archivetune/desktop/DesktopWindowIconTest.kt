package moe.rukamori.archivetune.desktop

import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopWindowIconTest {
    @Test
    fun everyWindowManagerSizeIsRenderedFromOneImage() {
        val source = BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB)

        val images = DesktopWindowIcon.scaled(source)

        assertEquals(DesktopWindowIcon.ICON_SIZES.size, images.size)
        assertContentEquals(DesktopWindowIcon.ICON_SIZES.toList(), images.map { it.getWidth(null) })
        assertTrue(images.all { it.getWidth(null) > 0 && it.getHeight(null) == it.getWidth(null) })
    }

    @Test
    fun anExactSizeIsReusedInsteadOfResampled() {
        val source = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)

        val images = DesktopWindowIcon.scaled(source)

        assertSame(source, images[DesktopWindowIcon.ICON_SIZES.indexOf(32)])
    }

    @Test
    fun aMissingImageProducesNoIconList() {
        assertTrue(DesktopWindowIcon.scaled(null).isEmpty())
    }

    @Test
    fun theApplicationJarCarriesTheWindowIcon() {
        // The packaged launcher gets its icon from jpackage; the AWT window reads this resource instead.
        assertTrue(DesktopWindowIcon.load().isNotEmpty())
    }
}
