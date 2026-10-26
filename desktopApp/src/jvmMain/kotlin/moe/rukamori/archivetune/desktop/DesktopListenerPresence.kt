package moe.rukamori.archivetune.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import io.ktor.client.HttpClient
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Properties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.shared.data.presence.KtorListenerPresenceTransport
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.presence.ListenerPresenceController
import moe.rukamori.archivetune.shared.domain.presence.ListenerPresenceKeys
import moe.rukamori.archivetune.shared.domain.presence.ListenerPresencePlatform
import moe.rukamori.archivetune.shared.domain.presence.ListenerPresencePolicy
import moe.rukamori.archivetune.shared.domain.presence.ListenerPresenceState
import moe.rukamori.archivetune.shared.domain.presence.ListenerPresenceStore
import moe.rukamori.archivetune.shared.ui.presence.ListenerPresenceSection

/**
 * The opt-in document in the instance directory. It is a file of its own, not part of the settings file or the vault, so
 * the random identifier never travels with a settings export. A write goes to a synced sibling first and then replaces
 * the document in one move, and the new state is published only after that move.
 */
internal class FileListenerPresenceStore(
    private val file: Path,
    private val newListenerId: () -> String,
) : ListenerPresenceStore {
    private val lock = Mutex()
    private val current = MutableStateFlow<ListenerPresenceState?>(null)

    override val state: Flow<ListenerPresenceState> =
        current.onSubscription { loaded() }.filterNotNull().distinctUntilChanged()

    override suspend fun enable() = transition { ListenerPresencePolicy.enable(it, newListenerId) }

    override suspend fun disable() = transition { ListenerPresencePolicy.disable() }

    override suspend fun resetId() = transition { ListenerPresencePolicy.resetId(it, newListenerId) }

    private suspend fun loaded(): ListenerPresenceState = lock.withLock { current.value ?: read().also { current.value = it } }

    private suspend fun transition(next: (ListenerPresenceState) -> ListenerPresenceState) {
        lock.withLock {
            val before = current.value ?: read()
            val after = next(before)
            if (after != before) write(after)
            current.value = after
        }
    }

    private suspend fun read(): ListenerPresenceState = withContext(Dispatchers.IO) {
        if (!Files.isRegularFile(file)) return@withContext ListenerPresenceState.Off
        try {
            val properties = Properties().apply { Files.newInputStream(file).use { load(it) } }
            ListenerPresenceState(
                enabled = properties.getProperty(ListenerPresenceKeys.ENABLED) == "true",
                listenerId = properties.getProperty(ListenerPresenceKeys.LISTENER_ID)?.takeIf { it.isNotEmpty() },
                consentVersion = properties.getProperty(ListenerPresenceKeys.CONSENT_VERSION)?.toIntOrNull() ?: 0,
            )
        } catch (_: IOException) {
            // An unreadable document counts as no consent. The next switch-on writes a fresh one.
            ListenerPresenceState.Off
        } catch (_: IllegalArgumentException) {
            ListenerPresenceState.Off
        }
    }

    private suspend fun write(state: ListenerPresenceState) = withContext(Dispatchers.IO) {
        val properties = Properties()
        properties.setProperty(ListenerPresenceKeys.ENABLED, state.enabled.toString())
        properties.setProperty(ListenerPresenceKeys.CONSENT_VERSION, state.consentVersion.toString())
        state.listenerId?.let { properties.setProperty(ListenerPresenceKeys.LISTENER_ID, it) }
        Files.createDirectories(file.parent)
        val staging = file.resolveSibling("${file.fileName}.tmp")
        FileOutputStream(staging.toFile()).use { output ->
            properties.store(output, null)
            output.fd.sync()
        }
        if (Files.getFileStore(staging).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(staging, PosixFilePermissions.fromString("rw-------"))
        }
        try {
            Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING)
        }
        Unit
    }
}

/**
 * The desktop host for listener presence. Heartbeats follow the player's audible playback and go out only while the
 * user has opted in and the build or launch supplies an HTTPS endpoint (ARCHIVETUNE_LISTENER_PRESENCE_URL through
 * [DesktopCredentials]). The shared client carries no cookie or auth plugin, which the transport requires.
 */
internal class DesktopListenerPresence(
    parentScope: CoroutineScope,
    private val http: HttpClient,
    private val player: MusicPlayerController,
    instanceDirectory: Path,
) {
    private val secureRandom = SecureRandom()
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    val store: ListenerPresenceStore = FileListenerPresenceStore(instanceDirectory.resolve(STORE_FILE)) {
        ListenerPresencePolicy.newListenerId { count -> ByteArray(count).also(secureRandom::nextBytes) }
    }

    private var controller: ListenerPresenceController? = null

    /** Starts the heartbeat loop once, after the session graph exists. Without an HTTPS endpoint nothing starts. */
    fun start() {
        if (controller != null) return
        val playing = player.snapshot.map { it?.isAudioAdvancing == true }.stateIn(scope, SharingStarted.Eagerly, false)
        controller = ListenerPresenceController(
            scope = scope,
            store = store,
            transport = KtorListenerPresenceTransport(http, DesktopCredentials.value(ENDPOINT_NAME)),
            playing = playing,
            platform = ListenerPresencePlatform.DESKTOP,
            appVersion = ListenerPresencePolicy.sanitizedAppVersion(
                DesktopListenerPresence::class.java.`package`?.implementationVersion.orEmpty(),
            ),
        ).also { it.start() }
    }

    /**
     * Stops the loop before the shared client closes, so a running heartbeat gets its one stopped payload. The wait is
     * bounded; a slow endpoint never holds up quitting.
     */
    suspend fun close() {
        val job = scope.coroutineContext[Job] ?: return
        withTimeoutOrNull(CLOSE_TIMEOUT_MS) { job.cancelAndJoin() }
    }

    private companion object {
        const val STORE_FILE = "listener-presence.properties"
        const val ENDPOINT_NAME = "ARCHIVETUNE_LISTENER_PRESENCE_URL"
        const val CLOSE_TIMEOUT_MS = 2_000L
    }
}

/** The listener-presence switch over the desktop opt-in store, with the identifier reset Settings > Privacy offers. */
@Composable
internal fun DesktopListenerPresenceSection(
    store: ListenerPresenceStore,
    report: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by store.state.collectAsState(initial = ListenerPresenceState.Off)
    val scope = rememberCoroutineScope()
    fun change(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report("listener presence change was not saved: ${failure.javaClass.simpleName}")
            }
        }
    }
    ListenerPresenceSection(
        enabled = state.active,
        onEnabledChange = { on -> change { if (on) store.enable() else store.disable() } },
        modifier = modifier,
        onResetId = { change { store.resetId() } },
    )
}
