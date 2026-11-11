package moe.rukamori.archivetune.shared.ui.queue

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.queue.QueueRepeatMode
import moe.rukamori.archivetune.shared.domain.repository.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class PhoneQueueHeaderTest {
    @Test fun controlsDispatchToTheirOwnersAndStaleCurrentTrackActionsAreDisabled() = runComposeUiTest {
        val calls = mutableListOf<String>()
        val current = ContentOccurrence("track@1", ContentItem(ContentRef("youtube", ContentKind.SONG, "track"), "Song", "Artist"))
        var identity by mutableStateOf(current.occurrenceId)
        var infinite by mutableStateOf(true)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalPhoneQueuePlayerActions provides PhoneQueuePlayerActions(identity, true,
                    { calls += "like" }, { calls += "more" })) {
                    Column(Modifier.requiredSize(393.dp, 740.dp)) {
                        PhoneQueueDragStrip()
                        PhoneQueueHeader(current, "3 songs • 12:00 • 11:45 remaining", "Local", infinite, false, true, false,
                            QueueRepeatMode.OFF, true, true, { _, m -> Box(m) },
                            { infinite = it; calls += "infinite" }, { calls += "shuffle" }, { calls += "repeat" }, { calls += "lock" },
                            { calls += "jump" }, { calls += "save" }, { calls += "clear" })
                    }
                }
            }
        }
        onNodeWithText("Playing from Local").assertIsDisplayed()
        onNodeWithText("Continue Playing").assertIsDisplayed()
        onNodeWithTag("queue-infinite").assertIsOn().performClick()
        onNodeWithTag("queue-infinite").assertIsOff()
        for (action in listOf("shuffle", "repeat", "lock", "jump", "save", "clear")) onNodeWithTag("queue-$action").performClick()
        onNodeWithTag("queue-current-like").performClick()
        onNodeWithTag("queue-current-more").performClick()
        runOnIdle {
            assertEquals(listOf("infinite", "shuffle", "repeat", "lock", "jump", "save", "clear", "like", "more"), calls)
            identity = "track@2"
        }
        onNodeWithTag("queue-current-like").assertIsNotEnabled()
        onNodeWithTag("queue-current-more").assertIsNotEnabled()
    }
}
