package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.domain.dsp.acoustic.AcousticAuditTarget
import moe.rukamori.archivetune.shared.ui.acoustic.AcousticQualityPanel
import moe.rukamori.archivetune.shared.data.diagnostics.JvmDiagnosticLogFiles
import moe.rukamori.archivetune.shared.data.diagnostics.RotatingDiagnosticLog
import moe.rukamori.archivetune.shared.domain.diagnostics.CrashLogKind
import moe.rukamori.archivetune.shared.domain.diagnostics.CrashLogRecord
import moe.rukamori.archivetune.shared.domain.diagnostics.CrashReport
import moe.rukamori.archivetune.shared.domain.diagnostics.DiagnosticLevel
import moe.rukamori.archivetune.shared.domain.diagnostics.DiagnosticLogStore
import moe.rukamori.archivetune.shared.domain.diagnostics.DiagnosticRecord
import moe.rukamori.archivetune.shared.domain.feedback.DiagnosticRedaction
import moe.rukamori.archivetune.shared.domain.settings.SettingsStore
import moe.rukamori.archivetune.shared.ui.navigation.ShellNavigation
import moe.rukamori.archivetune.shared.ui.screens.diagnostics.DiagnosticLogActions
import moe.rukamori.archivetune.shared.ui.screens.diagnostics.DiagnosticLogScreen
import moe.rukamori.archivetune.shared.ui.settings.DeveloperSettings
import moe.rukamori.archivetune.shared.ui.settings.SettingsController

private const val APPLICATION_ID = "moe.rukamori.archivetune"
private val viewerTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
private val crashTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

internal fun formatDiagnosticTime(epochMillis: Long): String = viewerTime.format(Instant.ofEpochMilli(epochMillis))

/**
 * The persistent log of this desktop process, the counterpart of the Android logcat capture: host diagnostics plus
 * everything written to standard output and standard error, in rotating files under `<instance>/logs`. Lines from
 * before [install] (only the single-instance hand-off runs that early) go to standard error alone.
 */
internal object DesktopDiagnosticLog : DiagnosticLogStore {
    @Volatile
    private var log: RotatingDiagnosticLog? = null
    private val idle = MutableStateFlow(0L)

    /** The streams as the JVM opened them, so a host line is printed once and never captured twice. */
    private val standardError: PrintStream = System.err

    override val revision: StateFlow<Long> get() = log?.revision ?: idle.asStateFlow()

    /** Once per process, after the instance lock is held, so a second launch never rotates the owner's files. */
    @Synchronized
    fun install(instanceDirectory: Path) {
        if (log != null) return
        val started = System.currentTimeMillis()
        log = RotatingDiagnosticLog(
            files = JvmDiagnosticLogFiles(instanceDirectory.toAbsolutePath().normalize().resolve("logs")),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            sessionStartMillis = started,
            nowMillis = System::currentTimeMillis,
            formatTime = ::formatDiagnosticTime,
            sessionHeader = "Natune session ${Instant.ofEpochMilli(started)} pid=${ProcessHandle.current().pid()} " +
                "version=${DesktopBuildIdentity.VERSION_NAME} revision=${DesktopBuildIdentity.FORK_REVISION}",
        )
        System.setOut(PrintStream(LineCapture(System.out, DiagnosticLevel.INFO, "stdout"), true, Charsets.UTF_8))
        System.setErr(PrintStream(LineCapture(standardError, DiagnosticLevel.WARNING, "stderr"), true, Charsets.UTF_8))
    }

    fun host(message: String, level: DiagnosticLevel = DiagnosticLevel.WARNING) {
        standardError.println("Natune desktop: $message")
        log?.append(level, HOST_TAG, message)
    }

