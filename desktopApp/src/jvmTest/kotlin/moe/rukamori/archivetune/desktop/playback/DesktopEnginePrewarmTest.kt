package moe.rukamori.archivetune.desktop.playback

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class DesktopEnginePrewarmTest {
    @Test
    fun constructionReadsNoPathAndLoadsNothing() {
        val loads = AtomicInteger()

        val prewarm = prewarm(loads, present = true)

        assertEquals(DesktopEnginePrewarmState.Idle, prewarm.state.value)
        assertEquals(0, loads.get())
    }

    @Test
    fun aHostWithoutTheLibraryIsNotLoaded() {
        val loads = AtomicInteger()

        val prewarm = prewarm(loads, present = false).also { it.start() }

        assertEquals(DesktopEnginePrewarmState.LibraryAbsent, prewarm.state.value)
        assertEquals(0, loads.get())
    }

    @Test
    fun aHeadlessProcessIsNotLoadedEvenWhenTheLibraryIsPresent() {
        val loads = AtomicInteger()
        val prewarm = DesktopEnginePrewarm(
            libraryPresent = { true },
            load = { loads.incrementAndGet() },
            dispatcher = Dispatchers.Unconfined,
            headless = { true },
        )

        prewarm.start()

        assertEquals(DesktopEnginePrewarmState.Headless, prewarm.state.value)
        assertEquals(0, loads.get())
    }

    @Test
    fun theLoadLeavesTheCallingThreadAndReportsLoadingBeforeReady() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val prewarm = DesktopEnginePrewarm(
            libraryPresent = { true },
            load = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "the test did not release the load" }
            },
            dispatcher = Dispatchers.Default,
            headless = { false },
        )

        prewarm.start()

        assertTrue(entered.await(5, TimeUnit.SECONDS), "the warm must not run on the caller's thread")
        assertEquals(DesktopEnginePrewarmState.Loading, prewarm.state.value)
        release.countDown()
        assertEquals(DesktopEnginePrewarmState.Ready, awaitState(prewarm, DesktopEnginePrewarmState.Ready))
    }

    @Test
    fun aFailedLoadIsRecordedAndDoesNotEscape() {
        val prewarm = DesktopEnginePrewarm(
            libraryPresent = { true },
            load = { throw IllegalStateException("engine did not load") },
            dispatcher = Dispatchers.Unconfined,
            headless = { false },
        )

        prewarm.start()

        val failed = assertIs<DesktopEnginePrewarmState.Failed>(prewarm.state.value)
        assertEquals("engine did not load", failed.detail)
    }

    @Test
    fun startWarmsOnceAndDoesNotRetryAFailedLoad() {
        val loads = AtomicInteger()
        val prewarm = DesktopEnginePrewarm(
            libraryPresent = { true },
            load = {
                loads.incrementAndGet()
                throw IllegalStateException("engine did not load")
            },
            dispatcher = Dispatchers.Unconfined,
            headless = { false },
        )

        prewarm.start()
        prewarm.start()

        assertEquals(1, loads.get())
        assertIs<DesktopEnginePrewarmState.Failed>(prewarm.state.value)
    }

    @Test
    fun aSecondStartAfterReadyDoesNotLoadAgain() {
        val loads = AtomicInteger()
        val prewarm = prewarm(loads, present = true)

        prewarm.start()
        prewarm.start()

        assertEquals(1, loads.get())
        assertEquals(DesktopEnginePrewarmState.Ready, prewarm.state.value)
    }

    private fun prewarm(
        loads: AtomicInteger,
        present: Boolean,
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ): DesktopEnginePrewarm = DesktopEnginePrewarm(
        libraryPresent = { present },
        load = { loads.incrementAndGet() },
        dispatcher = dispatcher,
        headless = { false },
    )

    private fun awaitState(
        prewarm: DesktopEnginePrewarm,
        expected: DesktopEnginePrewarmState,
    ): DesktopEnginePrewarmState = runBlocking {
        withTimeout(5_000) { prewarm.state.first { it == expected } }
    }
}
