
package moe.rukamori.archivetune.ui.player

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

fun Modifier.consumeUnhandledPointerInput(): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                event.changes.forEach { pointerInputChange ->
                    if (!pointerInputChange.isConsumed) {
                        pointerInputChange.consume()
                    }
                }
            }
        }
    }
