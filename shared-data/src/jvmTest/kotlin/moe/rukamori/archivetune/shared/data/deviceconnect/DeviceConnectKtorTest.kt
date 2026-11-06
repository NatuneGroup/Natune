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
            // Bounded: the handler runs on the engine's application job, so an unbounded wait here keeps that
            // job alive after the test and the engine's own shutdown then never joins it.
            runCatching { withTimeout(CLOSE_BUDGET_MS) { connection.awaitClosed() } }
        }
        val http = HttpClient(CIO)
        var initiator: DeviceConnectConnection? = null
        var responderConnection: DeviceConnectConnection? = null
        try {
            val port = withTimeout(30_000) {
                println("device-connect: starting loopback server")
                server.start().also { println("device-connect: loopback server on $it") }
            }
            assertTrue(port in 1..65_535)
            val channelA = withTimeout(10_000) {
                DeviceConnectClient(http).connect(DeviceEndpoint("127.0.0.1", port))
            }
            val a = DeviceConnectConnection(
                DeviceConnectRole.INITIATOR, identityA, JvmDeviceCrypto(), pairsA, channelA, scope, "Phone", listener = listenerA,
            )
            initiator = a
            a.start()
            val b = withTimeout(10_000) { responder.await() }
            responderConnection = b
            val prompt = withTimeout(10_000) {
                while (true) {
                    listenerB.failures.tryReceive().getOrNull()?.let { throw AssertionError("responder failed: ${it.code} ${it.message}") }
                    listenerB.prompts.tryReceive().getOrNull()?.let { return@withTimeout it }
                    delay(10)
                }
                error("unreachable")
            }
            assertEquals(6, prompt.code.length)
            withTimeout(10_000) { b.approvePairing() }
            withTimeout(10_000) {
                while (listenerA.authenticated == null || listenerB.authenticated == null) delay(10)
            }
            val entry = DeviceQueueEntry("occurrence-1", "youtube", "SONG", "song-1", "A song")
            // A websocket send suspends while the peer's buffers are full, so it needs the same budget as the
            // receive below instead of the run's lifetime.
            withTimeout(10_000) {
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
            }
            val received = withTimeout(10_000) { listenerB.sync.receive() }
            assertEquals(DeviceSyncMessageType.TRANSFER, received.type)
            assertEquals("session-1", received.sessionId)
            assertNotNull(received.snapshot)
            assertEquals(1, received.snapshot?.entries?.size)
            a.close()
            b.close()
        } finally {
            // Ordered, and every step is individually optional: close both peers before touching the engine, so no
            // handler is left suspended on a live connection, then drop the scope, then the transport. A failed
            // assertion above must not leave the engine's application job holding a connection that only the test
            // body would have closed.
            runCatching { initiator?.close() }
            runCatching { responderConnection?.close() }
            runCatching { scope.cancel() }
            runCatching { http.close() }
            runCatching { server.stop() }
        }
    }

    private companion object {
        /** How long a loopback connection may take to report itself closed. */
        const val CLOSE_BUDGET_MS = 30_000L
    }
}
