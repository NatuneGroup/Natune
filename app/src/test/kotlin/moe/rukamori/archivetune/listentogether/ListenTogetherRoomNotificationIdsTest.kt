package moe.rukamori.archivetune.listentogether

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherRoomNotificationIdsTest {
    @Test
    fun aRecordedPromptCanBeDismissedExactlyOnce() {
        val ids = ListenTogetherRoomNotificationIds()
        ids.trackJoinRequest("guest-1", 41)
        assertEquals(41, ids.forgetJoinRequest("guest-1"))
        assertNull(ids.forgetJoinRequest("guest-1"))
    }

    @Test
    fun joinRequestsAndSuggestionsAreTrackedIndependently() {
        val ids = ListenTogetherRoomNotificationIds()
        ids.trackJoinRequest("guest-1", 41)
        ids.trackSuggestion("suggestion-1", 42)

        assertEquals(41, ids.forgetJoinRequest("guest-1"))
        assertEquals(42, ids.forgetSuggestion("suggestion-1"))
        assertNull(ids.forgetJoinRequest("suggestion-1"))
    }

    @Test
    fun drainingAClosedRoomForgetsEveryPromptOfThatRoom() {
        val ids = ListenTogetherRoomNotificationIds()
        ids.trackJoinRequest("guest-1", 41)
        ids.trackSuggestion("suggestion-1", 42)

        assertEquals(setOf(41, 42), ids.drain().toSet())
        assertNull(ids.forgetJoinRequest("guest-1"))
        assertNull(ids.forgetSuggestion("suggestion-1"))
        assertTrue(ids.drain().isEmpty())
    }

    @Test
    fun promptsRecordedAfterTheDrainBelongToTheNextRoom() {
        val ids = ListenTogetherRoomNotificationIds()
        ids.trackJoinRequest("guest-1", 41)
        assertEquals(listOf(41), ids.drain())

        ids.trackJoinRequest("guest-1", 73)
        assertEquals(73, ids.forgetJoinRequest("guest-1"))
        assertTrue(ids.drain().isEmpty())
    }

    @Test
    fun closingTheRoomCancelsEveryTrackedPromptExactlyOnce() {
        val ids = ListenTogetherRoomNotificationIds()
        ids.trackJoinRequest("guest-1", 41)
        ids.trackSuggestion("suggestion-1", 42)
        val cancelled = mutableListOf<Int>()

        ids.drainAndCancel { notificationId -> cancelled += notificationId }

        assertEquals(setOf(41, 42), cancelled.toSet())
        assertEquals(2, cancelled.size)
        assertTrue(ids.drain().isEmpty())
    }

    @Test
    fun socketWritesAndUiDismissalsKeepEverySurvivingIdCorrect() {
        val ids = ListenTogetherRoomNotificationIds()
        val workers = 8
        val perWorker = 200
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val threads =
            (0 until workers).map { worker ->
                Thread {
                    try {
                        start.await()
                        repeat(perWorker) { index ->
                            ids.trackJoinRequest("guest-$worker-$index", worker * 1_000 + index)
                            ids.trackSuggestion("suggestion-$worker-$index", worker * 1_000 + index)
                            // A UI or notification-action thread dismisses a neighbouring
                            // worker's prompt while that worker is still writing its own.
                            if (index % 2 == 0) {
                                ids.forgetJoinRequest("guest-${(worker + 1) % workers}-$index")
                                ids.forgetSuggestion("suggestion-${(worker + 1) % workers}-$index")
                            }
                        }
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    }
                }.also { it.start() }
            }
        start.countDown()
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(30)) }

        assertNull(failure.get())
        threads.forEach { thread -> assertTrue("worker did not finish", !thread.isAlive) }

        // A surviving key can only carry the id its own worker wrote, never another
        // key's id or a torn entry from the concurrent removals.
        for (worker in 0 until workers) {
            for (index in 0 until perWorker) {
                val expected = worker * 1_000 + index
                val trackedJoinRequest = ids.forgetJoinRequest("guest-$worker-$index")
                if (trackedJoinRequest != null) assertEquals(expected, trackedJoinRequest)
                val trackedSuggestion = ids.forgetSuggestion("suggestion-$worker-$index")
                if (trackedSuggestion != null) assertEquals(expected, trackedSuggestion)
            }
        }
        assertTrue(ids.drain().isEmpty())
    }
}
