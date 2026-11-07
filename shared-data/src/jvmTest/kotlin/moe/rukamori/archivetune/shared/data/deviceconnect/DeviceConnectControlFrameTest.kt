package moe.rukamori.archivetune.shared.data.deviceconnect

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectRole
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceFrame
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceFrameCodec
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceIdentity
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceMessageType
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePlatform

/**
 * Before the key exchange only the hello and its error may reach the wire in the clear; a peer refuses
 * every other unsealed frame. A bye or a pair rejection written before the keys exist would therefore
 * tear the connection down with an invalid-frame error on the far side, so it is dropped and this side
 * closes locally instead.
 */
class DeviceConnectControlFrameTest {
    private val identity = DeviceIdentity("b".repeat(16), "Phone", DevicePlatform.ANDROID)

    @Test
    fun byeBeforeTheKeysExistIsNotWrittenToTheWire() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val listener = RecordingListener()
            val link = MemoryChannel()
            val connection = connection(link, scope, listener)
            connection.start()
            assertEquals(DeviceMessageType.HELLO_COMMIT, nextFrame(link).header.type)

            connection.sendBye()

            assertEquals(emptyList(), framesAfterTheHello(link))
            assertTrue(listener.closed)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun pairRejectionBeforeTheKeysExistIsNotWrittenToTheWire() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val listener = RecordingListener()
            val link = MemoryChannel()
            val connection = connection(link, scope, listener)
            connection.start()
            assertEquals(DeviceMessageType.HELLO_COMMIT, nextFrame(link).header.type)

            connection.rejectPairing()

            assertEquals(emptyList(), framesAfterTheHello(link))
            assertTrue(listener.closed)
        } finally {
            scope.cancel()
        }
    }

    private fun connection(link: MemoryChannel, scope: CoroutineScope, listener: RecordingListener) =
        DeviceConnectConnection(
            DeviceConnectRole.RESPONDER,
            identity,
            JvmDeviceCrypto(),
            MemoryPairRepository(),
            link,
            scope,
            "Laptop",
            listener = listener,
        )

    private suspend fun nextFrame(link: MemoryChannel): DeviceFrame = withTimeout(5_000) {
        while (true) {
            val bytes = link.wire.receiveCatching().getOrNull() ?: error("the connection wrote no frame")
            DeviceFrameCodec.decode(bytes)?.let { return@withTimeout it }
        }
        error("unreachable")
    }

    /** Every frame written before the connection closes its channel; nothing should follow the hello. */
    private suspend fun framesAfterTheHello(link: MemoryChannel): List<DeviceMessageType> = withTimeout(5_000) {
        val types = mutableListOf<DeviceMessageType>()
        while (true) {
            val bytes = link.wire.receiveCatching().getOrNull() ?: break
            DeviceFrameCodec.decode(bytes)?.let { types += it.header.type }
        }
        types
    }
}