    /**
     * One YouTube resolver line on the `PlaybackDiagnostics` tag at INFO, the shape the Android playback
     * diagnostics contract writes to logcat (`YouTube tier=… resolved=… elapsedMs=… reason=…`). Routine
     * resolution noise stays out of stderr; it only lands in the persistent log the viewer and exports read.
     */
    fun playbackTier(message: String) {
        log?.append(DiagnosticLevel.INFO, PLAYBACK_DIAGNOSTICS_TAG, message)
    }

    override fun append(level: DiagnosticLevel, tag: String, message: String, error: Throwable?) {
        log?.append(level, tag, message, error)
    }

    override suspend fun readForViewer(): List<DiagnosticRecord> = log?.readForViewer().orEmpty()

    override suspend fun readForExport(): String = log?.readForExport().orEmpty()

    override fun clearViewer() {
        log?.clearViewer()
    }

    private const val HOST_TAG = "Natune"
    private const val PLAYBACK_DIAGNOSTICS_TAG = "PlaybackDiagnostics"
    private const val LINE_LIMIT = 8 * 1024

    /** Passes every byte through and logs each completed line. A line longer than the limit is logged in pieces. */
    private class LineCapture(
        private val target: OutputStream,
        private val level: DiagnosticLevel,
        private val tag: String,
    ) : OutputStream() {
        private val line = ByteArrayOutputStream()

        @Synchronized
        override fun write(byte: Int) {
            target.write(byte)
            collect(byte)
        }

        @Synchronized
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            target.write(bytes, offset, length)
            for (index in offset until offset + length) collect(bytes[index].toInt())
        }

        @Synchronized
        override fun flush() {
            target.flush()
        }

        private fun collect(byte: Int) {
            if (byte == '\n'.code || line.size() >= LINE_LIMIT) {
                emit()
                if (byte == '\n'.code) return
            }
            line.write(byte)
        }

        private fun emit() {
            val text = line.toString(Charsets.UTF_8).trimEnd('\r')
            line.reset()
            if (text.isNotBlank()) log?.append(level, tag, text)
        }
    }
}

/** The report Export writes and a feedback draft carries, in the section order of the Android diagnostic report. */
internal suspend fun desktopDiagnosticReport(): String {
    val processLog = DesktopDiagnosticLog.readForExport()
    return buildString {
        appendLine("Natune diagnostic report v2")
        appendLine("Revision: ${DesktopBuildIdentity.FORK_REVISION} (${DesktopBuildIdentity.FORK_BRANCH})")
        appendLine("Local modifications: ${DesktopBuildIdentity.FORK_DIRTY}")
        appendLine("Version: ${DesktopBuildIdentity.VERSION_NAME}")
        appendLine("Variant: desktop")
        desktopCrashDeviceInfo().forEach { (key, value) -> appendLine("$key: $value") }
        appendLine("Captured: ${Instant.now()}")
        appendLine()
        appendLine("Process log (standard output, standard error and host diagnostics; earlier sessions first, newest last):")
        append(processLog)
    }
}

/** The Device info rows of the crash screen, under the Android labels where the desktop has the same fact. */
internal fun desktopCrashDeviceInfo(): List<Pair<String, String>> {
    fun property(name: String) = System.getProperty(name).orEmpty().ifBlank { "-" }
    return listOf(
        "OS version" to "${property("os.name")} ${property("os.version")}",
        "Hardware" to property("os.arch"),
        "Java" to "${property("java.runtime.version")} (${property("java.vendor")})",
        "Processors" to Runtime.getRuntime().availableProcessors().toString(),
        "Locale" to java.util.Locale.getDefault().toLanguageTag(),
    )
}

/**
 * Crash capture, as on Android: the dying thread only formats the report and writes one redacted file in the instance
 * directory. A JVM often outlives an uncaught exception, so the open window shows the crash screen at once through
 * [latest]; otherwise the next launch shows the stored report. The newest crash overwrites an older one.
 */
internal object DesktopCrashCapture {
    private val newest = MutableStateFlow<CrashReport?>(null)
    private var recordsDirectory: Path? = null

