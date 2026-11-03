package moe.rukamori.archivetune.shared.ui.ios

import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.data.platform.ios.IosAcousticProbe
import moe.rukamori.archivetune.shared.domain.dsp.acoustic.AcousticAuditTarget
import moe.rukamori.archivetune.shared.domain.dsp.acoustic.AcousticWindow

/**
 * The acoustic audit wiring of [IosSessionGraph]: the probe decode bound to the loaded request, the
 * occurrence#attempt key, the dialog-key watcher and the menu entry point. Extracted so the session
 * graph stays under the file budget; the state machine itself lives in shared-domain.
 */

/** Decodes a bounded window of the loaded request's source, refusing targets that are not it. */
internal suspend fun IosSessionGraph.acousticWindow(
    target: AcousticAuditTarget,
    checkActive: () -> Unit,
): AcousticWindow {
    checkActive()
    val request = playbackPort.currentRequest()
    return when {
        request == null -> AcousticWindow.Refused("no_current_source")
        request.mediaId != target.mediaId -> AcousticWindow.Refused("current_source_unavailable")
        else -> IosAcousticProbe.window(
            request.locator,
            requestKey = "${request.occurrenceId}#${request.attemptId ?: -1L}",
            occurrenceKey = request.occurrenceId,
        )
    }
}

/**
 * The loaded request's audit binding token: occurrenceId plus the load-attempt id, so a source
 * fallback inside the same occurrence invalidates targets and results bound to the older attempt.
 * Empty while nothing is loaded.
 */
internal suspend fun IosSessionGraph.acousticRequestKey(): String =
    playbackPort.currentRequest()
        ?.let { "${it.occurrenceId}#${it.attemptId ?: -1L}" }
        .orEmpty()

/**
 * Opens the audit surface with [target] bound to the request loaded right now — menu callers cannot
 * read the attempt themselves because the request only exists inside the port.
 */
internal fun IosSessionGraph.openAcousticAudit(target: AcousticAuditTarget) {
    scope.launch {
        acoustic.openDialog(target.copy(requestKey = acousticRequestKey()))
    }
}

/**
 * Rebinds an open audit dialog to the live request key on every snapshot: a source fallback inside
 * the same occurrence retires the key the dialog opened with, so a result decoded under the retired
 * key stops matching instead of displaying as stale evidence.
 */
internal fun IosSessionGraph.watchAcousticBinding() {
    scope.launch {
        player.snapshot.collectLatest {
            if (acoustic.dialogTarget.value != null) {
                acoustic.rebindDialogKey(acousticRequestKey())
            }
        }
    }
}
