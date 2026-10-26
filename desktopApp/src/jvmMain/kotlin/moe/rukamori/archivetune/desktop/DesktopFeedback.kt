package moe.rukamori.archivetune.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.ktor.client.HttpClient
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.data.feedback.FeedbackReviewOwner
import moe.rukamori.archivetune.shared.data.feedback.KtorFeedbackTransport
import moe.rukamori.archivetune.shared.domain.diagnostics.CrashReport
import moe.rukamori.archivetune.shared.domain.feedback.FeedbackDiagnostics
import moe.rukamori.archivetune.shared.domain.feedback.FeedbackDrafts
import moe.rukamori.archivetune.shared.domain.feedback.FeedbackReport
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailure
import moe.rukamori.archivetune.shared.ui.player.PlaybackErrorActions
import moe.rukamori.archivetune.shared.ui.player.PlaybackErrorDialog
import moe.rukamori.archivetune.shared.ui.screens.feedback.BoundFeedbackReviewDialog
import moe.rukamori.archivetune.shared.ui.screens.feedback.FeedbackReviewOwnerBinding
import moe.rukamori.archivetune.shared.ui.screens.diagnostics.CrashReportActions
import moe.rukamori.archivetune.shared.ui.screens.diagnostics.CrashReportDialog

internal fun desktopFeedbackApp(): FeedbackReport.AppIdentity =
    FeedbackReport.AppIdentity(
        versionName = DesktopBuildIdentity.VERSION_NAME,
        versionCode = 0,
        forkRevision = DesktopBuildIdentity.FORK_REVISION,
        forkBranch = DesktopBuildIdentity.FORK_BRANCH,
        variant = "desktop",
    )

internal fun desktopFeedbackDevice(): FeedbackReport.DeviceIdentity {
    val os = System.getProperty("os.name").orEmpty()
    return FeedbackReport.DeviceIdentity(
        manufacturer = os,
        model = System.getProperty("os.arch").orEmpty(),
        osRelease = "$os ${System.getProperty("os.version").orEmpty()} (Java ${System.getProperty("java.version").orEmpty()})",
        sdkInt = 0,
        platform = "Desktop",
    )
}

/** A playback failure the player stopped on, with the song it was for. */
internal data class DesktopPlaybackFailureNotice(
    val failure: PlaybackFailure,
    val title: String?,
    val provider: String?,
)

/**
 * The opt-in feedback service of the desktop host: the transport over the graph's client, the diagnostic log, the
 * stored crash report and the playback failure the player last stopped on. Review owners are made per window, on the
 * window's UI scope, because the shared owner mutates its draft on the dispatcher of the scope it is given.
 */
internal class DesktopFeedback(http: HttpClient, private val instanceDirectory: Path) {
    private val transport = KtorFeedbackTransport(
        http,
        DesktopCredentials.feedbackApiUrl.orEmpty(),
        DesktopCredentials.feedbackIngestKey,
    )
    private val diagnostics = object : FeedbackDiagnostics {
        override suspend fun unredactedReport(): String = desktopDiagnosticReport()
    }
    private val app = desktopFeedbackApp()
    private val device = desktopFeedbackDevice()

    val available: Boolean get() = transport.available

    private val failure = MutableStateFlow<DesktopPlaybackFailureNotice?>(null)

    /** The failure the playback error dialog shows, or null while none is open. */
    val playbackFailure: StateFlow<DesktopPlaybackFailureNotice?> = failure.asStateFlow()

    fun showPlaybackFailure(notice: DesktopPlaybackFailureNotice) {
        failure.value = notice
    }

    fun closePlaybackFailure() {
        failure.value = null
    }

    fun newOwner(scope: CoroutineScope): FeedbackReviewOwner =
        FeedbackReviewOwner(
            diagnostics = diagnostics,
            transport = transport,
            scope = scope,
            app = app,
            device = device,
            forkDirty = DesktopBuildIdentity.FORK_DIRTY,
            sentAt = { Instant.now().toString() },
        )

    /** The stored crash is shown whether or not feedback is configured; only Report crash needs the service. */
    suspend fun storedCrash(): CrashReport? = withContext(Dispatchers.IO) { DesktopCrashCapture.stored(instanceDirectory) }

    /** The body the crash review starts from: the shared report header, then the redacted crash. */
    fun crashFeedbackBody(report: CrashReport): String =
        report.feedbackBody(FeedbackDrafts.header(app, device, DesktopBuildIdentity.FORK_DIRTY))

    suspend fun clearCrash(): Boolean = withContext(Dispatchers.IO) { DesktopCrashCapture.clear(instanceDirectory) }
}

/**
 * The feedback reviews of one window: one review owner for playback errors and one for the stored crash report, both on
 * the window's UI scope, and the crash screen's state. It lives as long as the window's composition, so moving between
 * pages never drops an open review. [close] ends both reviews without sending.
 */
