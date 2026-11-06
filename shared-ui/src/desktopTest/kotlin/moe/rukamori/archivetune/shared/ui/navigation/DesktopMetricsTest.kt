package moe.rukamori.archivetune.shared.ui.navigation

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopMetricsTest {
    @Test fun labelledSidebarAppearsAt1024AndNotAtTheMinimumWindow() {
        assertTrue(1024.dp >= DesktopLabelledSidebarMinWidth)
        assertFalse(900.dp >= DesktopLabelledSidebarMinWidth)
    }

    @Test fun settingsKeepsTwoPanesAt1024ContentWidth() {
        // 1024 window minus a 232 dp labelled sidebar still fits the category pane plus a detail page.
        val content = 1024.dp - 232.dp
        assertTrue(content >= DesktopSettingsTwoPaneMinWidth)
        assertEquals(240.dp, desktopSettingsNavWidth(content))
        assertEquals(320.dp, desktopSettingsNavWidth(1208.dp))
    }

    @Test fun homeCardsGrowWithTheDesktopButNeverShrinkBelowThePhoneSize() {
        assertEquals(150.dp, desktopHomeCardWidth(792.dp))
        assertEquals(156.dp, desktopHomeCardWidth(944.dp))
        assertEquals(160.dp, desktopHomeCardWidth(1208.dp))
    }
}
