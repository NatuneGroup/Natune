package moe.rukamori.archivetune.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackdropTonePaletteTest {
    private fun Color.value() = maxOf(red, green, blue)

    private fun Color.saturation(): Float {
        val max = value()
        return if (max == 0f) 0f else (max - minOf(red, green, blue)) / max
    }

    @Test
    fun toneClampsValueAndKeepsHue() {
        val pureRed = Color(0xFFFF0000)
        val tone = pureRed.backdropTone(valueMin = 0.2f, valueMax = 0.5f)
        assertEquals(0.5f, tone.value(), 0.01f)
        assertTrue(tone.red > tone.green && tone.red > tone.blue)
        assertEquals(tone.green, tone.blue, 0.01f)
    }

    @Test
    fun nearGreySourcesStayNearGrey() {
        val grey = Color(0xFF808285)
        val tone = grey.backdropTone(valueMin = 0f, valueMax = 1f, saturationScale = 3f)
        assertTrue(tone.saturation() <= 0.08f + 0.01f)
    }

    @Test
    fun saturationIsScaledAndCapped() {
        val muted = Color.hsv(200f, 0.8f, 0.6f)
        val tone = muted.backdropTone(valueMin = 0f, valueMax = 1f, saturationScale = 0.45f, saturationCap = 0.30f)
        assertEquals(0.30f, tone.saturation(), 0.02f)
    }

    @Test
    fun darkPaletteDarkensDownwardsAndLightPaletteStaysLight() {
        val source = listOf(Color(0xFF3366CC))
        val dark = BackdropTonePalette.fromColorsLight(source, fallbackColor = Color.Black, darkTheme = true)
        assertTrue(dark.top.value() >= dark.mid.value())
        assertTrue(dark.mid.value() >= dark.bottom.value())
        assertTrue(dark.top.value() <= 0.66f + 0.01f)

        val light = BackdropTonePalette.fromColorsLight(source, fallbackColor = Color.White, darkTheme = false)
        assertTrue(light.bottom.value() >= 0.74f - 0.01f)
    }

    @Test
    fun emptySourceUsesTheToneOfTheFallback() {
        val palette = BackdropTonePalette.fromColorsLight(emptyList(), fallbackColor = Color(0xFF101010), darkTheme = true)
        assertEquals(palette.top, palette.mid)
        assertEquals(palette.mid, palette.bottom)
        assertEquals(0.12f, palette.top.value(), 0.01f)
    }
}
