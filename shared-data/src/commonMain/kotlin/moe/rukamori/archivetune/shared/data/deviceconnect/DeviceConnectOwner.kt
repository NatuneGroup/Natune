package moe.rukamori.archivetune.shared.data.deviceconnect

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectEnvironment
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectError
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectErrorCode
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectHosts
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectLimits
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectRole
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectState
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceCrypto
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceEndpoint
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceIdentity
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceInfoPayload
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePairInfo
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePairingPrompt
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePeerPresence
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePeerState
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePlaybackAction
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePlaybackPort
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceQueueHash
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceRemoteCommand
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceSessionState
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceSyncMessage
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceSyncMessageType
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceTransferRequest
import moe.rukamori.archivetune.shared.domain.deviceconnect.toHex
import moe.rukamori.archivetune.shared.domain.queue.QueueRepeatMode
import moe.rukamori.archivetune.shared.domain.settings.DeviceConnectAllowVpnKey
import moe.rukamori.archivetune.shared.domain.settings.DeviceConnectDefaultDeviceKey
import moe.rukamori.archivetune.shared.domain.settings.DeviceConnectEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.DeviceConnectDeviceNameKey
import moe.rukamori.archivetune.shared.domain.settings.SettingsStore
import kotlin.time.Clock

/**
 * Owns Device Connect lifecycle: the server, discovery, authenticated connections, pairing prompts and
 * the single playback session. A controller never writes the target's queue; it sends commands with the
 * revisions it last saw, and the target answers typed stale errors.
 */
