package moe.rukamori.archivetune.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeardownQueuePersistenceTest {
    @Test
    fun persistentQueueOff_skipsTheLastChanceSave() {
        assertFalse(shouldSave(enabled = false, items = 500))
    }

    @Test
    fun emptyQueue_skipsTheLastChanceSave() {
        assertFalse(shouldSave(enabled = true, items = 0))
    }

    @Test
    fun enabledQueue_isSaved() {
        assertTrue(shouldSave(enabled = true, items = 1))
    }

    @Test
    fun destroyBudget_doesNotWaitForAnEmptyQueue() {
        assertEquals(0L, TeardownQueuePersistence.destroyWaitMillis(0))
    }

    @Test
    fun destroyBudget_growsWithTheQueue() {
        val oneItem = TeardownQueuePersistence.destroyWaitMillis(1)
        val hundredItems = TeardownQueuePersistence.destroyWaitMillis(100)
        assertEquals(TeardownQueuePersistence.DESTROY_WAIT_BASE_MS + 1L, oneItem)
        assertEquals(TeardownQueuePersistence.DESTROY_WAIT_BASE_MS + 100L, hundredItems)
        assertTrue(hundredItems > oneItem)
    }

    @Test
    fun destroyBudget_isCappedHoweverLongTheQueueIs() {
        val budget = TeardownQueuePersistence.destroyWaitMillis(Int.MAX_VALUE)
        assertEquals(TeardownQueuePersistence.DESTROY_WAIT_MAX_MS, budget)
    }

    @Test
    fun finishedWrite_reportsDoneWithoutWaiting() {
        val write = scope.launch {}
        runBlocking { write.join() }
        assertTrue(TeardownQueuePersistence.blockUntilFinished(write, timeoutMs = 0L))
    }

    @Test
    fun runningWrite_withoutBudget_returnsAtOnce() {
        val release = CompletableDeferred<Unit>()
        val write = scope.launch { release.await() }
        assertFalse(TeardownQueuePersistence.blockUntilFinished(write, timeoutMs = 0L))
        assertTrue(write.isActive)
        release.complete(Unit)
    }

    @Test
    fun missedBudget_leavesTheWriteRunning() {
        val release = CompletableDeferred<Unit>()
        val write = scope.launch { release.await() }
        assertFalse(TeardownQueuePersistence.blockUntilFinished(write, timeoutMs = 20L))
        assertTrue(write.isActive)
        release.complete(Unit)
        assertTrue(TeardownQueuePersistence.blockUntilFinished(write, timeoutMs = 2_000L))
    }

    private fun shouldSave(
        enabled: Boolean,
        items: Int,
    ): Boolean = TeardownQueuePersistence.shouldSave(enabled, items)

    private companion object {
        // Real dispatchers on purpose: blockUntilFinished is the blocking bridge to a write that
        // has to keep running on its own dispatcher, which virtual time cannot model.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
