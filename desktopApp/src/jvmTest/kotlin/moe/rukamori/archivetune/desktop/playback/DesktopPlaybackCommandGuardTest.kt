package moe.rukamori.archivetune.desktop.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailureReason
import moe.rukamori.archivetune.shared.domain.playback.PlaybackLocator
import moe.rukamori.archivetune.shared.domain.playback.PlaybackRequest

/**
 * The playback executor must never swallow a command. A `ScheduledThreadPoolExecutor` discards
 * anything a task throws, so a native LinkageError, a rejected source or a failed DSP stage used to
 * drop the play action with no snapshot, no failure event and no user-visible error. These tests
 * need no native library: the port never opens a device, so they run in every unit-test workflow,
 * including the one that excludes the decoding suites.
 */
class DesktopPlaybackCommandGuardTest {
    private fun portWith(text: String = COMMAND_FAILURE_TEXT): DesktopPlaybackPort =
        DesktopPlaybackPort(
            backend = EngineBackend.NULL,
            commandFailureText = { text },
        )

    /**
     * An arbitrary absolute path: no fixture resource is loaded, so this test needs no native
     * closure.
     */
    private fun request(occurrenceId: String): PlaybackRequest =
        PlaybackRequest(
            occurrenceId,
            "media-$occurrenceId",
            PlaybackLocator.LocalFile("/unused/$occurrenceId.wav"),
        )

    @Test
    fun aLinkageErrorInACommandPublishesOneFailureForItsOccurrence() {
        val port = portWith()
        FailureRecorder(port).use { failures ->
            val req = request("occ-guard")
            try {
                // This is the throw a build whose JNI symbol is missing raises. Before the guard it
                // vanished.
                port.submitTask(req) { throw UnsatisfiedLinkError("no JNI symbol") }

                val failure = failures.awaitFirst()
                assertEquals("occ-guard", failure.occurrenceId)
                assertEquals(PlaybackFailureReason.OUTPUT_FAILED, failure.reason)
                assertEquals(COMMAND_FAILURE_TEXT, failure.message)
                assertEquals(req.attemptId, failure.attemptId)
                assertEquals(
                    1,
                    failures.all.size,
                    "one dropped command reports exactly one failure",
                )
            } finally {
                port.close()
            }
        }
    }

    @Test
    fun aRuntimeExceptionAndANonFatalErrorAreReportedToo() {
        val port = portWith()
        FailureRecorder(port).use { failures ->
            try {
                port.submitTask(request("occ-runtime")) {
                    throw IllegalStateException("stage rejected")
                }
                // An AssertionError is neither an Exception nor a LinkageError; the guard still
                // catches it.
                port.submitTask(request("occ-error")) {
                    throw AssertionError("native invariant")
                }

                val reported = failures.awaitCount(2)
                assertEquals(listOf("occ-runtime", "occ-error"), reported.map { it.occurrenceId })
                assertTrue(reported.all { it.reason == PlaybackFailureReason.OUTPUT_FAILED })
                assertTrue(reported.all { it.message == COMMAND_FAILURE_TEXT })
            } finally {
                port.close()
            }
        }
    }

    @Test
    fun theExecutorSurvivesADroppedCommand() {
        val port = portWith()
        FailureRecorder(port).use { failures ->
            try {
                port.submitTask(request("occ-first")) {
                    throw UnsatisfiedLinkError("first failure")
                }
                failures.awaitFirst()
                // If the poll loop or the executor thread died, this second command would never
                // run. It must publish
                // its own failure, proving the port still processes commands after a thrown task.
                port.submitTask(request("occ-second")) {
                    throw IllegalStateException("second")
                }
                val reported = failures.awaitCount(2)
                assertEquals(listOf("occ-first", "occ-second"), reported.map { it.occurrenceId })
            } finally {
                port.close()
            }
        }
    }

    @Test
    fun aCommandWithNoOccurrenceIsHandledWithoutAnException() {
        val port = portWith()
        FailureRecorder(port).use { failures ->
            try {
                // A task with no request and no active session has no occurrence to name; it is
                // logged, not turned
                // into a bogus failure, and the executor stays usable for the next command.
                port.submitTask { throw UnsatisfiedLinkError("no session") }
                Thread.sleep(150L)
                assertEquals(emptyList(), failures.all)

                val req = request("occ-after")
                port.submitTask(req) { throw IllegalStateException("with session") }
                assertEquals("occ-after", failures.awaitFirst().occurrenceId)
            } finally {
                port.close()
            }
        }
    }

    @Test
    fun engineCreateMessagesKeepTheLoaderReasonButNeverAPath() {
        val loader = IllegalStateException("Desktop playback is unavailable: reinstall Natune")
        assertEquals("Desktop playback is unavailable: reinstall Natune", engineCreateFailureMessage(loader))

        // A raw LinkageError message can name a filesystem path; the user-visible text must not echo it.
        val linkage = UnsatisfiedLinkError("/app/resources/native/lib.so: undefined symbol")
        val message = engineCreateFailureMessage(linkage)
        assertTrue(message.contains("UnsatisfiedLinkError"))
        assertFalse(message.contains("/app/resources"), "a native path must never reach the user-visible text")
    }

    private companion object {
        const val COMMAND_FAILURE_TEXT = "Playback could not start (test resource text)."
    }
}
