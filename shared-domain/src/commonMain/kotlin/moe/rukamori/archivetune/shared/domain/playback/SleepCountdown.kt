package moe.rukamori.archivetune.shared.domain.playback

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Milliseconds left until a sleep timer fires at [deadlineEpochMs], or 0 when no timed countdown runs.
 *
 * The timer's owner keeps the deadline. A sheet collects this flow only while it is composed, so a hidden sheet costs
 * nothing. The flow wakes once per displayed second and ends at zero: the owner clears the deadline when the timer
 * fires, and a sheet that sees a new deadline restarts the collection rather than sharing one loop.
 */
fun sleepCountdownRemainingMs(
    deadlineEpochMs: Long,
    nowEpochMs: () -> Long,
): Flow<Long> =
    flow {
        while (true) {
            val remainingMs =
                if (deadlineEpochMs > 0L) (deadlineEpochMs - nowEpochMs()).coerceAtLeast(0L) else 0L
            emit(remainingMs)
            if (remainingMs == 0L) return@flow
            delay(waitUntilNextDisplayedSecondMs(remainingMs))
        }
    }

/**
 * Waits for the next whole displayed second, so the readout changes exactly at that instant. A remainder that already
 * sits on a whole second waits one full second.
 */
private fun waitUntilNextDisplayedSecondMs(remainingMs: Long): Long {
    val intoSecond = remainingMs % 1_000L
    return if (intoSecond > 0L) intoSecond else 1_000L
}
