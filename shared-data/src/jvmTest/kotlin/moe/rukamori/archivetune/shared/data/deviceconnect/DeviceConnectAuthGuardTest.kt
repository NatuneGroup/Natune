package moe.rukamori.archivetune.shared.data.deviceconnect

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.deviceconnect.AuthResultPayload
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectErrorCode
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectLimits
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectPairing
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectRole
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceFrame
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceFrameCodec
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceFrameHeader
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceIdentity
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceMessageType
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePlatform
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceWireJson
import moe.rukamori.archivetune.shared.domain.deviceconnect.HelloCommitPayload
import moe.rukamori.archivetune.shared.domain.deviceconnect.HelloPayload
import moe.rukamori.archivetune.shared.domain.deviceconnect.hexToBytesOrNull
import moe.rukamori.archivetune.shared.domain.deviceconnect.toHex

/**
 * The pre-authentication boundary: nothing but the plaintext hello may arrive before the key exchange,
 * and no pairing result may authenticate a peer that did not complete a verified pairing step. A raw peer
 * that speaks the wire format by hand stands in for a hostile host on the LAN.
 */
class DeviceConnectAuthGuardTest {
    private val peerId = "a".repeat(16)
    private val victimId = "b".repeat(16)
    private val victimIdentity = DeviceIdentity(victimId, "Phone", DevicePlatform.ANDROID)

    @Test
    fun plainTextAuthResultIsRejectedAndTheConnectionIsClosed() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val listener = RecordingListener()
            val link = MemoryChannel()
            val peer = RawPeer(link, scope, peerId)
            val victim = responderVictim(link, scope, listener)
            victim.start()
            completeHello(peer, victimId)

            peer.sendPlain(
                DeviceMessageType.AUTH_RESULT,
                DeviceWireJson.json.encodeToString(AuthResultPayload.serializer(), AuthResultPayload(ok = true)),
            )

            assertEquals(DeviceConnectErrorCode.INVALID_MESSAGE, awaitFailure(listener).code)
            awaitClosed(listener)
            assertNull(listener.authenticated)
            assertTrue(listener.closed)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun plainTextSessionMessageIsRejected() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val listener = RecordingListener()
            val link = MemoryChannel()
            val peer = RawPeer(link, scope, peerId)
            val victim = responderVictim(link, scope, listener)
            victim.start()
            completeHello(peer, victimId)

            peer.sendPlain(DeviceMessageType.PLAYBACK, """{"type":"STATE"}""")

            assertEquals(DeviceConnectErrorCode.INVALID_MESSAGE, awaitFailure(listener).code)
            awaitClosed(listener)
            assertNull(listener.authenticated)
            assertTrue(listener.sync.tryReceive().isFailure)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun keyExchangeAloneDoesNotAuthenticate() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val listener = RecordingListener()
            val link = MemoryChannel()
            val peer = RawPeer(link, scope, peerId)
            val victim = responderVictim(link, scope, listener)
            victim.start()
            val responderCommit = completeHello(peer, victimId)

            // The peer holds the ECDH keys and the transcript, so it can seal correctly; it simply never
            // passed a pairing step, and the responder is waiting for a pair request.
            peer.sendSealed(
                DeviceMessageType.AUTH_RESULT,
                DeviceWireJson.json.encodeToString(AuthResultPayload.serializer(), AuthResultPayload(ok = true)),
                responderCommit,
                victimId,
            )

            assertEquals(DeviceConnectErrorCode.INVALID_MESSAGE, awaitFailure(listener).code)
            awaitClosed(listener)
            assertNull(listener.authenticated)
            assertTrue(listener.closed)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun sealedSessionMessageBeforeAuthenticationIsRejected() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val listener = RecordingListener()
            val link = MemoryChannel()
            val peer = RawPeer(link, scope, peerId)
            val victim = responderVictim(link, scope, listener)
            victim.start()
            val responderCommit = completeHello(peer, victimId)

            peer.sendSealed(DeviceMessageType.PLAYBACK, """{"type":"STATE"}""", responderCommit, victimId)

