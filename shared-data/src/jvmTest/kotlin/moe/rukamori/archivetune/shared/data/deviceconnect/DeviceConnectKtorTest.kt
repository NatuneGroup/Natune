package moe.rukamori.archivetune.shared.data.deviceconnect

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.data.deviceconnect.DeviceConnectConnection
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectRole
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceEndpoint
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceIdentity
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePlatform
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceQueueEntry
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceSyncMessage
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceSyncMessageType

class DeviceConnectKtorTest {
    @Test
    fun twoInProcessPeersCompleteTheHandshakeAndTransferOverLoopback() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val identityA = DeviceIdentity("c".repeat(16), "Desktop", DevicePlatform.DESKTOP)
        val identityB = DeviceIdentity("d".repeat(16), "Phone", DevicePlatform.ANDROID)
        val pairsA = MemoryPairRepository()
        val pairsB = MemoryPairRepository()
        val listenerA = RecordingListener()
        val listenerB = RecordingListener()
        val responder = CompletableDeferred<DeviceConnectConnection>()
        val server = DeviceConnectServer { channel ->
            val connection = DeviceConnectConnection(
                DeviceConnectRole.RESPONDER, identityB, JvmDeviceCrypto(), pairsB, channel, scope, "Desktop", listener = listenerB,
            )
            responder.complete(connection)
            connection.start()
            connection.awaitClosed()
        }
        val http = HttpClient(CIO)
        try {
            val port = withTimeout(10_000) { server.start() }
            assertTrue(port in 1..65_535)
            val channelA = withTimeout(10_000) {
                DeviceConnectClient(http).connect(DeviceEndpoint("127.0.0.1", port))
            }
            val a = DeviceConnectConnection(
                DeviceConnectRole.INITIATOR, identityA, JvmDeviceCrypto(), pairsA, channelA, scope, "Phone", listener = listenerA,
            )
            a.start()
            val b = withTimeout(10_000) { responder.await() }
            val prompt = withTimeout(10_000) { listenerB.prompts.receive() }
            assertEquals(6, prompt.code.length)
            b.approvePairing()
            withTimeout(10_000) {
                while (listenerA.authenticated == null || listenerB.authenticated == null) delay(10)
            }
            val entry = DeviceQueueEntry("occurrence-1", "youtube", "SONG", "song-1", "A song")
            a.sendSync(
                DeviceSyncMessage(
                    DeviceSyncMessageType.TRANSFER,
                    sessionId = "session-1",
                    snapshot = DevicePlaybackSnapshot(
                        sessionId = "session-1",
                        queueRevision = 1,
                        trackRevision = 1,
                        entries = listOf(entry),
                        currentOccurrenceId = entry.occurrenceId,
                        playOrder = listOf(entry.occurrenceId),
                        playing = true,
                        positionMs = 1_000,
                        anchorEpochMs = 1,
                        queueHash = "hash",
                    ),
                ),
            )
            val received = withTimeout(10_000) { listenerB.sync.receive() }
            assertEquals(DeviceSyncMessageType.TRANSFER, received.type)
            assertEquals("session-1", received.sessionId)
            assertNotNull(received.snapshot)
            assertEquals(1, received.snapshot?.entries?.size)
            a.close()
            b.close()
        } finally {
            runCatching { http.close() }
            runCatching { server.stop() }
            scope.cancel()
        }
    }
}
