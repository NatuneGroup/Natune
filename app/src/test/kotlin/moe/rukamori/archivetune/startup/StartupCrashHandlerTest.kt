package moe.rukamori.archivetune.startup

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupCrashHandlerTest {
    @Test fun earlyCrashIsSavedBeforeChainingToTheRuntime() {
        val events = mutableListOf<String>()
        val handler = StartupCrashHandler(
            capture = { _, _ -> events += "capture" },
            previous = { _, _ -> events += "runtime" },
            terminate = { events += "terminate" },
        )
        handler.uncaughtException(Thread.currentThread(), RuntimeException())
        assertEquals(listOf("capture", "runtime", "terminate"), events)
    }

    @Test fun laterDebugAndFeedbackHandlerReplacesTheRuntimeDelegateNotTheRecorder() {
        val events = mutableListOf<String>()
        val handler = StartupCrashHandler(
            capture = { _, _ -> events += "capture" },
            previous = { _, _ -> events += "runtime" },
            terminate = { events += "terminate" },
        )
        handler.applicationHandler = Thread.UncaughtExceptionHandler { _, _ -> events += "debug-feedback" }
        handler.uncaughtException(Thread.currentThread(), RuntimeException())
        assertEquals(listOf("capture", "debug-feedback", "terminate"), events)
    }

    @Test fun captureFailureStillChainsAndReentrancyCannotHandleTheSameCrashTwice() {
        val events = mutableListOf<String>()
        lateinit var handler: StartupCrashHandler
        handler = StartupCrashHandler(
            capture = { _, _ -> events += "capture"; throw java.io.IOException() },
            previous = { thread, error ->
                events += "runtime"
                handler.uncaughtException(thread, error)
            },
            terminate = { events += "terminate" },
        )
        handler.uncaughtException(Thread.currentThread(), RuntimeException())
        assertEquals(listOf("capture", "runtime", "terminate"), events)
    }
}
