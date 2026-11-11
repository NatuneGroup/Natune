package moe.rukamori.archivetune.ui.component

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import org.junit.Assert.assertEquals
import org.junit.Test

class GlassPopupMenuTest {
    @Test
    fun liquidGlassWinsOverFrostAndTint() {
        assertEquals(GlassPopupLook.LIQUID_GLASS, glassPopupLook(liquidGlass = true, frosted = true, tinted = true))
    }

    @Test
    fun frostedMenuFollowsTheNavigationBarBlur() {
        assertEquals(GlassPopupLook.FROSTED, glassPopupLook(liquidGlass = false, frosted = true, tinted = false))
    }

    @Test
    fun tintedFrostNeedsTheFrostedBackdrop() {
        assertEquals(GlassPopupLook.TINTED_FROSTED, glassPopupLook(liquidGlass = false, frosted = true, tinted = true))
        assertEquals(GlassPopupLook.SOLID, glassPopupLook(liquidGlass = false, frosted = false, tinted = true))
    }

    @Test
    fun menuIsOpaqueWhenNoGlassSettingCanDraw() {
        assertEquals(GlassPopupLook.SOLID, glassPopupLook(liquidGlass = false, frosted = false, tinted = false))
    }

    @Test
    fun menuHangsFromTheEndEdgeOfItsAnchor() {
        val anchor = IntRect(left = 900, top = 100, right = 948, bottom = 148)
        assertEquals(IntOffset(684, 152), glassPopupTopLeft(anchor, popupWidthPx = 264, gapPx = 4))
    }

    @Test
    fun menuStaysOnScreenWhenTheAnchorIsAtTheStartEdge() {
        val anchor = IntRect(left = 0, top = 0, right = 40, bottom = 40)
        assertEquals(IntOffset(0, 44), glassPopupTopLeft(anchor, popupWidthPx = 264, gapPx = 4))
    }
}
