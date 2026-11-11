package moe.rukamori.archivetune.shared.ui.queue

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val PhoneQueueDismissDistance = 48.dp

// iOS has no hardware back button, so the drag strip is the way from the queue back to the artwork.
internal fun Modifier.phoneQueueClose(label: String, onClose: () -> Unit): Modifier =
    semantics { contentDescription = label }
        .clickable(onClickLabel = label, role = Role.Button, onClick = onClose)
        .phoneQueueDismissOnDrag(onClose)

// Headers keep taps for their own controls, so only a deliberate downward drag dismisses them.
internal fun Modifier.phoneQueueDismissOnDrag(onClose: () -> Unit): Modifier =
    pointerInput(onClose) {
        var distance = 0f
        detectVerticalDragGestures(
            onDragStart = { distance = 0f },
            onVerticalDrag = { change, amount -> change.consume(); distance += amount },
            onDragEnd = { if (distance > PhoneQueueDismissDistance.toPx()) onClose() },
            onDragCancel = { distance = 0f },
        )
    }
