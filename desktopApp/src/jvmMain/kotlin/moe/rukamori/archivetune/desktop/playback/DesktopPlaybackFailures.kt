package moe.rukamori.archivetune.desktop.playback

import moe.rukamori.archivetune.desktop.DesktopDiagnosticLog
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailureReason
import moe.rukamori.archivetune.shared.domain.playback.PlaybackLocator
import moe.rukamori.archivetune.shared.domain.playback.PlaybackRequest

/**
 * A clear, self-contained reason a JVM-only test can assert without loading Compose string resources. The host passes
 * the localized `desktop_strings.xml` value instead; neither carries a path, a URL or a credential.
 */
internal const val DEFAULT_COMMAND_FAILURE_TEXT =
    "Playback could not start: the audio engine stopped before this command could be applied. Check the " +
        "audio output on this computer, and reinstall Natune if the problem continues."

/**
 * Why [locator] would not open. A prepared view that is spent, expired or from another host is an open failure,
 * because the remedy is to prepare the occurrence again. Any other locator is one this engine does not take.
 */
internal fun refusalOf(locator: PlaybackLocator): Pair<PlaybackFailureReason, String> =
    if (locator is PlaybackLocator.PreparedPlaintext) {
        PlaybackFailureReason.OPEN_FAILED to
            "the prepared audio is spent, expired or not from this host; prepare it again"
    } else {
        PlaybackFailureReason.UNSUPPORTED_LOCATOR to "the engine cannot open this kind of source"
    }

/**
 * The user-visible text for a failed engine create. A raw [error] message (the native loader's own text) can name an
 * absolute filesystem path, so it is written only to the diagnostic log; the user gets the curated resource string.
 * Injectable [logDetail] keeps the split testable without installing the rotating log.
 */
internal fun engineCreateFailureMessage(
    error: Throwable,
    logDetail: (String) -> Unit = { DesktopDiagnosticLog.playbackTier("playback engine create failed: $it") },
): String {
    logDetail("${error.javaClass.simpleName}: ${error.message.orEmpty()}")
    return DesktopStrings["playback_failed"]
}

/**
 * Runs one command on the playback thread. A `ScheduledThreadPoolExecutor` swallows anything a task throws, so a
 * native [LinkageError], a rejected source or a failed DSP stage would drop the play action with no snapshot, no
 * `PlaybackFailure` and no user-visible error. This turns any non-fatal throw into one failure for the affected
 * occurrence (the command's own request, or the active session's), named by [failureText] and routed through
 * [publishFailure]; the caller always reschedules the poll afterwards. Fatal JVM errors are returned to the executor
 * rather than reported as a recoverable playback failure. The diagnostic line carries a class name only.
 */
internal fun guardedPlaybackCommand(
    request: PlaybackRequest?,
    failureText: () -> String,
    currentRequest: () -> PlaybackRequest?,
    publishFailure: (PlaybackRequest, PlaybackFailureReason, String) -> Unit,
    task: () -> Unit,
) {
    try {
        task()
    } catch (error: VirtualMachineError) {
        throw error
    } catch (error: Throwable) {
        System.err.println("playback command dropped: ${error.javaClass.simpleName}")
        val failed = request ?: currentRequest()
        if (failed != null) publishFailure(failed, PlaybackFailureReason.OUTPUT_FAILED, failureText())
    }
}