    /** A crash this process survived, for the open window. */
    val latest: StateFlow<CrashReport?> = newest.asStateFlow()

    fun reportFile(instanceDirectory: Path): File =
        instanceDirectory.toAbsolutePath().normalize().resolve("feedback").resolve("crash_report.txt").toFile()

    /** Chains to the handler already installed, so the JVM still prints and handles the failure as before. */
    fun install(instanceDirectory: Path) {
        recordsDirectory =
            instanceDirectory.toAbsolutePath().normalize().resolve("feedback").resolve("records")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            capture(instanceDirectory, thread, error)
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                System.err.print("Exception in thread \"${thread.name}\" ")
                error.printStackTrace()
            }
        }
    }

    private fun capture(instanceDirectory: Path, thread: Thread, error: Throwable) {
        try {
            val now = Instant.now()
            val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
            val report = CrashReport(
                capturedAtMillis = now.toEpochMilli(),
                timestampText = crashTime.format(now),
                appVersion = DesktopBuildIdentity.VERSION_NAME,
                packageName = APPLICATION_ID,
                deviceInfo = desktopCrashDeviceInfo() + ("Thread" to DiagnosticRedaction.redact(thread.name)),
                stackTrace = CrashReport.redactedStack(trace),
            )
            val file = reportFile(instanceDirectory)
            file.parentFile?.mkdirs()
            file.writeText(CrashReport.encode(report))
            DesktopDiagnosticLog.append(DiagnosticLevel.ERROR, "Crash", "FATAL EXCEPTION: ${thread.name}\n$trace")
            newest.value = report
            recordsDirectory?.let { directory ->
                DesktopCrashLogStore(directory).save(
                    CrashLogRecord(
                        timestampMs = report.capturedAtMillis,
                        pid = ProcessHandle.current().pid().toInt(),
                        kind = CrashLogKind.JAVA,
                        status = 0,
                        process = APPLICATION_ID,
                        thread = DiagnosticRedaction.redact(thread.name),
                        body = report.stackTrace,
                        truncated = false,
                        versionName = DesktopBuildIdentity.VERSION_NAME,
                    )
                )
            }
        } catch (failure: Exception) {
            System.err.println("Natune desktop: crash capture failed: ${failure.javaClass.simpleName}")
        }
    }

    /** A report from before the crash screen existed is shown as its whole text. */
    fun stored(instanceDirectory: Path): CrashReport? =
        try {
            reportFile(instanceDirectory).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }?.let { text ->
                CrashReport.decode(text) ?: CrashReport(
                    capturedAtMillis = 0L,
                    timestampText = "",
                    appVersion = DesktopBuildIdentity.VERSION_NAME,
                    packageName = APPLICATION_ID,
                    deviceInfo = desktopCrashDeviceInfo(),
                    stackTrace = CrashReport.redactedStack(text),
                )
            }
        } catch (failure: Exception) {
            null
        }

    /** False while the report is still on disk, so it is offered again next launch. */
    fun clear(instanceDirectory: Path): Boolean {
        newest.value = null
        return reportFile(instanceDirectory).let { !it.exists() || it.delete() }
    }
}

/** A save chooser on the event queue. Null when the user cancels or no chooser can open. */
internal suspend fun chooseDesktopSaveFile(title: String, suggestedName: String): File? =
    suspendCancellableCoroutine { continuation ->
        EventQueue.invokeLater {
            val chosen = try {
                val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE)
                dialog.file = suggestedName
                // A modal file dialog blocks this event-queue task until it closes, while AWT keeps pumping events.
                dialog.isVisible = true
                val name = dialog.file
                val folder = dialog.directory
                dialog.dispose()
                if (name == null || folder == null) null else File(folder, name)
            } catch (failure: Exception) {
                desktopDiagnostic("file chooser unavailable: ${failure.javaClass.simpleName}")
                null
            }
            if (continuation.isActive) continuation.resume(chosen)
        }
    }

