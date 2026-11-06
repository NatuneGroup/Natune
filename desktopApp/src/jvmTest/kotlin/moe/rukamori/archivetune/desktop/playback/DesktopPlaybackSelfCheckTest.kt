package moe.rukamori.archivetune.desktop.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.desktop.potoken.DesktopAttestationStatus

class DesktopPlaybackSelfCheckTest {
    private val logged = mutableListOf<String>()
    private val reported = mutableListOf<String>()

    private fun prewarm(
        present: Boolean = true,
        failWith: String? = null,
        headless: Boolean = false,
    ): DesktopEnginePrewarm = DesktopEnginePrewarm(
        libraryPresent = { present },
        load = { failWith?.let { throw IllegalStateException(it) } ?: Unit },
        dispatcher = Dispatchers.Unconfined,
        headless = { headless },
    )

    private fun check(
        engine: DesktopEnginePrewarm,
        output: String? = null,
        attestation: DesktopAttestationStatus = DesktopAttestationStatus.Ready("test"),
    ): DesktopPlaybackSelfCheck = DesktopPlaybackSelfCheck(
        engine = engine,
        probeOutput = { output },
        attestation = { attestation },
        log = { logged += it },
        report = { reported += it },
        engineTimeoutMs = 1_000L,
    )

    @Test
    fun aReadyHostReportsNothing() = runTest {
        val health = check(prewarm()).run()

        assertTrue(health.audible)
        assertTrue(health.blockers.isEmpty())
        assertTrue(reported.isEmpty())
        assertEquals(listOf("playback self-check: engine, output device and attestation are ready"), logged)
    }

    @Test
    fun anAbsentEngineIsNamedWithoutTouchingNativeCode() = runTest {
        // probeOutput must not be reached: the engine library is what provides it.
        val health = DesktopPlaybackSelfCheck(
            engine = prewarm(present = false),
            probeOutput = { error("the output probe must not run without an engine") },
            attestation = { error("attestation must not run without an engine") },
            log = { logged += it },
            report = { reported += it },
        ).run()

        assertFalse(health.audible)
        assertEquals(listOf(DesktopPlaybackBlocker.EngineMissing::class), health.blockers.map { it::class })
        assertEquals(1, reported.size)
        assertTrue(reported.single().contains("no playback engine"))
        assertTrue(logged.single().startsWith("playback self-check: EngineMissing"))
    }

    @Test
    fun aFailedLoadCarriesTheLoaderReason() = runTest {
        val health = check(prewarm(failWith = "libarchivetune_engine.so: undefined symbol")).run()

        val blocker = health.blockers.single() as DesktopPlaybackBlocker.EngineFailed
        assertEquals("libarchivetune_engine.so: undefined symbol", blocker.detail)
        assertTrue(blocker.message.contains("undefined symbol"))
    }

    @Test
    fun theDeviceRefusalIsTheUserMessage() = runTest {
        val refusal = "no audio output backend is available: the silent null backend was refused."
        val health = check(prewarm(), output = refusal).run()

        val blocker = health.blockers.single() as DesktopPlaybackBlocker.OutputUnavailable
        assertEquals(refusal, blocker.detail)
        assertEquals("Natune cannot play audio: $refusal", blocker.message)
        assertEquals(blocker.message, reported.single())
    }

    @Test
    fun attestationIsReportedOnlyWhenTheEngineAndOutputAreUsable() = runTest {
        val unavailable = DesktopAttestationStatus.Unavailable(
            "the PO token helper is switched off in settings, so YouTube requests carry no attestation token",
        )

        // A dead output device is the louder cause: it silences every source, not only YouTube.
        val outputFirst = check(prewarm(), output = "no device", attestation = unavailable).run()
        assertEquals(listOf(DesktopPlaybackBlocker.OutputUnavailable::class), outputFirst.blockers.map { it::class })

        val attestationOnly = check(prewarm(), attestation = unavailable).run()
        val blocker = attestationOnly.blockers.single() as DesktopPlaybackBlocker.AttestationUnavailable
        assertEquals(unavailable.detail, blocker.detail)
        assertTrue(blocker.message.startsWith("YouTube playback may be refused"))
    }

    @Test
    fun aHeadlessProcessIsNotProbedAndReportsNoBlocker() = runTest {
        val health = check(prewarm(headless = true), output = error("a headless host has no device to probe")).run()

        assertFalse(health.audible)
        assertTrue(health.blockers.isEmpty())
        assertTrue(reported.isEmpty())
        assertTrue(logged.isEmpty())
    }
}
