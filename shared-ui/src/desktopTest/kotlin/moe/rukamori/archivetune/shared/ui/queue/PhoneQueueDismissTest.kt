package moe.rukamori.archivetune.shared.ui.queue

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class PhoneQueueDismissTest {
    @Test fun tapOnDragStripReturnsToArtwork() = runComposeUiTest {
        var closes = 0
        setContent { QueueStrip { closes++ } }
        onNodeWithTag("queue-drag-strip")
            .assert(hasClickAction() and hasContentDescription("Back to player"))
            .performClick()
        runOnIdle { assertEquals(1, closes) }
    }

    @Test fun downwardDragOnDragStripReturnsToArtwork() = runComposeUiTest {
        var closes = 0
        setContent { QueueStrip { closes++ } }
        onNodeWithTag("queue-drag-strip").performTouchInput { swipe(center, Offset(center.x, center.y + 200f)) }
        runOnIdle { assertEquals(1, closes) }
    }

    @Test fun upwardDragOnDragStripKeepsQueueOpen() = runComposeUiTest {
        var closes = 0
        setContent { QueueStrip { closes++ } }
        onNodeWithTag("queue-drag-strip").performTouchInput { swipe(center, Offset(center.x, center.y - 200f)) }
        runOnIdle { assertEquals(0, closes) }
    }

    @Test fun downwardDragOnNonScrollingHeaderReturnsToArtwork() = runComposeUiTest {
        var closes = 0
        setContent {
            Box(Modifier.requiredSize(393.dp, 300.dp).testTag("queue-header-probe")
                .phoneQueueDismissOnDrag { closes++ })
        }
        onNodeWithTag("queue-header-probe").performTouchInput { swipe(center, Offset(center.x, center.y + 200f)) }
        runOnIdle { assertEquals(1, closes) }
    }

    @Composable
    private fun QueueStrip(onClose: () -> Unit) {
        MaterialTheme {
            CompositionLocalProvider(
                LocalPhoneQueuePlayerActions provides PhoneQueuePlayerActions(null, false, null, null, onClose),
            ) {
                Column(Modifier.requiredSize(393.dp, 700.dp)) {
                    Spacer(Modifier.height(300.dp))
                    PhoneQueueDragStrip()
                }
            }
        }
    }
}
