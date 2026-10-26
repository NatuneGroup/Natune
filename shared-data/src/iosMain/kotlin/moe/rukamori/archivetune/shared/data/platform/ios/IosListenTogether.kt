package moe.rukamori.archivetune.shared.data.platform.ios

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherChatHistoryStore
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherChatOwner
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherClient
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherMessageCodec
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherRoomDriver
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherServers
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.repository.QueueController
import moe.rukamori.archivetune.shared.domain.settings.ListenTogetherChatHistoryKey
import moe.rukamori.archivetune.shared.domain.settings.ListenTogetherSmartResyncKey
import moe.rukamori.archivetune.shared.domain.settings.ListenTogetherSyncVolumeKey
import moe.rukamori.archivetune.shared.domain.settings.SettingsStore
import moe.rukamori.archivetune.shared.domain.settings.listenTogetherServerUrl
import platform.Foundation.NSDate
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.timeIntervalSince1970

/**
 * The iOS Listen Together host: the shared client over the `NSURLSessionWebSocketTask` socket, the vault-backed
 * settings adapters, the shared room driver that keeps the player in step, and the chat owner the room chat pages
 * read. The session graph constructs one before [ListenTogetherClient.connect], because the client's event flow
 * does not replay.
 *
 * The session token rides the vault-backed settings store exactly as it does on desktop: an incomplete record
 * reads as nothing stored, the client applies its ten-minute grace period, and a failed write never reaches the
 * client, which keeps its in-memory room.
 */
class IosListenTogether(
    parentScope: CoroutineScope,
    private val settings: SettingsStore,
    player: MusicPlayerController,
    queue: QueueController,
    setVolume: (Float) -> Unit,
    /** The local custom picture as JPEG bytes, or null when the member chose none. The caller owns the read. */
    localAvatarBytes: suspend () -> ByteArray? = { null },
    private val report: (String) -> Unit = {},
) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job + Dispatchers.Default.limitedParallelism(1))

    private val store = IosListenTogetherStore(settings, report)

    val client = ListenTogetherClient(
        scope = scope,
        sockets = IosListenTogetherSocketFactory(
            NSURLSession.sessionWithConfiguration(NSURLSessionConfiguration.ephemeralSessionConfiguration),
        ),
        codec = ListenTogetherMessageCodec(gzip = IosListenTogetherGzip()),
        preferences = IosListenTogetherPreferences(settings),
        store = store,
        // iOS publishes no reachability events here; a dropped socket still reaches the client's retry rules.
        network = MutableStateFlow(true),
        nowMs = { (NSDate().timeIntervalSince1970 * 1000.0).toLong() },
    )

    /** Keeps the local player in step with the room; call [start] once the graph owns a player. */
    val driver = ListenTogetherRoomDriver(
        scope = scope,
        client = client,
        player = player,
        queue = queue,
        setVolume = setVolume,
        smartResync = { settings.snapshots.value?.get(ListenTogetherSmartResyncKey) != false },
        syncVolume = { settings.snapshots.value?.get(ListenTogetherSyncVolumeKey) == true },
        report = report,
        nowMs = { (NSDate().timeIntervalSince1970 * 1000.0).toLong() },
    )

    /** The room chat: JSON servers relay it; on The Meowery the client answers CHAT_UNSUPPORTED_BY_SERVER. */
    val chat = ListenTogetherChatOwner(
        client = client,
        scope = scope,
        history = IosListenTogetherChatHistory(settings, report),
        localAvatarBytes = localAvatarBytes,
        nowMs = { (NSDate().timeIntervalSince1970 * 1000.0).toLong() },
        onDiagnostic = report,
    )

    fun start() = driver.start()

    /** The web invite for the current room on the configured server, or null outside a room. */
    fun inviteLink(): String? {
        val code = client.roomCode.value ?: return null
        val serverUrl = settings.snapshots.value?.listenTogetherServerUrl(ListenTogetherServers.defaultServerUrl)
            ?: ListenTogetherServers.defaultServerUrl
        return "https://${ListenTogetherServers.inviteHost(serverUrl)}/listen?code=$code"
    }

    fun close() {
        chat.close()
        job.cancel()
    }
}

/** The room chat history, kept under the same settings key the Android host uses. */
private class IosListenTogetherChatHistory(
    private val settings: SettingsStore,
    private val log: (String) -> Unit,
) : ListenTogetherChatHistoryStore {
    override suspend fun read(): String? =
        settings.snapshots.value?.get(ListenTogetherChatHistoryKey)

    override suspend fun write(document: String?) {
        try {
            settings.edit { this[ListenTogetherChatHistoryKey] = document }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log("Listen Together chat history could not be saved")
        }
    }
}