/** Writes [text] to a file the user picks. Null when the user cancelled, false when the file could not be written. */
internal suspend fun saveDesktopText(title: String, suggestedName: String, text: suspend () -> String): Boolean? {
    val file = chooseDesktopSaveFile(title, suggestedName) ?: return null
    return try {
        val content = text()
        withContext(Dispatchers.IO) { Files.writeString(file.toPath(), content) }
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        desktopDiagnostic("text was not saved: ${failure.javaClass.simpleName}")
        false
    }
}

/**
 * Settings > Developer > Debug Logs. The desktop has no share sheet, so Share copies the visible lines, and Export
 * saves the redacted diagnostic report to a file the user picks.
 */
@Composable
internal fun DesktopDiagnosticLogPage(settings: SettingsStore, navigation: ShellNavigation, show: (String) -> Unit) {
    DiagnosticLogScreen(
        store = DesktopDiagnosticLog,
        settings = settings,
        formatTime = ::formatDiagnosticTime,
        actions = DiagnosticLogActions(
            back = { navigation.back() },
            copyToClipboard = ::copyToDesktopClipboard,
            share = { text ->
                copyToDesktopClipboard(text)
                show(DesktopStrings["logs_copied"])
            },
            export = {
                saveDesktopText(DesktopStrings["logs_export_pick"], "natune-log-${System.currentTimeMillis()}.txt") {
                    DiagnosticRedaction.redact(desktopDiagnosticReport())
                }
            },
        ),
    )
}

/**
 * Settings > Developer. The acoustic audit mounts the shared panel over the queue's current song, through
 * the graph's audit owner. The Discord debug lines and nerd stats read the Android playback service, which
 * the desktop engine does not have, so those panels say they are not available instead of showing nothing.
 */
@Composable
internal fun DesktopDeveloperSettingsPage(
    graph: DesktopSessionGraph,
    controller: SettingsController,
    navigate: (String) -> Unit,
    onFailure: () -> Unit,
    scrollTo: String?,
) {
    DeveloperSettings(
        controller = controller,
        navigate = navigate,
        playerAvailable = true,
        acousticQualitySection = { active -> DesktopAcousticQualitySection(graph, active) },
        discordDebugSection = { DesktopUnavailablePanel() },
        nerdStatsSection = { DesktopUnavailablePanel() },
        resumed = true,
        onFailure = onFailure,
        scrollTo = scrollTo,
    )
}

/**
 * The developer-page acoustic section: the shared audit panel of the queue's current song, or the
 * unavailable note while nothing is loaded. Leaving or hiding the section cancels a run in flight.
 */
@Composable
private fun DesktopAcousticQualitySection(graph: DesktopSessionGraph, active: Boolean) {
    val queueState by graph.queue.state.collectAsState()
    val current = queueState.currentOccurrenceId
        ?.let { id -> queueState.entries.firstOrNull { it.occurrenceId == id }?.item }
    DisposableEffect(graph.acoustic, active) {
        graph.acoustic.setActive(active)
        onDispose { graph.acoustic.setActive(false) }
    }
    if (current == null) {
        DesktopUnavailablePanel()
        return
    }
    // The snapshot collection keeps the audit key recomputing as the loaded request moves, so a
    // source fallback inside the same occurrence retires the older attempt's binding.
    val playbackSnapshot by graph.player.snapshot.collectAsState()
    val requestKey = playbackSnapshot?.let { graph.acousticRequestKey() }.orEmpty()
    AcousticQualityPanel(
        target = AcousticAuditTarget(
            current.ref.id,
            current.title,
            current.artists.joinToString { it.name },
            requestKey = requestKey,
        ),
        owner = graph.acoustic,
        active = active,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun DesktopUnavailablePanel() {
    Text(
        DesktopStrings["settings_feature_unavailable"],
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