            assertEquals(DeviceConnectErrorCode.INVALID_MESSAGE, awaitFailure(listener).code)
            awaitClosed(listener)
            assertNull(listener.authenticated)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun outOfOrderPairCommitIsRejected() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val listener = RecordingListener()
            val link = MemoryChannel()
            val peer = RawPeer(link, scope, peerId)
            val victim = responderVictim(link, scope, listener)
            victim.start()
            val responderCommit = completeHello(peer, victimId)

            // A responder accepts a commitment only after its user approved the pairing prompt.
            peer.sendSealed(DeviceMessageType.PAIR_COMMIT, """{"proof":"00"}""", responderCommit, victimId)

            assertEquals(DeviceConnectErrorCode.INVALID_MESSAGE, awaitFailure(listener).code)
            awaitClosed(listener)
            assertNull(listener.authenticated)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun pairApprovalStillAuthenticatesBothPeers() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val pairsA = MemoryPairRepository()
            val pairsB = MemoryPairRepository()
            val listenerA = RecordingListener()
            val listenerB = RecordingListener()
            val (channelA, channelB) = loopbackChannels(scope)
            val a = DeviceConnectConnection(
                DeviceConnectRole.INITIATOR,
                DeviceIdentity(peerId, "Laptop", DevicePlatform.DESKTOP),
                JvmDeviceCrypto(),
                pairsA,
                channelA,
                scope,
                "Phone",
                listener = listenerA,
            )
            val b = DeviceConnectConnection(
                DeviceConnectRole.RESPONDER,
                victimIdentity,
                JvmDeviceCrypto(),
                pairsB,
                channelB,
                scope,
                "Laptop",
                listener = listenerB,
            )
            a.start()
            b.start()
            val prompt = awaitPrompt(listenerB)
            assertEquals(6, prompt.code.length)
            assertFalse(prompt.isRequester)
            b.approvePairing()
            awaitAuthenticated(listenerA, listenerB)
            assertNotNull(pairsA.secret(victimId))
            assertNotNull(pairsB.secret(peerId))
            a.close()
            b.close()
        } finally {
            scope.cancel()
        }
    }

    private fun responderVictim(link: MemoryChannel, scope: CoroutineScope, listener: RecordingListener) =
        DeviceConnectConnection(
            DeviceConnectRole.RESPONDER,
            victimIdentity,
            JvmDeviceCrypto(),
            MemoryPairRepository(),
            link,
            scope,
            "Laptop",
            listener = listener,
        )

    /** Drives the commitment exchange by hand and returns the responder's hello commitment. */
    private suspend fun completeHello(peer: RawPeer, responderId: String): HelloCommitPayload {
        val responderCommitFrame = peer.nextFrame()
        assertEquals(DeviceMessageType.HELLO_COMMIT, responderCommitFrame.header.type)
        val responderCommit = DeviceWireJson.json.decodeFromString(
            HelloCommitPayload.serializer(),
            responderCommitFrame.payload.decodeToString(),
        )
        assertEquals(DeviceConnectRole.RESPONDER.wire, responderCommit.role)
        assertEquals(responderId, responderCommit.deviceId)
        peer.sendPlain(
            DeviceMessageType.HELLO_COMMIT,
            DeviceWireJson.json.encodeToString(
                HelloCommitPayload.serializer(),
                HelloCommitPayload(
                    role = DeviceConnectRole.INITIATOR.wire,
                    deviceId = peer.deviceId,
                    nonce = peer.nonce.toHex(),
                    publicKey = peer.keyPair.publicKey.toHex(),
                    commitment = peer.commitment.toHex(),
                ),
            ),
        )
        val responderHelloFrame = peer.nextFrame()
        assertEquals(DeviceMessageType.HELLO, responderHelloFrame.header.type)
        peer.sendPlain(
            DeviceMessageType.HELLO,
            DeviceWireJson.json.encodeToString(
                HelloPayload.serializer(),
                HelloPayload(
                    role = DeviceConnectRole.INITIATOR.wire,
                    deviceId = peer.deviceId,
                    nonce = peer.nonce.toHex(),
                    publicKey = peer.keyPair.publicKey.toHex(),
                ),
            ),
        )
        return responderCommit
    }

    private suspend fun awaitFailure(listener: RecordingListener) = withTimeout(5_000) {
        while (true) {
            listener.failures.tryReceive().getOrNull()?.let { return@withTimeout it }
            delay(10)
        }
        error("unreachable")
    }

    private suspend fun awaitClosed(listener: RecordingListener) = withTimeout(5_000) {
        while (!listener.closed) delay(10)
    }

    private suspend fun awaitPrompt(listener: RecordingListener) = withTimeout(5_000) {
        while (true) {
            listener.failures.tryReceive().getOrNull()?.let { throw AssertionError("connection failed: ${it.code} ${it.message}") }
            listener.prompts.tryReceive().getOrNull()?.let { return@withTimeout it }
            delay(10)
        }
        error("unreachable")
    }

    private suspend fun awaitAuthenticated(first: RecordingListener, second: RecordingListener) = withTimeout(5_000) {
        while (first.authenticated == null || second.authenticated == null) {
            first.failures.tryReceive().getOrNull()?.let { throw AssertionError("initiator failed: ${it.code} ${it.message}") }
            second.failures.tryReceive().getOrNull()?.let { throw AssertionError("responder failed: ${it.code} ${it.message}") }
            delay(10)
        }
    }

    /**
     * A hostile LAN host: it speaks the v1 wire format itself, so it can claim any device id, skip steps
     * and seal frames with the keys the key exchange handed it.
     */
    private class RawPeer(
        private val link: MemoryChannel,
        scope: CoroutineScope,
        val deviceId: String,
    ) {
        private val crypto = JvmDeviceCrypto()
        private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
        private var sequence = 0L

        val keyPair = crypto.generateKeyPair()
        val nonce = crypto.randomBytes(DeviceConnectLimits.NONCE_BYTES)
        val commitment = DeviceConnectPairing.commitment(DeviceConnectRole.INITIATOR, deviceId, nonce, keyPair.publicKey)

        init {
            scope.launch { for (bytes in link.wire) inbox.send(bytes) }
        }

        suspend fun nextFrame(): DeviceFrame = withTimeout(5_000) {
            while (true) {
                val bytes = inbox.receive()
                DeviceFrameCodec.decode(bytes)?.let { return@withTimeout it }
            }
            error("unreachable")
        }

        fun sendPlain(type: DeviceMessageType, payload: String) {
            sequence += 1
            link.accept(DeviceFrameCodec.encode(type, sequence, payload.encodeToByteArray()))
        }

        fun sendSealed(type: DeviceMessageType, payload: String, responderCommit: HelloCommitPayload, responderId: String) {
            sequence += 1
            val responderKey = responderCommit.publicKey.hexToBytesOrNull() ?: error("responder key")
            val transcript = DeviceConnectPairing.transcript(
                initiatorCommitment = commitment,
                responderCommitment = responderCommit.commitment.hexToBytesOrNull() ?: error("responder commitment"),
                initiatorId = deviceId,
                responderId = responderId,
                initiatorNonce = nonce,
                responderNonce = responderCommit.nonce.hexToBytesOrNull() ?: error("responder nonce"),
                initiatorPublicKey = keyPair.publicKey,
                responderPublicKey = responderKey,
            )
            val keys = DeviceConnectPairing.deriveKeys(crypto.sharedSecret(keyPair, responderKey), transcript)
            val key = DeviceConnectPairing.trafficKey(keys, DeviceConnectRole.INITIATOR)
            val nonce12 = ByteArray(DeviceConnectLimits.FRAME_NONCE_BYTES)
            for (i in 0 until 8) nonce12[4 + i] = (sequence ushr (56 - 8 * i)).toByte()
            val header = DeviceFrameHeader(
                DeviceConnectLimits.PROTOCOL_VERSION,
                type,
                DeviceFrameCodec.FLAG_ENCRYPTED,
                sequence,
            )
            val sealed = DeviceAesGcm.seal(key, nonce12, header.aad(transcript), payload.encodeToByteArray())
            link.accept(DeviceFrameCodec.encode(type, sequence, sealed, nonce12))
        }
    }
}