class DeviceConnectOwner(
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val identityStore: DeviceIdentityStore,
    private val pairs: DevicePairRepository,
    private val crypto: DeviceCrypto,
    private val playback: DevicePlaybackPort,
    private val discovery: DeviceDiscovery,
    private val httpClient: HttpClient,
    private val environment: () -> DeviceConnectEnvironment = { DeviceConnectEnvironment() },
    private val nowEpochMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val _state = MutableStateFlow(DeviceConnectState())
    val state: StateFlow<DeviceConnectState> = _state.asStateFlow()

    private val lifecycle = Mutex()
    private val connections = mutableMapOf<String, DeviceConnectConnection>()
    private val unbound = mutableSetOf<DeviceConnectConnection>()
    private val discovered = mutableMapOf<String, DevicePeerState>()
    private val connectAttempts = mutableMapOf<String, Long>()
    private var server: DeviceConnectServer? = null
    private var serverPort = 0
    private var started = false
    private var shuttingDown = false
    private var identity: DeviceIdentity? = null
    private var localSnapshotBeforeRemote: DevicePlaybackSnapshot? = null
    private var targetUpdateJob: Job? = null
    private var pendingTransferTarget: String? = null
    private val syncQueue = Channel<SyncEvent>(Channel.UNLIMITED)
    private val client = DeviceConnectClient(httpClient)

    fun start() {
        if (started) return
        started = true
        scope.launch {
            settings.snapshots.filterNotNull().collect { snapshot ->
                val enabled = snapshot[DeviceConnectEnabledKey, false]
                if (enabled) enableIfNeeded() else disable()
            }
        }
        scope.launch { refreshPaired() }
        scope.launch {
            for (event in syncQueue) handleSync(event.connection, event.message)
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        settings.edit { this[DeviceConnectEnabledKey] = enabled }
    }

    suspend fun setDeviceName(name: String) {
        val renamed = identityStore.rename(name)
        identity = renamed
        _state.update { it.copy(local = renamed) }
        updateAdvertisement()
    }

    suspend fun setAllowVpn(enabled: Boolean) {
        settings.edit { this[DeviceConnectAllowVpnKey] = enabled }
    }

    suspend fun setDefaultDevice(peerId: String?) {
        settings.edit { this[DeviceConnectDefaultDeviceKey] = peerId.orEmpty() }
    }

    fun requestPermission() {
        _state.update { it.copy(permissionRequired = true) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun markPermissionRequired() {
        _state.update { it.copy(permissionRequired = true) }
    }

    fun clearPermissionRequirement() {
        _state.update { it.copy(permissionRequired = false) }
    }

    suspend fun pair(peerId: String) {
        if (connections[peerId] != null || unbound.any { it.peerDeviceId == peerId }) return
        connectTo(peerId)
    }

    suspend fun connectTo(peerId: String) {
        val peer = _state.value.peers.firstOrNull { it.id == peerId }
            ?: _state.value.paired.firstOrNull { it.peerId == peerId }?.let { info ->
                DevicePeerState(info.peerId, info.peerName, info.peerPlatform, info.peerModel, DevicePeerPresence.PAIRED)
            }
            ?: run {
                _state.update { it.copy(error = DeviceConnectError(DeviceConnectErrorCode.UNAVAILABLE, "The device was not found")) }
                return
            }
        val endpoint = peer.endpoint ?: run {
            _state.update { it.copy(error = DeviceConnectError(DeviceConnectErrorCode.UNAVAILABLE, "The device has no reachable address")) }
            return
        }
        dial(endpoint, peer.name)
    }

    suspend fun connectManual(text: String) {
        val endpoint = DeviceConnectHosts.parseManualEndpoint(text) ?: run {
            _state.update { it.copy(error = DeviceConnectError(DeviceConnectErrorCode.INVALID_MESSAGE, "Enter a private IPv4 or IPv6 address and port")) }
            return
        }
        dial(endpoint, endpoint.host)
    }

    suspend fun unpair(peerId: String) {
        pairs.remove(peerId)
        connections.remove(peerId)?.close()
        clearSession()
        refreshPaired()
        updatePeers()
    }

    suspend fun approvePairing() {
        val prompt = _state.value.pairing ?: return
        val connection = connections[prompt.peerId] ?: unbound.firstOrNull { it.peerDeviceId == prompt.peerId } ?: return
        runCatching { connection.approvePairing() }
            .onFailure { _state.update { it.copy(error = DeviceConnectError(DeviceConnectErrorCode.UNAVAILABLE, "Pairing could not be completed")) } }
        _state.update { it.copy(pairing = null) }
        updateAdvertisement()
    }

    suspend fun rejectPairing() {
        val prompt = _state.value.pairing ?: return
        val connection = connections[prompt.peerId] ?: unbound.firstOrNull { it.peerDeviceId == prompt.peerId } ?: return
        runCatching { connection.rejectPairing() }
        _state.update { it.copy(pairing = null) }
        updateAdvertisement()
    }

    suspend fun rescan() {
        val current = identity ?: return
        serverPort.takeIf { it > 0 }?.let { startDiscovery(current, it) }
    }

    suspend fun transferTo(peerId: String) {
        val conflict = environment().conflict
        if (conflict != null) {
            _state.update {
                it.copy(error = DeviceConnectError(conflict, conflictMessage(conflict)))
            }
            return
        }
        if (pairs.find(peerId) == null) {
            _state.update { it.copy(error = DeviceConnectError(DeviceConnectErrorCode.UNAVAILABLE, "Pair this device first")) }
            return
        }
        val connection = connections[peerId]
        if (connection == null) {
            pendingTransferTarget = peerId
            connectTo(peerId)
            return
        }
        val snapshot = runCatching { playback.pauseForRemote() }.getOrNull() ?: run {
            _state.update { it.copy(error = DeviceConnectError(DeviceConnectErrorCode.UNAVAILABLE, "Nothing is playing on this device")) }
            return
        }
        if (snapshot.entries.any { it.provider == "local" }) {
            playback.resume(snapshot)
            _state.update {
                it.copy(error = DeviceConnectError(DeviceConnectErrorCode.UNSHAREABLE_QUEUE, "This queue contains local files and cannot be shared."))
            }
            return
        }
        if (snapshot.entries.size > DeviceConnectLimits.MAX_QUEUE_ENTRIES) {
            playback.resume(snapshot)
            _state.update { it.copy(error = DeviceConnectError(DeviceConnectErrorCode.TOO_LARGE, "This queue is too large to sync.")) }
            return
        }
        val sessionId = crypto.randomBytes(16).toHex()
        val outgoing = snapshot.copy(sessionId = sessionId, queueHash = DeviceQueueHash.of(snapshot.entries))
        val session = DeviceSessionState(
            sessionId = sessionId,
            targetId = peerId,
            targetName = _state.value.peer(peerId)?.name ?: _state.value.pair(peerId)?.peerName,
            controllerIds = setOf(identity?.installId.orEmpty()),
            isController = true,
            remoteState = outgoing,
        )
        val sent = runCatching {
            connection.sendSync(DeviceSyncMessage(DeviceSyncMessageType.TRANSFER, sessionId = sessionId, snapshot = outgoing))
        }.isSuccess
        if (!sent) {
            playback.resume(snapshot)
            _state.update { it.copy(error = DeviceConnectError(DeviceConnectErrorCode.TOO_LARGE, "This queue is too large to sync.")) }
            return
        }
        localSnapshotBeforeRemote = snapshot
        _state.update { it.copy(session = session, error = null) }
        playback.onRemoteSessionChanged(session)
        updateAdvertisement()
    }

    suspend fun acceptIncomingTransfer() {
        val incoming = _state.value.incomingTransfer ?: return
        val connection = connections[incoming.fromId]
        val error = playback.apply(incoming.snapshot)
        if (error != null) {
            runCatching {
                connection?.sendSync(
                    DeviceSyncMessage(DeviceSyncMessageType.ERROR, sessionId = incoming.sessionId, error = error),
                )
            }
            _state.update { it.copy(incomingTransfer = null, error = error) }
            return
        }
        val session = DeviceSessionState(
            sessionId = incoming.sessionId,
            targetId = identity?.installId,
            targetName = identity?.name,
            controllerIds = setOf(incoming.fromId),
            isTarget = true,
            remoteState = incoming.snapshot,
        )
        _state.update { it.copy(incomingTransfer = null, session = session, error = null) }
        playback.onRemoteSessionChanged(session)
        if (connection != null) {
            sendState(connection, session)
            startTargetUpdates(connection, incoming.sessionId)
        }
        updateAdvertisement()
    }

    suspend fun declineIncomingTransfer() {
        val incoming = _state.value.incomingTransfer ?: return
        runCatching {
            connections[incoming.fromId]?.sendSync(
                DeviceSyncMessage(
                    DeviceSyncMessageType.ERROR,
                    sessionId = incoming.sessionId,
                    error = DeviceConnectError(DeviceConnectErrorCode.NOT_ALLOWED, "The other device declined the transfer"),
                ),
            )
        }
        _state.update { it.copy(incomingTransfer = null) }
    }

    fun pullBack() {
        val session = _state.value.session ?: return
        if (!session.isController) return
        val targetId = session.targetId ?: return
        val connection = connections[targetId] ?: return
        scope.launch {
            runCatching {
                connection.sendSync(DeviceSyncMessage(DeviceSyncMessageType.PULL, sessionId = session.sessionId))
            }.onFailure {
                _state.update { state -> state.copy(error = DeviceConnectError(DeviceConnectErrorCode.UNAVAILABLE, "The playback device did not answer")) }
            }
        }
    }

    fun release() {
        val session = _state.value.session ?: return
        val peer = session.targetId?.let(connections::get)
        scope.launch {
            if (session.isController) {
                runCatching { peer?.sendSync(DeviceSyncMessage(DeviceSyncMessageType.RELEASE, sessionId = session.sessionId)) }
            }
            clearSession()
        }
    }

    fun remotePlay() = remoteCommand(DeviceRemoteCommand(DevicePlaybackAction.PLAY))

    fun remotePause() = remoteCommand(DeviceRemoteCommand(DevicePlaybackAction.PAUSE))

    fun remoteSeek(positionMs: Long) = remoteCommand(DeviceRemoteCommand(DevicePlaybackAction.SEEK, positionMs = positionMs))

    fun remoteNext() = remoteCommand(DeviceRemoteCommand(DevicePlaybackAction.NEXT))

    fun remotePrevious() = remoteCommand(DeviceRemoteCommand(DevicePlaybackAction.PREVIOUS))

    fun remoteSelect(occurrenceId: String) =
        remoteCommand(DeviceRemoteCommand(DevicePlaybackAction.SELECT, occurrenceId = occurrenceId))

    fun remoteSetVolume(volume: Float) =
        remoteCommand(DeviceRemoteCommand(DevicePlaybackAction.VOLUME, volume = volume))

    fun remoteSetShuffle(enabled: Boolean) =
        remoteCommand(DeviceRemoteCommand(DevicePlaybackAction.SHUFFLE, enabled = enabled))

    fun remoteSetSpeed(speed: Float) =
        remoteCommand(DeviceRemoteCommand(DevicePlaybackAction.SPEED, speed = speed))

    fun remoteSetPitch(pitch: Float) =
        remoteCommand(DeviceRemoteCommand(DevicePlaybackAction.PITCH, pitch = pitch))

    fun remoteSetRepeat(mode: QueueRepeatMode) =
        remoteCommand(DeviceRemoteCommand(DevicePlaybackAction.REPEAT, repeatMode = mode.storedValue))

    fun stop() {
        shuttingDown = true
        runCatching { discovery.stop() }
        server?.stop()
        server = null
        serverPort = 0
        connections.values.toList().forEach { it.close() }
        connections.clear()
        unbound.toList().forEach { it.close() }
        unbound.clear()
        discovered.clear()
        clearSession()
        _state.update { DeviceConnectState(enabled = false, local = identity, paired = it.paired) }
    }

    private suspend fun enableIfNeeded() {
        if (shuttingDown) return
        lifecycle.withLock {
            if (shuttingDown || server != null) return
            _state.update { it.copy(enabled = true, serverStarting = true, error = null) }
            val local = runCatching { identityStore.identity() }.getOrElse {
                _state.update { state -> state.copy(enabled = true, serverStarting = false, error = DeviceConnectError(DeviceConnectErrorCode.UNAVAILABLE, "Settings are not ready")) }
                return
            }
            identity = local
            val instance = DeviceConnectServer { channel -> acceptChannel(channel) }
            val port = runCatching { instance.start() }.getOrElse {
                _state.update { state ->
                    state.copy(serverStarting = false, error = DeviceConnectError(DeviceConnectErrorCode.UNAVAILABLE, "The local server could not start"))
                }
                return
            }
            server = instance
            serverPort = port
            _state.update { it.copy(local = local, enabled = true, serverStarting = false, serverRunning = true, serverPort = port) }
            refreshPaired()
            startDiscovery(local, port)
        }
    }

    private suspend fun disable() {
        lifecycle.withLock {
            if (server == null && _state.value.enabled.not()) return
            runCatching { discovery.stop() }
            server?.stop()
            server = null
            serverPort = 0
            clearSession()
            connections.values.toList().forEach { it.close() }
            connections.clear()
            unbound.toList().forEach { it.close() }
            unbound.clear()
            discovered.clear()
            _state.update {
                DeviceConnectState(enabled = false, local = identity, paired = it.paired)
            }
        }
    }

    private suspend fun startDiscovery(local: DeviceIdentity, port: Int) {
        val advertisement = advertisement(local, port)
        runCatching {
            discovery.start(
                advertisement,
                object : DeviceDiscovery.Listener {
                    override fun onFound(device: DiscoveredDevice) {
                        if (device.deviceId == identity?.installId) return
                        discovered[device.deviceId] = DevicePeerState(
                            id = device.deviceId,
                            name = device.name,
                            platform = device.platform,
                            model = device.model,
                            presence = DevicePeerPresence.DISCOVERED,
                            endpoint = device.endpoint,
                            lastSeenEpochMs = device.lastSeenEpochMs,
                        )
                        updatePeers()
                        scope.launch { maybeAutoConnect(device.deviceId) }
                    }

                    override fun onLost(deviceId: String) {
                        discovered.remove(deviceId)
                        updatePeers()
                    }

                    override fun onFailure(message: String) {
                        _state.update { it.copy(error = DeviceConnectError(DeviceConnectErrorCode.UNAVAILABLE, message)) }
                    }
                },
            )
        }.onFailure {
            _state.update { state -> state.copy(error = DeviceConnectError(DeviceConnectErrorCode.UNAVAILABLE, "Local discovery could not start")) }
        }
    }

    private suspend fun maybeAutoConnect(peerId: String) {
        if (connections[peerId] != null) return
        if (pairs.find(peerId) == null) return
        val now = nowEpochMs()
        val previous = connectAttempts[peerId] ?: 0L
        if (now - previous < AUTO_CONNECT_INTERVAL_MS) return
        connectAttempts[peerId] = now
        connectTo(peerId)
    }

    private suspend fun dial(endpoint: DeviceEndpoint, peerName: String) {
        if (!DeviceConnectHosts.isPrivateOrLocal(endpoint.host)) {
            _state.update { it.copy(error = DeviceConnectError(DeviceConnectErrorCode.NOT_ALLOWED, "Only devices on your local network can connect")) }
            return
        }
        val channel = runCatching { client.connect(endpoint) }.getOrElse {
            _state.update { state -> state.copy(error = DeviceConnectError(DeviceConnectErrorCode.UNAVAILABLE, "The device is unreachable")) }
            return
        }
        val connection = newConnection(DeviceConnectRole.INITIATOR, channel, peerName)
        unbound.add(connection)
        connection.start()
        updatePeers()
    }

    private suspend fun acceptChannel(channel: DeviceFrameChannel) {
        val connection = newConnection(DeviceConnectRole.RESPONDER, channel, "")
        unbound.add(connection)
        connection.start()
        connection.awaitClosed()
    }

    private fun newConnection(role: DeviceConnectRole, channel: DeviceFrameChannel, peerNameHint: String): DeviceConnectConnection {
        val local = identity ?: error("Device Connect has no identity")
        var connection: DeviceConnectConnection? = null
        val listener = object : DeviceConnectConnection.Listener {
            override fun onAuthenticated(peer: DeviceInfoPayload) {
                val active = connection ?: return
                unbound.remove(active)
                connections[peer.deviceId] = active
                refreshPeersAsync()
                val target = pendingTransferTarget
                if (target == peer.deviceId) {
                    pendingTransferTarget = null
                    scope.launch { transferTo(target) }
                }
            }

            override fun onPeerInfo(peer: DeviceInfoPayload) {
                val existing = discovered[peer.deviceId]
                if (existing != null) {
                    discovered[peer.deviceId] = existing.copy(
                        name = peer.name.ifBlank { existing.name },
                        platform = moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePlatform.fromWire(peer.platform),
                        model = peer.model,
                    )
                }
                scope.launch { updatePeers() }
            }

            override fun onPairingPrompt(prompt: DevicePairingPrompt) {
                _state.update { it.copy(pairing = prompt) }
                updateAdvertisementAsync()
            }

            override fun onPairingEnded() {
                _state.update { it.copy(pairing = null) }
                updateAdvertisementAsync()
            }

            override fun onSync(message: DeviceSyncMessage) {
                val active = connection ?: return
                syncQueue.trySend(SyncEvent(active, message))
            }

            override fun onFailure(error: DeviceConnectError) {
                _state.update { it.copy(error = error) }
            }

            override fun onClosed() {
                val active = connection ?: return
                unbound.remove(active)
                connections.entries.filter { it.value === active }.forEach { connections.remove(it.key) }
                val peerId = active.peerDeviceId
                if (peerId != null && _state.value.pairing?.peerId == peerId) {
                    _state.update { it.copy(pairing = null) }
                }
                if (_state.value.incomingTransfer?.fromId == peerId) {
                    _state.update { it.copy(incomingTransfer = null) }
                }
                val current = _state.value.session
                if (current != null && current.targetId == peerId) {
                    if (current.isController) {
                        localSnapshotBeforeRemote?.let { snapshot -> scope.launch { playback.resume(snapshot) } }
                    }
                    clearSession()
                }
                refreshPeersAsync()
            }
        }
        val created = DeviceConnectConnection(
            role = role,
            localIdentity = local,
            crypto = crypto,
            pairs = pairs,
            channel = channel,
            scope = scope,
            peerNameHint = peerNameHint,
            nowEpochMs = nowEpochMs,
            listener = listener,
        )
        connection = created
        return created
    }

    private suspend fun handleSync(connection: DeviceConnectConnection, message: DeviceSyncMessage) {
        when (message.type) {
            DeviceSyncMessageType.TRANSFER -> onIncomingTransfer(connection, message)
            DeviceSyncMessageType.STATE -> onRemoteState(connection, message)
            DeviceSyncMessageType.UPDATE -> onRemoteUpdate(connection, message)
            DeviceSyncMessageType.COMMAND -> onRemoteCommand(connection, message)
            DeviceSyncMessageType.PULL -> onPullRequested(connection, message)
            DeviceSyncMessageType.RELEASE -> {
                clearSession()
                updateAdvertisement()
            }
            DeviceSyncMessageType.ERROR -> onSessionError(connection, message)
            DeviceSyncMessageType.ATTACH -> Unit
        }
    }

    private suspend fun onIncomingTransfer(connection: DeviceConnectConnection, message: DeviceSyncMessage) {
        val snapshot = message.snapshot ?: return sendError(connection, message.sessionId, DeviceConnectError(DeviceConnectErrorCode.INVALID_MESSAGE, "Transfer without a snapshot"))
        val sessionId = message.sessionId ?: snapshot.sessionId
        if (snapshot.entries.any { it.provider == "local" }) {
            return sendError(connection, sessionId, DeviceConnectError(DeviceConnectErrorCode.UNSHAREABLE_QUEUE, "This queue contains local files and cannot be shared."))
        }
        if (snapshot.entries.size > DeviceConnectLimits.MAX_QUEUE_ENTRIES) {
            return sendError(connection, sessionId, DeviceConnectError(DeviceConnectErrorCode.TOO_LARGE, "This queue is too large to sync."))
        }
        val conflict = environment().conflict
        if (conflict != null) {
            return sendError(connection, sessionId, DeviceConnectError(conflict, conflictMessage(conflict)))
        }
        val current = _state.value.session
        val fromId = connection.peerDeviceId.orEmpty()
        if (current != null && current.sessionId == sessionId && current.isController) {
            val error = playback.apply(snapshot)
            if (error != null) return sendError(connection, sessionId, error)
            val session = DeviceSessionState(
                sessionId = sessionId,
                targetId = identity?.installId,
                targetName = identity?.name,
                controllerIds = setOf(fromId),
                isTarget = true,
                remoteState = snapshot,
            )
            _state.update { it.copy(session = session, incomingTransfer = null, error = null) }
            playback.onRemoteSessionChanged(session)
            sendState(connection, session)
            startTargetUpdates(connection, sessionId)
            return
        }
        if (_state.value.incomingTransfer != null || (current != null && current.isTarget)) {
            return sendError(connection, sessionId, DeviceConnectError(DeviceConnectErrorCode.BUSY, "This device is already controlling another session"))
        }
        _state.update {
            it.copy(
                incomingTransfer = DeviceTransferRequest(sessionId, fromId, connection.peerDeviceId?.let { id -> _state.value.peer(id)?.name } ?: fromId, snapshot),
                error = null,
            )
        }
    }

    private fun onRemoteState(connection: DeviceConnectConnection, message: DeviceSyncMessage) {
        val session = _state.value.session ?: return
        if (!session.isController || connection.peerDeviceId != session.targetId) return
        val snapshot = message.snapshot ?: return
        val updated = session.copy(
            targetName = session.targetId?.let { _state.value.peer(it)?.name } ?: session.targetName,
            remoteState = snapshot,
            error = null,
        )
        _state.update { it.copy(session = updated) }
        playback.onRemoteSessionChanged(updated)
    }

    private fun onRemoteUpdate(connection: DeviceConnectConnection, message: DeviceSyncMessage) {
        val session = _state.value.session ?: return
        if (!session.isController || connection.peerDeviceId != session.targetId) return
        val previous = session.remoteState ?: return
        val snapshot = message.snapshot ?: previous
        _state.update { it.copy(session = session.copy(remoteState = snapshot)) }
        playback.onRemoteSessionChanged(session.copy(remoteState = snapshot))
    }

    private suspend fun onRemoteCommand(connection: DeviceConnectConnection, message: DeviceSyncMessage) {
        val session = _state.value.session ?: return sendError(connection, message.sessionId, DeviceConnectError(DeviceConnectErrorCode.SESSION_GONE, "This session has ended"))
        if (!session.isTarget) return sendError(connection, message.sessionId, DeviceConnectError(DeviceConnectErrorCode.NOT_ALLOWED, "This device is not the playback target"))
        val sender = connection.peerDeviceId
        if (sender == null || sender !in session.controllerIds) {
            return sendError(connection, message.sessionId, DeviceConnectError(DeviceConnectErrorCode.NOT_ALLOWED, "This device is not the controller"))
        }
        val error = playback.command(message)
        if (error != null) return sendError(connection, message.sessionId, error)
        sendState(connection, session)
    }

    private suspend fun onPullRequested(connection: DeviceConnectConnection, message: DeviceSyncMessage) {
        val session = _state.value.session ?: return sendError(connection, message.sessionId, DeviceConnectError(DeviceConnectErrorCode.SESSION_GONE, "This session has ended"))
        if (!session.isTarget) return sendError(connection, message.sessionId, DeviceConnectError(DeviceConnectErrorCode.NOT_ALLOWED, "This device is not the playback target"))
        val sender = connection.peerDeviceId
        if (sender == null || sender !in session.controllerIds) {
            return sendError(connection, session.sessionId, DeviceConnectError(DeviceConnectErrorCode.NOT_ALLOWED, "This device is not the controller"))
        }
        val snapshot = playback.pauseForRemote() ?: return sendError(connection, message.sessionId, DeviceConnectError(DeviceConnectErrorCode.UNAVAILABLE, "Nothing is playing on this device"))
        targetUpdateJob?.cancel()
        runCatching {
            connection.sendSync(DeviceSyncMessage(DeviceSyncMessageType.TRANSFER, sessionId = session.sessionId, snapshot = snapshot.copy(sessionId = session.sessionId)))
        }.onFailure {
            return sendError(connection, session.sessionId, DeviceConnectError(DeviceConnectErrorCode.UNAVAILABLE, "The playback device did not answer"))
        }
        val updated = session.copy(
            targetId = connection.peerDeviceId,
            targetName = connection.peerDeviceId?.let { _state.value.peer(it)?.name },
            isController = true,
            isTarget = false,
            remoteState = snapshot,
        )
        _state.update { it.copy(session = updated) }
        playback.onRemoteSessionChanged(updated)
    }

    private fun onSessionError(connection: DeviceConnectConnection, message: DeviceSyncMessage) {
        val error = message.error ?: DeviceConnectError(DeviceConnectErrorCode.INVALID_MESSAGE, "The other device reported an error")
        val session = _state.value.session
        if (session != null && error.code.isStale) {
            _state.update { it.copy(session = session.copy(error = error)) }
            return
        }
        _state.update { it.copy(error = error) }
        if (session?.isController == true) {
            localSnapshotBeforeRemote?.let { snapshot -> scope.launch { playback.resume(snapshot) } }
        }
        clearSession()
    }

    private suspend fun sendState(connection: DeviceConnectConnection, session: DeviceSessionState) {
        val snapshot = playback.capture() ?: session.remoteState ?: return
        runCatching {
            connection.sendSync(
                DeviceSyncMessage(
                    DeviceSyncMessageType.STATE,
                    sessionId = session.sessionId,
                    snapshot = snapshot,
                    queueRevision = snapshot.queueRevision,
                    trackRevision = snapshot.trackRevision,
                ),
            )
        }
    }

    private fun startTargetUpdates(connection: DeviceConnectConnection, sessionId: String) {
        targetUpdateJob?.cancel()
        targetUpdateJob = scope.launch {
            var lastQueueRevision = -1L
            while (isActive) {
                val session = _state.value.session ?: break
                if (!session.isTarget || session.sessionId != sessionId) break
                val snapshot = playback.capture() ?: break
                val type = if (snapshot.queueRevision != lastQueueRevision) {
                    lastQueueRevision = snapshot.queueRevision
                    DeviceSyncMessageType.STATE
                } else {
                    DeviceSyncMessageType.UPDATE
                }
                val message = DeviceSyncMessage(
                    type,
                    sessionId = sessionId,
                    snapshot = snapshot,
                    queueRevision = snapshot.queueRevision,
                    trackRevision = snapshot.trackRevision,
                )
                if (runCatching { connection.sendSync(message) }.isFailure) break
                delay(UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun remoteCommand(command: DeviceRemoteCommand) {
        val session = _state.value.session ?: return
        if (!session.isController) return
        val targetId = session.targetId ?: return
        val connection = connections[targetId] ?: return
        val remote = session.remoteState
        val outgoing = command.copy(
            expectedQueueRevision = remote?.queueRevision,
            expectedTrackRevision = remote?.trackRevision,
            expectedSessionId = session.sessionId,
        )
        scope.launch {
            runCatching {
                connection.sendSync(
                    DeviceSyncMessage(
                        DeviceSyncMessageType.COMMAND,
                        sessionId = session.sessionId,
                        command = outgoing,
                        queueRevision = remote?.queueRevision ?: 0,
                        trackRevision = remote?.trackRevision ?: 0,
                    ),
                )
            }.onFailure {
                _state.update { state -> state.copy(error = DeviceConnectError(DeviceConnectErrorCode.UNAVAILABLE, "The playback device did not answer")) }
            }
        }
    }

    private suspend fun sendError(connection: DeviceConnectConnection, sessionId: String?, error: DeviceConnectError) {
        runCatching {
            connection.sendSync(DeviceSyncMessage(DeviceSyncMessageType.ERROR, sessionId = sessionId, error = error))
        }
    }

    private fun clearSession() {
        targetUpdateJob?.cancel()
        targetUpdateJob = null
        localSnapshotBeforeRemote = null
        _state.update { it.copy(session = null, incomingTransfer = null) }
        playback.onRemoteSessionChanged(null)
    }

    private suspend fun refreshPaired() {
        val paired = runCatching { pairs.list() }.getOrDefault(emptyList())
        _state.update { it.copy(paired = paired) }
        updatePeers()
    }

    private fun refreshPeersAsync() {
        scope.launch {
            refreshPaired()
            updatePeers()
        }
    }

    private fun updatePeers() {
        val paired = _state.value.paired
        val pairedIds = paired.mapTo(mutableSetOf()) { it.peerId }
        val peers = buildList {
            paired.forEach { info ->
                val presence = when {
                    _state.value.session?.targetId == info.peerId && _state.value.session?.isController == true -> DevicePeerPresence.PLAYING_REMOTE
                    connections.containsKey(info.peerId) -> DevicePeerPresence.AVAILABLE
                    discovered.containsKey(info.peerId) -> DevicePeerPresence.PAIRED
                    else -> DevicePeerPresence.OFFLINE
                }
                add(
                    DevicePeerState(
                        id = info.peerId,
                        name = info.peerName,
                        platform = info.peerPlatform,
                        model = info.peerModel,
                        presence = presence,
                        endpoint = discovered[info.peerId]?.endpoint,
                        lastSeenEpochMs = discovered[info.peerId]?.lastSeenEpochMs ?: 0,
                        staleAccount = info.staleAccount,
                    ),
                )
            }
            discovered.values.filter { it.id !in pairedIds }.forEach { add(it) }
        }
        _state.update { it.copy(peers = peers) }
    }

    private suspend fun updateAdvertisement() {
        val local = identity ?: return
        if (serverPort <= 0) return
        runCatching { discovery.updateAdvertisement(advertisement(local, serverPort)) }
    }

    private fun updateAdvertisementAsync() {
        scope.launch { updateAdvertisement() }
    }

    private fun advertisement(local: DeviceIdentity, port: Int): DeviceAdvertisement {
        val state = _state.value
        return DeviceAdvertisement(
            deviceId = local.installId,
            name = local.name,
            platform = local.platform,
            port = port,
            state = when {
                state.pairing != null -> "pairing"
                state.session != null -> "paired"
                else -> "idle"
            },
        )
    }

    private fun conflictMessage(code: DeviceConnectErrorCode): String = when (code) {
        DeviceConnectErrorCode.CONFLICT -> "Stop casting or leave the Listen Together room before moving playback."
        else -> "This device cannot start a remote session now."
    }

    private data class SyncEvent(val connection: DeviceConnectConnection, val message: DeviceSyncMessage)

    private companion object {
        const val UPDATE_INTERVAL_MS = 500L
        const val AUTO_CONNECT_INTERVAL_MS = 20_000L
    }
}
