package moe.rukamori.archivetune.ui.screens.settings.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SukisuAboutBackgroundTest {
    @Test
    fun `scroll progress follows the header offset and saturates once the header is gone`() {
        assertEquals(0f, sukisuHeaderScrollProgress(0, 0, 400), 0f)
        assertEquals(0.5f, sukisuHeaderScrollProgress(0, 200, 400), 1e-6f)
        assertEquals(1f, sukisuHeaderScrollProgress(0, 900, 400), 0f)
        assertEquals(1f, sukisuHeaderScrollProgress(3, 0, 400), 0f)
        assertEquals(0f, sukisuHeaderScrollProgress(0, 120, 0), 0f)
    }

    @Test
    fun `background is opaque at rest and fully faded after three quarters of the header`() {
        assertEquals(1f, sukisuBackgroundAlpha(0f), 0f)
        assertEquals(1f, sukisuBackgroundAlpha(0.25f), 1e-6f)
        assertEquals(0.5f, sukisuBackgroundAlpha(0.625f), 1e-6f)
        assertEquals(0f, sukisuBackgroundAlpha(1f), 0f)
    }

    @Test
    fun `frame loop runs only while resumed, visible and with motion allowed`() {
        assertTrue(sukisuBackgroundAnimates(resumed = true, animationsDisabled = false, animationScale = 1f, backgroundAlpha = 1f))
        assertFalse(sukisuBackgroundAnimates(resumed = false, animationsDisabled = false, animationScale = 1f, backgroundAlpha = 1f))
        assertFalse(sukisuBackgroundAnimates(resumed = true, animationsDisabled = true, animationScale = 1f, backgroundAlpha = 1f))
        assertFalse(sukisuBackgroundAnimates(resumed = true, animationsDisabled = false, animationScale = 0f, backgroundAlpha = 1f))
        assertFalse(sukisuBackgroundAnimates(resumed = true, animationsDisabled = false, animationScale = 1f, backgroundAlpha = 0f))
    }
}