internal class DesktopFeedbackMount(private val graph: DesktopSessionGraph, private val scope: CoroutineScope) {
    val feedback: DesktopFeedback get() = graph.feedback

    val playback = FeedbackReviewOwnerBinding(feedback.newOwner(scope), scope, onSent = { })

    /** The crash the crash screen shows, or null while it is closed. */
    var crashReport: CrashReport? by mutableStateOf(null)
        private set

    /** True while the screen shows the report of an earlier launch rather than a failure of this process. */
    var crashFromEarlierLaunch: Boolean by mutableStateOf(false)
        private set

    val crash = FeedbackReviewOwnerBinding(feedback.newOwner(scope), scope) {
        // A delivered crash is not offered again on the next launch.
        scope.launch {
            feedback.clearCrash()
            crashReport = null
        }
    }

    init {
        scope.launch {
            val stored = try {
                feedback.storedCrash()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                desktopDiagnostic("stored crash report not read: ${failure.javaClass.simpleName}")
                null
            }
            if (crashReport == null && stored != null) {
                crashReport = stored
                crashFromEarlierLaunch = true
            }
        }
        // A failure this process outlived opens the screen at once, as the Android crash activity does.
        scope.launch {
            DesktopCrashCapture.latest.collect { latest ->
                if (latest != null) {
                    crashReport = latest
                    crashFromEarlierLaunch = false
                }
            }
        }
    }

    fun reportCrash() {
        crashReport?.let { report -> crash.openCrash(feedback.crashFeedbackBody(report)) }
    }

    /** Deleting the stored report is the explicit "do not send". */
    fun deleteCrash() {
        scope.launch { if (feedback.clearCrash()) crashReport = null }
    }

    /**
     * With feedback configured, Close is the prompt's Later: the report is offered again next launch until it is sent
     * or deleted. Without it nothing could ever use the report, so closing removes it, and the screen shows once.
     */
    fun closeCrash() {
        if (feedback.available) crashReport = null else deleteCrash()
    }

    fun shareCrash(reportText: String) {
        scope.launch {
            if (saveDesktopText(DesktopStrings["logs_export_pick"], "Crashlog.txt") { reportText } == false) {
                desktopDiagnostic("crash report was not saved")
            }
        }
    }

    fun close() {
        playback.close()
        crash.close()
    }
}

/**
 * The playback error dialog and the crash screen, each with the shared review-before-send dialog. Only
 * the user's Send in the review transmits; nothing is sent on open, retry, close or dismissal.
 */
@Composable
internal fun DesktopFeedbackOverlay(mount: DesktopFeedbackMount, graph: DesktopSessionGraph) {
    val notice by mount.feedback.playbackFailure.collectAsState()
    val reviewOpen by mount.playback.state.collectAsState()
    val crashReviewOpen by mount.crash.state.collectAsState()
    notice?.takeIf { reviewOpen == null }?.let { current ->
        DesktopPlaybackErrorDialog(current, mount.feedback, graph, onFeedback = mount.playback::openPlayback)
    }
    mount.crashReport?.takeIf { crashReviewOpen == null && reviewOpen == null }?.let { report ->
        CrashReportDialog(
            report = report,
            appName = DesktopStrings["app_name"],
            actions = CrashReportActions(
                copyToClipboard = ::copyToDesktopClipboard,
                shareLogs = mount::shareCrash,
                report = if (mount.feedback.available) mount::reportCrash else null,
                delete = if (mount.feedback.available) mount::deleteCrash else null,
                close = mount::closeCrash,
                notice = if (mount.feedback.available && mount.crashFromEarlierLaunch) DesktopStrings["crash_prompt_text"] else null,
            ),
        )
    }
    BoundFeedbackReviewDialog(mount.playback, reducedMotion = false)
    BoundFeedbackReviewDialog(mount.crash, reducedMotion = false)
}

@Composable
private fun DesktopPlaybackErrorDialog(
    notice: DesktopPlaybackFailureNotice,
    feedback: DesktopFeedback,
    graph: DesktopSessionGraph,
    onFeedback: (String) -> Unit,
) {
    // Login and PO-token recovery stay hidden: the desktop YouTube resolver reports no sign-in kind with its failures.
    PlaybackErrorDialog(
        failure = notice.failure,
        trackTitle = notice.title,
        sourceName = notice.provider,
        actions = PlaybackErrorActions(
            retry = {
                feedback.closePlaybackFailure()
                graph.retryAfterFailure(notice.failure.occurrenceId)
            },
            close = feedback::closePlaybackFailure,
            copyToClipboard = ::copyToDesktopClipboard,
            sendFeedback = if (feedback.available) onFeedback else null,
        ),
    )
}
