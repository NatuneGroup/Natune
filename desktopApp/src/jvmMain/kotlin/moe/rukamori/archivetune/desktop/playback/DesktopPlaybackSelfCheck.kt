package moe.rukamori.archivetune.desktop.playback

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.desktop.DesktopDiagnosticLog
import moe.rukamori.archivetune.desktop.potoken.DesktopAttestationStatus

/**
 * One reason this host cannot make a sound, in the order the startup check reads them. The three the desktop actually
 * fails on are the engine library, the output device and the attestation path; each was silent before, because the
 * player only reported the first play.
 */
internal sealed interface DesktopPlaybackBlocker {
    /** What the user is told: one line naming the cause, and the remedy where the user has one. */
    val message: String

    /** What the local diagnostic log records, sanitized and never a credential. */
    val detail: String

    /** The build carries no `libarchivetune_engine`, so no decoder or output exists at all. */
    data class EngineMissing(override val detail: String) : DesktopPlaybackBlocker {
        override val message: String =
            "Natune cannot play audio: this build has no playback engine. Reinstall Natune; a self-built copy needs " +
                "its native engine built and staged (desktopApp/native/scripts/build-native-deps.sh)."
    }

    /** The library is present but did not load or did not match this host. */
    data class EngineFailed(override val detail: String) : DesktopPlaybackBlocker {
        override val message: String =
            "Natune cannot play audio: the playback engine did not load. Reinstall Natune to repair this build's " +
                "native files. Detail: $detail"
    }

    /** The engine loaded, but miniaudio found no usable output backend and refused its silent null device. */
    data class OutputUnavailable(override val detail: String) : DesktopPlaybackBlocker {
        override val message: String = "Natune cannot play audio: $detail"
    }

    /** No PO token provider can mint, so YouTube requests go untokened and a bot-checked network refuses them. */
    data class AttestationUnavailable(override val detail: String) : DesktopPlaybackBlocker {
        override val message: String = "YouTube playback may be refused on this network: $detail"
    }
}

/** What the startup check found. [audible] is true only when no check refused. */
internal data class DesktopPlaybackHealth(
    val blockers: List<DesktopPlaybackBlocker> = emptyList(),
    val audible: Boolean = blockers.isEmpty(),
)

/**
 * Runs the desktop playback self-check once per process, right after the graph opens.
 *
 * The engine warm already answers whether the native library loaded; this adds the two questions it cannot: whether
 * miniaudio can open a real output device on this host, and whether any PO token provider can mint for the signed-in
 * state. A failure of any of the three used to surface only as the first press of play doing nothing, so a container
 * without a sound device, a package without the engine and a bot-checked network all looked the same. Each answer is
 * written to the local diagnostic log and the first ones are shown to the user.
 *
 * The collaborators are injectable so the order, the messages and the "nothing is reported when everything passes"
 * rule are tested without a native engine, a sound device or a network.
 */
internal class DesktopPlaybackSelfCheck(
    private val engine: DesktopEnginePrewarm = DesktopEnginePrewarm.process,
    private val probeOutput: () -> String? = {
        PlaybackEngineNative.probeOutputDevice(NativeTwoDeckMixer.DEFAULT_SAMPLE_RATE_HZ)
    },
    private val attestation: suspend () -> DesktopAttestationStatus = { DesktopAttestationStatus.Ready("no provider") },
    private val log: (String) -> Unit = { DesktopDiagnosticLog.playbackTier(it) },
    private val report: (String) -> Unit = {},
    /** The engine load is a `dlopen` of a few hundred milliseconds; a hung one must not block the check forever. */
    private val engineTimeoutMs: Long = ENGINE_TIMEOUT_MS,
) {
    suspend fun run(): DesktopPlaybackHealth {
        val blockers = mutableListOf<DesktopPlaybackBlocker>()
        when (val warmed = engineState()) {
            // A process without a window can play nothing, so there is no one to tell and nothing to probe.
            DesktopEnginePrewarmState.Headless -> return DesktopPlaybackHealth(blockers = emptyList(), audible = false)
            DesktopEnginePrewarmState.LibraryAbsent -> blockers += DesktopPlaybackBlocker.EngineMissing(
                "the engine library is not on disk (no $PATH_PROPERTY, no resources/native, no library path entry)",
            )
            is DesktopEnginePrewarmState.Failed -> blockers += DesktopPlaybackBlocker.EngineFailed(warmed.detail)
            DesktopEnginePrewarmState.Idle,
            DesktopEnginePrewarmState.Loading,
            DesktopEnginePrewarmState.Ready,
            -> Unit
        }
        // The device probe is only meaningful once the engine loaded: the call is native code.
        if (blockers.isEmpty()) {
            val refusal = try {
                probeOutput()
            } catch (failure: UnsatisfiedLinkError) {
                "the output probe is unavailable: ${failure.message}"
            } catch (failure: IllegalStateException) {
                failure.message ?: "the output probe was refused"
            }
            if (refusal != null) blockers += DesktopPlaybackBlocker.OutputUnavailable(refusal)
        }
        // Attestation cannot fail playback by itself: an untokened request only fails on a bot-checked network, so it
        // is reported after the two causes that always silence the host.
        if (blockers.isEmpty()) {
            val status = try {
                attestation()
            } catch (failure: IllegalStateException) {
                DesktopAttestationStatus.Unavailable(failure.message ?: "the attestation provider failed")
            }
            if (status is DesktopAttestationStatus.Unavailable) {
                blockers += DesktopPlaybackBlocker.AttestationUnavailable(status.detail)
            }
        }
        val health = DesktopPlaybackHealth(blockers = blockers)
        for (blocker in blockers) {
            log("playback self-check: ${blocker.javaClass.simpleName}: ${blocker.detail}")
            report(blocker.message)
        }
        if (blockers.isEmpty()) log("playback self-check: engine, output device and attestation are ready")
        return health
    }

    /** The warm's settled state, started here if the window has not started it yet. */
    private suspend fun engineState(): DesktopEnginePrewarmState {
        engine.start()
        val settled = withTimeoutOrNull(engineTimeoutMs) {
            engine.state.first { state ->
                state !is DesktopEnginePrewarmState.Idle && state !is DesktopEnginePrewarmState.Loading
            }
        }
        return settled ?: DesktopEnginePrewarmState.Failed(
            "the engine library did not finish loading within ${engineTimeoutMs / 1_000} s",
        )
    }

    private companion object {
        const val ENGINE_TIMEOUT_MS = 15_000L
        const val PATH_PROPERTY = PlaybackEngineLibrary.PATH_PROPERTY
    }
}
