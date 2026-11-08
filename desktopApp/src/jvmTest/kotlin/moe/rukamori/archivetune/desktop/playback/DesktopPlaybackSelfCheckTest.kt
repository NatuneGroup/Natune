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
    private val requestedKeys = mutableListOf<String>()

    // The production texts are Compose resources. Injecting their exact templates here keeps the assertions on the
    // user-visible text deterministic and lets the tests prove which resource key each blocker asks for.
    private val fakeStrings = mapOf(
        "playback_engine_missing" to
            "Natune cannot play audio: this build has no playback engine. Reinstall Natune.",
        "playback_engine_failed" to
            "Natune cannot play audio: the playback engine did not load. Reinstall Natune. Detail: {detail}",
        "playback_output_unavailable" to "Natune cannot play audio: {detail}",
        "playback_attestation_unavailable" to "YouTube playback may be refused on this network: {detail}",
    )

    private fun desktopStrings(key: String): String {
        requestedKeys += key
        return fakeStrings[key] ?: error("no desktop string resource for $key")
    }

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
        strings = ::desktopStrings,
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
            strings = ::desktopStrings,
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
        // A process without a window has nothing to play to, so neither probe may run: both are lambdas that fail
        // the test if the check calls them. (The helper above takes the probe's answer, not the probe.)
        val health =
            DesktopPlaybackSelfCheck(
                engine = prewarm(headless = true),
                probeOutput = { error("a headless host has no device to probe") },
                attestation = { error("a headless host has no provider to ask") },
                log = { logged += it },
                report = { reported += it },
                strings = ::desktopStrings,
                engineTimeoutMs = 1_000L,
            ).run()

        assertFalse(health.audible)
        assertTrue(health.blockers.isEmpty())
        assertTrue(reported.isEmpty())
        assertTrue(logged.isEmpty())
    }

    @Test
    fun everyBlockerReadsItsMessageFromAResourceKey() = runTest {
        // Engine missing names one key, a device refusal names another; neither message is an inline literal.
        DesktopPlaybackSelfCheck(
            engine = prewarm(present = false),
            probeOutput = { error("must not probe without an engine") },
            attestation = { error("must not attest without an engine") },
            log = { logged += it },
            report = { reported += it },
            strings = ::desktopStrings,
        ).run()
        assertTrue(requestedKeys.contains("playback_engine_missing"))
        assertTrue(reported.single().startsWith("Natune cannot play audio: this build has no playback engine"))

        requestedKeys.clear()
        reported.clear()
        check(prewarm(), output = "no sound card").run()
        assertTrue(requestedKeys.contains("playback_output_unavailable"))
        assertEquals("Natune cannot play audio: no sound card", reported.single())
    }

    @Test
    fun theFailedEngineMessageSubstitutesTheSanitizedDetail() = runTest {
        val health = check(prewarm(failWith = "libarchivetune_engine.so: undefined symbol")).run()

        assertTrue(requestedKeys.contains("playback_engine_failed"))
        val blocker = health.blockers.single() as DesktopPlaybackBlocker.EngineFailed
        assertEquals("libarchivetune_engine.so: undefined symbol", blocker.detail)
        assertTrue(blocker.message.endsWith("Detail: libarchivetune_engine.so: undefined symbol"))
    }

    @Test
    fun aNativeLinkageFailureInTheOutputProbeBecomesAVisibleBlocker() = runTest {
        // The probe can fail with a LinkageError when a JNI symbol is missing. It must produce a user-visible
        // blocker, not escape the self-check coroutine with no notice at all.
        val health = DesktopPlaybackSelfCheck(
            engine = prewarm(),
            probeOutput = { throw UnsatisfiedLinkError("no symbol") },
            log = { logged += it },
            report = { reported += it },
            strings = ::desktopStrings,
        ).run()

        val blocker = health.blockers.single() as DesktopPlaybackBlocker.OutputUnavailable
        assertTrue(blocker.detail.contains("the output probe is unavailable"))
        assertTrue(blocker.message.startsWith("Natune cannot play audio:"))
        assertEquals(blocker.message, reported.single())
    }
}
