package moe.rukamori.archivetune.shared.ui.lyrics

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The bloom drawn into a bitmap. An emphasized letter's glow must keep the letter's shape and spill past
 * its cell; the glow clipped to the cell before it was enlarged drew a lit tile behind every letter.
 */
class LyricsGlyphGlowRenderTest {
    private val density = Density(1f)
    private val measurer = TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr)

    private fun render(text: String, ink: (androidx.compose.ui.text.TextLayoutResult) -> Rect): Pair<Rect, androidx.compose.ui.graphics.PixelMap> {
        val layout = measurer.measure(AnnotatedString(text), TextStyle(fontSize = 80.sp))
        val origin = Offset(60f, 60f)
        val cell = ink(layout).translate(origin)
        val bitmap = ImageBitmap(layout.size.width + 120, layout.size.height + 120)
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap), Size(bitmap.width.toFloat(), bitmap.height.toFloat())) {
            drawGlyphGlow(layout, cell, origin, Color.White, alpha = 1f, scale = 1.25f)
        }
        return cell to bitmap.toPixelMap()
    }

    private fun lit(map: androidx.compose.ui.graphics.PixelMap, x: Int, y: Int) =
        x in 0 until map.width && y in 0 until map.height && map[x, y].alpha > 0.05f

    @Test
    fun theLetterGlowSpillsPastItsCellInsteadOfFillingIt() {
        val (cell, map) = render("O") { Rect(0f, 0f, it.size.width.toFloat(), it.size.height.toFloat()) }
        // Some of the enlarged ring must land outside the cell, on at least one side.
        val outside = (cell.left.toInt() - 12 until cell.left.toInt()).any { x ->
            (cell.top.toInt() until cell.bottom.toInt()).any { y -> lit(map, x, y) }
        } || (cell.right.toInt() + 1..cell.right.toInt() + 12).any { x ->
            (cell.top.toInt() until cell.bottom.toInt()).any { y -> lit(map, x, y) }
        }
        assertTrue(outside, "the glow stopped at the letter's cell, which reads as a box")
        // The hole of the O stays dark: the glow is the letter's ink, not a filled tile.
        assertTrue(!lit(map, cell.center.x.toInt(), cell.center.y.toInt()), "the glow filled the letter's cell")
    }

    @Test
    fun aWordGlowNeverDragsItsNeighbourIn() {
        val text = "ab cd"
        val (cell, map) = render(text) { layout ->
            val first = layout.getBoundingBox(0)
            val last = layout.getBoundingBox(1)
            Rect(first.left, first.top, last.right, last.bottom)
        }
        // Well right of the scaled cell lies "cd", which must stay unlit.
        val farRight = (cell.right + cell.width * 0.4f).toInt()
        val untouched = (farRight until map.width).none { x -> (0 until map.height).any { y -> lit(map, x, y) } }
        assertTrue(untouched, "a neighbouring word was drawn into the bloom")
    }
}
