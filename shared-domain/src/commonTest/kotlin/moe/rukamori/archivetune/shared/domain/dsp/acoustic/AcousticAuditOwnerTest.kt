package moe.rukamori.archivetune.shared.domain.dsp.acoustic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class AcousticRunTest {
    private fun run(
        active: Boolean = true,
        expired: Boolean = false,
        report: (Float) -> Unit = {},
    ) = AcousticRun(isActive = { active }, deadlineExceeded = { expired }, report = report)

    @Test
    fun cancelledRunStopsAtTheNextCheck() {
        val run = run(active = false)
        try {
            run.check()
        } catch (_: CancellationException) {
            return
        }
        throw AssertionError("A cancelled run must not continue")
    }

    @Test
    fun cancellationWinsOverAnExpiredDeadline() {
        val run = run(active = false, expired = true)
        try {
            run.check()
        } catch (_: CancellationException) {
            return
        }
        throw AssertionError("Cancellation must outrank the deadline")
    }

    @Test
    fun expiredRunReportsTheDeadlineCode() {
        val run = run(expired = true)
        try {
            run.check()
        } catch (error: AcousticUnavailable) {
            assertEquals("deadline_exceeded", error.code)
            return
        }
        throw AssertionError("An expired run must not continue")
    }

    @Test
    fun progressIsClampedAndForwarded() {
        val seen = mutableListOf<Float>()
        val run = run(report = { seen += it })
        run.progress(2f)
        run.progress(-1f)
        run.progress(0.25f)
        assertEquals(listOf(1f, 0f, 0.25f), seen)
    }
}

class AcousticAuditOwnerTest {
    private val target = AcousticAuditTarget(
        mediaId = "media-B",
        title = "Selected",
        artists = "Artist",
        requestKey = "media-B",
    )

    private fun decoded(
        sourceLabel: String = "TEST",
        requestKey: String = "media-B",
        occurrenceKey: String = "media-B",
        frames: Int = 2048,
    ) = AcousticWindow.Decoded(
        pcm = AcousticPcm(FloatArray(frames), 44_100, 1, 0L, frames, truncated = false),
        sourceLabel = sourceLabel,
        advertisedSampleRate = 44_100,
        claimedLossless = true,
        requestKey = requestKey,
        occurrenceKey = occurrenceKey,
    )

    private fun ownerOf(
        scope: CoroutineScope,
        verifySource: (suspend (AcousticWindow.Decoded) -> String?)? = null,
        decode: suspend (AcousticAuditTarget, AcousticRun) -> AcousticWindow,
    ): AcousticAuditOwner = AcousticAuditOwner(scope, verifySource, decode)

    @Test
    fun nothingRunsBeforeTheUserAsks() = runTest {
        var decodes = 0
        val owner = ownerOf(backgroundScope) { _, _ ->
            decodes++
            decoded()
        }
        advanceUntilIdle()
        assertEquals(AcousticAuditStatus.IDLE, owner.state.value.status)
        assertEquals(0, decodes)
        owner.start(target)
        advanceUntilIdle()
        assertEquals(AcousticAuditStatus.COMPLETE, owner.state.value.status)
        assertEquals(1, decodes)
        assertEquals("TEST", owner.state.value.result?.sourceLabel)
    }

    @Test
    fun decodeProgressPublishesTheDecodingFraction() = runTest {
        lateinit var owner: AcousticAuditOwner
        val observed = mutableListOf<AcousticAuditState>()
        owner = ownerOf(backgroundScope) { _, run ->
            run.progress(0.2f)
            observed += owner.state.value
            decoded()
        }
        owner.start(target)
        advanceUntilIdle()
        // A blocking probe reports its decode fraction, so the surface shows decoding rather than a
        // frozen resolving state for the whole read.
        assertEquals(AcousticAuditStatus.DECODING, observed.single().status)
        assertEquals(0.2f, observed.single().progress!!, 1e-6f)
        assertEquals(AcousticAuditStatus.COMPLETE, owner.state.value.status)
    }

    @Test
    fun aRefusedSourceVerdictReplacesTheFinishedResult() = runTest {
        val owner = ownerOf(backgroundScope, verifySource = { "source_changed" }) { _, _ -> decoded() }
        owner.start(target)
        advanceUntilIdle()
        assertEquals(AcousticAuditStatus.UNAVAILABLE, owner.state.value.status)
        assertEquals("source_changed", owner.state.value.message)
        assertNull(owner.state.value.result)
    }

    @Test
    fun aVerifiedResultPublishes() = runTest {
        var verified = 0
        val owner = ownerOf(backgroundScope, verifySource = { verified++; null }) { _, _ -> decoded() }
        owner.start(target)
        advanceUntilIdle()
        assertEquals(1, verified)
        assertEquals(AcousticAuditStatus.COMPLETE, owner.state.value.status)
        assertEquals("media-B", owner.state.value.result?.mediaId)
    }

    @Test
    fun aTargetWithoutARequestKeyNeverBindsAResult() = runTest {
        val owner = ownerOf(backgroundScope) { _, _ -> decoded() }
        owner.start(AcousticAuditTarget("media-B", "Selected"))
        advanceUntilIdle()
        assertEquals(AcousticAuditStatus.UNAVAILABLE, owner.state.value.status)
        assertEquals("current_source_unavailable", owner.state.value.message)
        assertNull(owner.state.value.result)
    }

    @Test
    fun aWindowDecodedForAnotherRequestNeverBinds() = runTest {
        val owner = ownerOf(backgroundScope) { _, _ ->
            decoded(requestKey = "media-C#4", occurrenceKey = "media-C")
        }
        owner.start(target)
        advanceUntilIdle()
        assertEquals(AcousticAuditStatus.UNAVAILABLE, owner.state.value.status)
        assertEquals("current_source_unavailable", owner.state.value.message)
        assertNull(owner.state.value.result)
    }

    @Test
    fun cancelDropsTheFinishedResultAndPublishesCancelled() = runTest {
        val owner = ownerOf(backgroundScope) { _, _ -> decoded() }
        owner.start(target)
        advanceUntilIdle()
        assertEquals(AcousticAuditStatus.COMPLETE, owner.state.value.status)
        owner.cancel()
        advanceUntilIdle()
        assertEquals(AcousticAuditStatus.CANCELLED, owner.state.value.status)
        assertNull(owner.state.value.result)
    }

    @Test
    fun aSupersededRunCannotOverwriteTheNextResult() = runTest {
        val gate = CompletableDeferred<Unit>()
        var decodes = 0
        val owner = ownerOf(backgroundScope) { _, _ ->
            val label = if (++decodes == 1) {
                gate.await()
                "SUPERSEDED"
            } else "CURRENT"
            decoded(sourceLabel = label)
        }
        owner.start(target)
        runCurrent()
        owner.cancel()
        runCurrent()
        owner.start(target)
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(AcousticAuditStatus.COMPLETE, owner.state.value.status)
        assertEquals("CURRENT", owner.state.value.result?.sourceLabel)
    }
}
