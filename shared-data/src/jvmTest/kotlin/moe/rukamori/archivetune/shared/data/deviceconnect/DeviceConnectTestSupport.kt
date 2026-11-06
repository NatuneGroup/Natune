package moe.rukamori.archivetune.shared.data.deviceconnect

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectError
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceFrame
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceFrameCodec
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceInfoPayload
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePairGrant
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePairInfo
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePairingPrompt
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceSyncMessage

internal class MemoryPairRepository : DevicePairRepository {
    private val grants = mutableMapOf<String, DevicePairGrant>()

    override suspend fun find(peerId: String): DevicePairGrant? = grants[peerId]

    override suspend fun list(): List<DevicePairInfo> = grants.values.map { it.info }

    override suspend fun save(grant: DevicePairGrant) {
        grants[grant.info.peerId] = grant
    }

    override suspend fun remove(peerId: String) {
        grants.remove(peerId)
    }

    fun secret(peerId: String): ByteArray? = grants[peerId]?.secret

    fun replaceSecret(peerId: String, secret: ByteArray) {
        val grant = grants[peerId] ?: return
        grants[peerId] = grant.copy(secret = secret)
    }
}

internal class MemoryChannel : DeviceFrameChannel {
    private val decoded = Channel<DeviceFrame>(Channel.UNLIMITED)
    val wire = Channel<ByteArray>(Channel.UNLIMITED)
    override val frames: Flow<DeviceFrame> = decoded.receiveAsFlow()

    override suspend fun send(bytes: ByteArray) {
        wire.send(bytes)
    }

    override suspend fun close(reason: String) {
        wire.close()
        decoded.close()
    }

    fun accept(bytes: ByteArray) {
        val frame = DeviceFrameCodec.decode(bytes)
        frame?.let { decoded.trySend(it) }
    }
}

internal fun loopbackChannels(scope: CoroutineScope): Pair<MemoryChannel, MemoryChannel> {
    val first = MemoryChannel()
    val second = MemoryChannel()
    scope.launch { for (bytes in first.wire) second.accept(bytes) }
    scope.launch { for (bytes in second.wire) first.accept(bytes) }
    return first to second
}

internal class RecordingListener : DeviceConnectConnection.Listener {
    val prompts = Channel<DevicePairingPrompt>(Channel.UNLIMITED)
    val sync = Channel<DeviceSyncMessage>(Channel.UNLIMITED)
    val failures = Channel<DeviceConnectError>(Channel.UNLIMITED)
    @Volatile var authenticated: DeviceInfoPayload? = null
    @Volatile var peer: DeviceInfoPayload? = null
    @Volatile var closed = false

    override fun onAuthenticated(peer: DeviceInfoPayload) {
        authenticated = peer
    }

    override fun onPeerInfo(peer: DeviceInfoPayload) {
        this.peer = peer
    }

    override fun onPairingPrompt(prompt: DevicePairingPrompt) {
        prompts.trySend(prompt)
    }

    override fun onPairingEnded() = Unit

    override fun onSync(message: DeviceSyncMessage) {
        sync.trySend(message)
    }

    override fun onFailure(error: DeviceConnectError) {
        failures.trySend(error)
    }

    override fun onClosed() {
        closed = true
    }
}
