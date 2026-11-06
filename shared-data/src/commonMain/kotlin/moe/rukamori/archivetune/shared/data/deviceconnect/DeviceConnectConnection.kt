package moe.rukamori.archivetune.shared.data.deviceconnect

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import moe.rukamori.archivetune.shared.domain.deviceconnect.AuthProofPayload
import moe.rukamori.archivetune.shared.domain.deviceconnect.AuthResultPayload
import moe.rukamori.archivetune.shared.domain.deviceconnect.ByePayload
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectError
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectErrorCode
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectLimits
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectPairing
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectRole
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceCrypto
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceFrame
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceFrameCodec
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceFrameHeader
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceHandshakePhase
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceHandshakeState
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceIdentity
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceInfoPayload
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceKeyPair
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceMessageType
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePairGrant
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePairInfo
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePairingPrompt
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePlatform
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceReplayWindow
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceSyncMessage
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceWireJson
import moe.rukamori.archivetune.shared.domain.deviceconnect.ErrorPayload
import moe.rukamori.archivetune.shared.domain.deviceconnect.HelloCommitPayload
import moe.rukamori.archivetune.shared.domain.deviceconnect.HelloPayload
import moe.rukamori.archivetune.shared.domain.deviceconnect.PairApprovePayload
import moe.rukamori.archivetune.shared.domain.deviceconnect.PairCommitPayload
import moe.rukamori.archivetune.shared.domain.deviceconnect.PairRejectPayload
import moe.rukamori.archivetune.shared.domain.deviceconnect.PairRequestPayload
import moe.rukamori.archivetune.shared.domain.deviceconnect.PingPayload
import moe.rukamori.archivetune.shared.domain.deviceconnect.PlaybackChunkPayload
import moe.rukamori.archivetune.shared.domain.deviceconnect.hexToBytesOrNull
import moe.rukamori.archivetune.shared.domain.deviceconnect.toHex
import kotlin.time.Clock

/**
 * One peer connection: commit-reveal, ephemeral P-256 ECDH, transcript-bound HKDF keys, mutual SAS,
 * HMAC proofs, AES-256-GCM frames and payload chunking. Session semantics stay in [DeviceConnectOwner];
 * this type only validates and carries a [DeviceSyncMessage].
 */
class DeviceConnectConnection(
    private val role: DeviceConnectRole,
    private val localIdentity: DeviceIdentity,
    private val crypto: DeviceCrypto,
    private val pairs: DevicePairRepository,
    private val channel: DeviceFrameChannel,
    private val scope: CoroutineScope,
    private val peerNameHint: String = "",
    private val nowEpochMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val listener: Listener,
) {
    interface Listener {
        fun onAuthenticated(peer: DeviceInfoPayload)
        fun onPeerInfo(peer: DeviceInfoPayload)
        fun onPairingPrompt(prompt: DevicePairingPrompt)
        fun onPairingEnded()
        fun onSync(message: DeviceSyncMessage)
        fun onFailure(error: DeviceConnectError)
        fun onClosed()
    }

    private val handshake = DeviceHandshakeState(role)
    private val replay = DeviceReplayWindow()
    private val sendMutex = Mutex()
    private var sequence = 0L
    private var noncePrefix: ByteArray = ByteArray(0)
    private var keyPair: DeviceKeyPair? = null
    private var localNonce: ByteArray = ByteArray(0)
    private var localCommitment: ByteArray = ByteArray(0)
    private var peerCommitment: ByteArray? = null
    private var peerIdFromCommit: String? = null
    private var peerPublicKey: ByteArray? = null
    private var peerNonce: ByteArray? = null
    private var transcript: ByteArray? = null
    private var keys: DeviceConnectPairing.DerivedKeys? = null
    private var storedSecret: ByteArray? = null
    private var newPairSecret: ByteArray? = null
    private var authenticated = false
    private var peerInfo: DeviceInfoPayload? = null
    private var deviceInfoSent = false
    private var started = false
    private var closed = false
    private var startedAtMs = 0L
    private var lastReceivedMs = 0L
    private var lastPingMs = 0L
    private var receiveJob: Job? = null
    private var watchdogJob: Job? = null
    private val chunks = mutableMapOf<String, ChunkAssembly>()
    private val closedSignal = CompletableDeferred<Unit>()

    val peerDeviceId: String? get() = handshake.peerDeviceId

    fun start() {
        if (started) return
        started = true
        startedAtMs = nowEpochMs()
        lastReceivedMs = startedAtMs
        runCatching {
            val pair = crypto.generateKeyPair()
            keyPair = pair
            noncePrefix = crypto.randomBytes(4)
            localNonce = crypto.randomBytes(DeviceConnectLimits.NONCE_BYTES)
            localCommitment = DeviceConnectPairing.commitment(role, localIdentity.installId, localNonce, pair.publicKey)
            scope.launch {
                runCatching { sendHelloCommit(pair.publicKey) }
                    .onFailure { fail(DeviceConnectErrorCode.INVALID_MESSAGE, "Could not start the encrypted connection") }
            }
        }.onFailure { fail(DeviceConnectErrorCode.INVALID_MESSAGE, "Could not start the encrypted connection") }
        if (closed) return
        receiveJob = scope.launch {
            try {
                channel.frames.collect { onFrame(it) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (!closed) fail(DeviceConnectErrorCode.UNAVAILABLE, "The device connection closed")
            }
            if (!closed) closeInternal()
        }
        watchdogJob = scope.launch {
            while (isActive && !closed) {
                delay(1_000)
                watchdog()
            }
        }
    }

    suspend fun approvePairing() {
        if (closed || handshake.phase != DeviceHandshakePhase.AWAITING_PAIR_APPROVAL) return
        if (role != DeviceConnectRole.RESPONDER) return
        val secret = newPairSecret ?: return
        val peerId = peerDeviceId ?: return
        val hash = transcript ?: return
        val proof = DeviceConnectPairing.proof(secret, "approve", role, localIdentity.installId, peerId, hash)
        handshake.awaitingPairCommit()
        listener.onPairingEnded()
        sendControl(
            DeviceMessageType.PAIR_APPROVE,
            DeviceWireJson.json.encodeToString(PairApprovePayload.serializer(), PairApprovePayload(proof.toHex())),
        )
    }

    suspend fun rejectPairing() {
        if (closed) return
        runCatching {
            sendControl(
                DeviceMessageType.PAIR_REJECT,
                DeviceWireJson.json.encodeToString(PairRejectPayload.serializer(), PairRejectPayload("rejected")),
            )
        }
        listener.onPairingEnded()
        closeInternal()
    }

    suspend fun sendSync(message: DeviceSyncMessage) {
        if (!authenticated || closed) throw IllegalStateException("Device Connect is not authenticated")
        val bytes = DeviceWireJson.json.encodeToString(DeviceSyncMessage.serializer(), message).encodeToByteArray()
        if (bytes.size > DeviceConnectLimits.MAX_PAYLOAD_BYTES) {
            throw IllegalArgumentException("payload exceeds the Device Connect limit")
        }
        if (fitsFrame(bytes.size)) {
            sendEncryptedBytes(DeviceMessageType.PLAYBACK, bytes)
            return
        }
        val messageId = crypto.randomBytes(8).toHex()
        val count = (bytes.size + DeviceConnectLimits.CHUNK_BYTES - 1) / DeviceConnectLimits.CHUNK_BYTES
        for (index in 0 until count) {
            val from = index * DeviceConnectLimits.CHUNK_BYTES
            val to = minOf(bytes.size, from + DeviceConnectLimits.CHUNK_BYTES)
            val chunk = PlaybackChunkPayload(
                messageId = messageId,
                index = index,
                count = count,
                totalBytes = bytes.size,
                data = bytes.copyOfRange(from, to).toHex(),
            )
            sendEncrypted(DeviceMessageType.PLAYBACK_CHUNK, chunk)
        }
    }

    suspend fun sendBye(reason: String = "closed") {
        if (!closed) {
            runCatching {
                sendControl(
                    DeviceMessageType.BYE,
                    DeviceWireJson.json.encodeToString(ByePayload.serializer(), ByePayload(reason)),
                )
            }
        }
        closeInternal()
    }

    fun close() {
        closeInternal()
    }

    suspend fun awaitClosed() {
        closedSignal.await()
    }

    private fun watchdog() {
        val now = nowEpochMs()
        val waitingForApproval = handshake.phase == DeviceHandshakePhase.AWAITING_PAIR_APPROVAL ||
            handshake.phase == DeviceHandshakePhase.AWAITING_PAIR_COMMIT
        if (!authenticated && !waitingForApproval && now - startedAtMs > DeviceConnectLimits.HANDSHAKE_TIMEOUT_MS) {
            fail(DeviceConnectErrorCode.TIMEOUT, "The device did not finish pairing in time")
            return
        }
        if (handshake.pairingExpired(now)) {
            fail(DeviceConnectErrorCode.TIMEOUT, "Pairing approval timed out")
            return
        }
        if (authenticated && now - lastReceivedMs > DeviceConnectLimits.HEARTBEAT_TIMEOUT_MS) {
            fail(DeviceConnectErrorCode.TIMEOUT, "The device stopped responding")
            return
        }
        if (authenticated && now - lastPingMs >= DeviceConnectLimits.HEARTBEAT_INTERVAL_MS) {
            lastPingMs = now
            scope.launch {
                runCatching {
                    sendEncryptedBytes(
                        DeviceMessageType.PING,
                        DeviceWireJson.json.encodeToString(PingPayload.serializer(), PingPayload(now)).encodeToByteArray(),
                    )
                }
            }
        }
    }

    private suspend fun onFrame(frame: DeviceFrame) {
        if (closed) return
        lastReceivedMs = nowEpochMs()
        if (!replay.accept(frame.header.seq)) {
            fail(DeviceConnectErrorCode.INVALID_MESSAGE, "A replayed frame was refused")
            return
        }
        if (frame.header.encrypted) {
            onEncrypted(frame)
        } else {
            if (!handshake.allowsPlainText(frame.header.type)) {
                fail(DeviceConnectErrorCode.INVALID_MESSAGE, "An unencrypted frame arrived after the handshake")
                return
            }
            onPlain(frame)
        }
    }

    private suspend fun onPlain(frame: DeviceFrame) {
        val text = runCatching { frame.payload.decodeToString() }.getOrNull()
        if (text == null) {
            fail(DeviceConnectErrorCode.INVALID_MESSAGE, "A handshake frame was not UTF-8")
            return
        }
        try {
            when (frame.header.type) {
                DeviceMessageType.HELLO_COMMIT ->
                    onHelloCommit(DeviceWireJson.json.decodeFromString(HelloCommitPayload.serializer(), text))
                DeviceMessageType.HELLO ->
                    onHello(DeviceWireJson.json.decodeFromString(HelloPayload.serializer(), text))
                DeviceMessageType.ERROR -> onPeerError(DeviceWireJson.json.decodeFromString(ErrorPayload.serializer(), text))
                else -> fail(DeviceConnectErrorCode.INVALID_MESSAGE, "Unexpected unencrypted frame")
            }
        } catch (_: IllegalArgumentException) {
            fail(DeviceConnectErrorCode.INVALID_MESSAGE, "Malformed handshake payload")
        } catch (_: kotlinx.serialization.SerializationException) {
            fail(DeviceConnectErrorCode.INVALID_MESSAGE, "Malformed handshake payload")
        }
    }

    /** A peer error closes the connection, except for the one case that means "re-pair with me". */
    private suspend fun onPeerError(payload: ErrorPayload) {
        val error = payload.toError()
        if (error.code == DeviceConnectErrorCode.UNAUTHENTICATED && role == DeviceConnectRole.INITIATOR && !authenticated) {
            sendPairRequest()
            return
        }
        fail(error.code, error.message)
    }

    private suspend fun onEncrypted(frame: DeviceFrame) {
        if (!handshake.allowsEncrypted(frame.header.type)) {
            fail(DeviceConnectErrorCode.INVALID_MESSAGE, "An encrypted frame arrived out of order")
            return
        }
        val nonce = frame.nonce ?: return fail(DeviceConnectErrorCode.INVALID_MESSAGE, "Encrypted frame without a nonce")
        val hash = transcript ?: return fail(DeviceConnectErrorCode.UNAUTHENTICATED, "No transcript")
        val derived = keys ?: return fail(DeviceConnectErrorCode.UNAUTHENTICATED, "No session keys")
        val incoming = if (role == DeviceConnectRole.INITIATOR) derived.responderToInitiator else derived.initiatorToResponder
        val plaintext = DeviceAesGcm.open(incoming, nonce, frame.header.aad(hash), frame.payload)
            ?: return fail(DeviceConnectErrorCode.UNAUTHENTICATED, "A frame failed authentication")
        val text = runCatching { plaintext.decodeToString() }.getOrNull()
            ?: return fail(DeviceConnectErrorCode.INVALID_MESSAGE, "An encrypted frame was not UTF-8")
        try {
            when (frame.header.type) {
                DeviceMessageType.AUTH_PROOF ->
                    onAuthProof(DeviceWireJson.json.decodeFromString(AuthProofPayload.serializer(), text))
                DeviceMessageType.PAIR_REQUEST ->
                    onPairRequest(DeviceWireJson.json.decodeFromString(PairRequestPayload.serializer(), text))
                DeviceMessageType.PAIR_APPROVE ->
                    onPairApprove(DeviceWireJson.json.decodeFromString(PairApprovePayload.serializer(), text))
                DeviceMessageType.PAIR_COMMIT ->
                    onPairCommit(DeviceWireJson.json.decodeFromString(PairCommitPayload.serializer(), text))
                DeviceMessageType.AUTH_RESULT ->
                    onAuthResult(DeviceWireJson.json.decodeFromString(AuthResultPayload.serializer(), text))
                DeviceMessageType.PLAYBACK -> onSyncPayload(plaintext)
                DeviceMessageType.PLAYBACK_CHUNK ->
                    onPlaybackChunk(DeviceWireJson.json.decodeFromString(PlaybackChunkPayload.serializer(), text))
                DeviceMessageType.PING -> {
                    scope.launch {
                        runCatching {
                            sendEncryptedBytes(
                                DeviceMessageType.PONG,
                                DeviceWireJson.json.encodeToString(PingPayload.serializer(), PingPayload(nowEpochMs())).encodeToByteArray(),
                            )
                        }
                    }
                    lastReceivedMs = nowEpochMs()
                }
                DeviceMessageType.PONG -> Unit
                DeviceMessageType.BYE -> closeInternal()
                DeviceMessageType.PAIR_REJECT -> {
                    listener.onPairingEnded()
                    fail(DeviceConnectErrorCode.NOT_ALLOWED, "The other device rejected pairing")
                }
                DeviceMessageType.ERROR ->
                    onPeerError(DeviceWireJson.json.decodeFromString(ErrorPayload.serializer(), text))
                DeviceMessageType.DEVICE_INFO ->
                    onDeviceInfo(DeviceWireJson.json.decodeFromString(DeviceInfoPayload.serializer(), text))
                else -> fail(DeviceConnectErrorCode.INVALID_MESSAGE, "Unexpected encrypted frame")
            }
        } catch (_: IllegalArgumentException) {
            fail(DeviceConnectErrorCode.INVALID_MESSAGE, "Malformed session payload")
        } catch (_: kotlinx.serialization.SerializationException) {
            fail(DeviceConnectErrorCode.INVALID_MESSAGE, "Malformed session payload")
        }
    }

    private suspend fun onHelloCommit(payload: HelloCommitPayload) {
        if (payload.version != DeviceConnectLimits.PROTOCOL_VERSION) {
            return fail(DeviceConnectErrorCode.UNSUPPORTED_VERSION, "This device speaks another Device Connect version")
        }
        if (payload.role != role.peer().wire || !DeviceConnectPairing.validHello(payload.nonce, payload.publicKey, payload.deviceId)) {
            return fail(DeviceConnectErrorCode.INVALID_MESSAGE, "The hello commitment is invalid")
        }
        val nonce = payload.nonce.hexToBytesOrNull() ?: return fail(DeviceConnectErrorCode.INVALID_MESSAGE, "Bad nonce")
        val publicKey = payload.publicKey.hexToBytesOrNull() ?: return fail(DeviceConnectErrorCode.INVALID_MESSAGE, "Bad key")
        val commitment = payload.commitment.hexToBytesOrNull() ?: return fail(DeviceConnectErrorCode.INVALID_MESSAGE, "Bad commitment")
        val expected = DeviceConnectPairing.commitment(role.peer(), payload.deviceId, nonce, publicKey)
        if (!DeviceConnectPairing.verifyCommitment(expected, commitment)) {
            return fail(DeviceConnectErrorCode.INVALID_MESSAGE, "The hello commitment did not verify")
        }
        peerCommitment = expected
        peerIdFromCommit = payload.deviceId
        if (!handshake.onHelloCommit()) return fail(DeviceConnectErrorCode.INVALID_MESSAGE, "Duplicate hello commitment")
        sendLocalHello()
    }

    private suspend fun sendLocalHello() {
        val pair = keyPair ?: return
        sendPlain(
            DeviceMessageType.HELLO,
            HelloPayload(
                role = role.wire,
                deviceId = localIdentity.installId,
                nonce = localNonce.toHex(),
                publicKey = pair.publicKey.toHex(),
            ),
        )
    }

    private suspend fun onHello(payload: HelloPayload) {
        if (payload.version != DeviceConnectLimits.PROTOCOL_VERSION) {
            return fail(DeviceConnectErrorCode.UNSUPPORTED_VERSION, "This device speaks another Device Connect version")
        }
        if (payload.role != role.peer().wire || payload.deviceId != peerIdFromCommit) {
            return fail(DeviceConnectErrorCode.INVALID_MESSAGE, "The hello identity changed")
        }
        if (!DeviceConnectPairing.validHello(payload.nonce, payload.publicKey, payload.deviceId)) {
            return fail(DeviceConnectErrorCode.INVALID_MESSAGE, "The hello is invalid")
        }
        val peerNonceBytes = payload.nonce.hexToBytesOrNull() ?: return fail(DeviceConnectErrorCode.INVALID_MESSAGE, "Bad nonce")
        val peerKeyBytes = payload.publicKey.hexToBytesOrNull() ?: return fail(DeviceConnectErrorCode.INVALID_MESSAGE, "Bad key")
        val expected = DeviceConnectPairing.commitment(role.peer(), payload.deviceId, peerNonceBytes, peerKeyBytes)
        if (!DeviceConnectPairing.verifyCommitment(expected, peerCommitment ?: return fail(DeviceConnectErrorCode.INVALID_MESSAGE, "No commitment"))) {
            return fail(DeviceConnectErrorCode.INVALID_MESSAGE, "The hello does not match its commitment")
        }
        val pair = keyPair ?: return fail(DeviceConnectErrorCode.INVALID_MESSAGE, "No local key")
        peerNonce = peerNonceBytes
        peerPublicKey = peerKeyBytes
        if (!handshake.onHello(payload.deviceId)) return fail(DeviceConnectErrorCode.INVALID_MESSAGE, "Duplicate hello")
        val hash = transcriptFor(pair.publicKey, peerKeyBytes, peerNonceBytes) ?: return
        val shared = runCatching { crypto.sharedSecret(pair, peerKeyBytes) }.getOrElse {
            return fail(DeviceConnectErrorCode.INVALID_MESSAGE, "The peer key was refused")
        }
        val derived = DeviceConnectPairing.deriveKeys(shared, hash)
        transcript = hash
        keys = derived
        newPairSecret = derived.pairSecret
        val grant = pairs.find(payload.deviceId)
        if (grant != null) {
            storedSecret = grant.secret
            sendAuthProof()
        } else if (role == DeviceConnectRole.INITIATOR) {
            sendPairRequest()
        }
    }

    private fun transcriptFor(localPublic: ByteArray, peerPublic: ByteArray, peerNonceBytes: ByteArray): ByteArray? {
        val commit = peerCommitment ?: return null
        val peerId = peerIdFromCommit ?: return null
        return if (role == DeviceConnectRole.INITIATOR) {
            DeviceConnectPairing.transcript(
                initiatorCommitment = localCommitment,
                responderCommitment = commit,
                initiatorId = localIdentity.installId,
                responderId = peerId,
                initiatorNonce = localNonce,
                responderNonce = peerNonceBytes,
                initiatorPublicKey = localPublic,
                responderPublicKey = peerPublic,
            )
        } else {
            DeviceConnectPairing.transcript(
                initiatorCommitment = commit,
                responderCommitment = localCommitment,
                initiatorId = peerId,
                responderId = localIdentity.installId,
                initiatorNonce = peerNonceBytes,
                responderNonce = localNonce,
                initiatorPublicKey = peerPublic,
                responderPublicKey = localPublic,
            )
        }
    }

    private suspend fun sendHelloCommit(publicKey: ByteArray) {
        sendPlain(
            DeviceMessageType.HELLO_COMMIT,
            HelloCommitPayload(
                role = role.wire,
                deviceId = localIdentity.installId,
                nonce = localNonce.toHex(),
                publicKey = publicKey.toHex(),
                commitment = localCommitment.toHex(),
            ),
        )
    }

    private suspend fun sendAuthProof() {
        val secret = storedSecret ?: return
        val peerId = peerDeviceId ?: return
        val hash = transcript ?: return
        val proof = DeviceConnectPairing.proof(secret, "auth", role, localIdentity.installId, peerId, hash)
        sendEncrypted(
            DeviceMessageType.AUTH_PROOF,
            AuthProofPayload(role.wire, localIdentity.installId, peerId, proof.toHex()),
        )
    }

    private suspend fun sendPairRequest() {
        if (role != DeviceConnectRole.INITIATOR) return
        val secret = newPairSecret ?: return
        val peerId = peerDeviceId ?: return
        val sas = DeviceConnectPairing.sas(secret)
        if (!handshake.awaitingPairApproval(nowEpochMs())) return
        sendEncrypted(DeviceMessageType.PAIR_REQUEST, PairRequestPayload(sas, true, localIdentity.installId))
        listener.onPairingPrompt(
            DevicePairingPrompt(
                peerId = peerId,
                peerName = peerNameHint,
                peerPlatform = DevicePlatform.UNKNOWN,
                code = sas,
                expiresAtEpochMs = nowEpochMs() + DeviceConnectLimits.PAIRING_TIMEOUT_MS,
                isRequester = true,
            ),
        )
    }

    private suspend fun onAuthProof(payload: AuthProofPayload) {
        if (!handshake.canAcceptAuthProof()) {
            return fail(DeviceConnectErrorCode.INVALID_MESSAGE, "An authentication proof arrived out of order")
        }
        val peerId = peerDeviceId ?: return fail(DeviceConnectErrorCode.UNAUTHENTICATED, "No peer")
        val hash = transcript ?: return fail(DeviceConnectErrorCode.UNAUTHENTICATED, "No transcript")
        if (payload.senderId != peerId || payload.receiverId != localIdentity.installId || payload.role != role.peer().wire) {
            return fail(DeviceConnectErrorCode.UNAUTHENTICATED, "The proof named another connection")
        }
        val secret = storedSecret
        if (secret == null) {
            // The peer kept a grant we no longer have. Tell it to re-pair; it answers with a pair request.
            if (role == DeviceConnectRole.RESPONDER) {
                sendControl(
                    DeviceMessageType.ERROR,
                    DeviceWireJson.json.encodeToString(ErrorPayload.serializer(), ErrorPayload(DeviceConnectErrorCode.UNAUTHENTICATED.name, "re-pair")),
                )
            }
            return
        }
        val proof = payload.proof.hexToBytesOrNull() ?: return fail(DeviceConnectErrorCode.UNAUTHENTICATED, "Bad proof")
        val expected = DeviceConnectPairing.proof(secret, "auth", role.peer(), peerId, localIdentity.installId, hash)
        if (!DeviceConnectPairing.verify(expected, proof)) {
            fail(DeviceConnectErrorCode.UNAUTHENTICATED, "The stored pair secret did not verify")
            return
        }
        markAuthenticated()
    }

    private suspend fun onPairRequest(payload: PairRequestPayload) {
        if (role != DeviceConnectRole.RESPONDER) return fail(DeviceConnectErrorCode.INVALID_MESSAGE, "Only the responder approves a pair request")
        if (!handshake.canAcceptPairRequest()) return fail(DeviceConnectErrorCode.INVALID_MESSAGE, "A pair request arrived out of order")
        val peerId = peerDeviceId ?: return fail(DeviceConnectErrorCode.UNAUTHENTICATED, "No peer")
        if (payload.senderId != peerId) return fail(DeviceConnectErrorCode.INVALID_MESSAGE, "The request named another peer")
        val secret = newPairSecret ?: return fail(DeviceConnectErrorCode.UNAUTHENTICATED, "No pair secret")
        val sas = DeviceConnectPairing.sas(secret)
        if (!DeviceConnectPairing.matchesSas(sas, payload.sas)) {
            return fail(DeviceConnectErrorCode.SAS_MISMATCH, "The pairing codes do not match")
        }
        if (!handshake.awaitingPairApproval(nowEpochMs())) return fail(DeviceConnectErrorCode.INVALID_MESSAGE, "Unexpected pair request")
        listener.onPairingPrompt(
            DevicePairingPrompt(
                peerId = peerId,
                peerName = peerInfo?.name ?: peerNameHint,
                peerPlatform = DevicePlatform.fromWire(peerInfo?.platform),
                code = sas,
                expiresAtEpochMs = nowEpochMs() + DeviceConnectLimits.PAIRING_TIMEOUT_MS,
                isRequester = false,
            ),
        )
    }

    private suspend fun onPairApprove(payload: PairApprovePayload) {
        if (role != DeviceConnectRole.INITIATOR) return fail(DeviceConnectErrorCode.INVALID_MESSAGE, "Only the initiator accepts approval")
        if (!handshake.canAcceptPairApprove()) return fail(DeviceConnectErrorCode.INVALID_MESSAGE, "A pairing approval arrived out of order")
        val peerId = peerDeviceId ?: return fail(DeviceConnectErrorCode.UNAUTHENTICATED, "No peer")
        val hash = transcript ?: return fail(DeviceConnectErrorCode.UNAUTHENTICATED, "No transcript")
        val secret = newPairSecret ?: return fail(DeviceConnectErrorCode.UNAUTHENTICATED, "No pair secret")
        val proof = payload.proof.hexToBytesOrNull() ?: return fail(DeviceConnectErrorCode.UNAUTHENTICATED, "Bad proof")
        val expected = DeviceConnectPairing.proof(secret, "approve", role.peer(), peerId, localIdentity.installId, hash)
        if (!DeviceConnectPairing.verify(expected, proof)) {
            return fail(DeviceConnectErrorCode.SAS_MISMATCH, "The approval proof did not verify")
        }
        if (!handshake.awaitingPairCommit()) return fail(DeviceConnectErrorCode.INVALID_MESSAGE, "Unexpected pair approval")
        storeGrant()
        val commit = DeviceConnectPairing.proof(secret, "commit", role, localIdentity.installId, peerId, hash)
        sendEncrypted(DeviceMessageType.PAIR_COMMIT, PairCommitPayload(commit.toHex()))
    }

    private suspend fun onPairCommit(payload: PairCommitPayload) {
        if (role != DeviceConnectRole.RESPONDER) return fail(DeviceConnectErrorCode.INVALID_MESSAGE, "Only the responder accepts a commit")
        if (!handshake.canAcceptPairCommit()) return fail(DeviceConnectErrorCode.INVALID_MESSAGE, "A pair commitment arrived out of order")
        val peerId = peerDeviceId ?: return fail(DeviceConnectErrorCode.UNAUTHENTICATED, "No peer")
        val hash = transcript ?: return fail(DeviceConnectErrorCode.UNAUTHENTICATED, "No transcript")
        val secret = newPairSecret ?: return fail(DeviceConnectErrorCode.UNAUTHENTICATED, "No pair secret")
        val proof = payload.proof.hexToBytesOrNull() ?: return fail(DeviceConnectErrorCode.UNAUTHENTICATED, "Bad proof")
        val expected = DeviceConnectPairing.proof(secret, "commit", role.peer(), peerId, localIdentity.installId, hash)
        if (!DeviceConnectPairing.verify(expected, proof)) {
            return fail(DeviceConnectErrorCode.SAS_MISMATCH, "The commit proof did not verify")
        }
        storeGrant()
        // The result is the responder's confirmation, so it only travels sealed; the initiator accepts it
        // solely from the pair-commit phase, after it verified the approval proof that bound the same secret.
        sendEncrypted(DeviceMessageType.AUTH_RESULT, AuthResultPayload(ok = true))
        markAuthenticated()
    }

    private suspend fun onAuthResult(payload: AuthResultPayload) {
        if (role != DeviceConnectRole.INITIATOR || !handshake.canAcceptAuthResult()) {
            return fail(DeviceConnectErrorCode.INVALID_MESSAGE, "An authentication result arrived out of order")
        }
        if (!payload.ok) return fail(DeviceConnectErrorCode.UNAUTHENTICATED, payload.error ?: "The other device refused authentication")
        markAuthenticated()
    }

    private fun onDeviceInfo(payload: DeviceInfoPayload) {
        if (!authenticated) return fail(DeviceConnectErrorCode.UNAUTHENTICATED, "Device info arrived before authentication")
        if (peerDeviceId != null && payload.deviceId != peerDeviceId) return
        peerInfo = payload
        listener.onPeerInfo(payload)
        listener.onAuthenticated(payload)
    }

    private suspend fun storeGrant() {
        val peerId = peerDeviceId ?: return
        val secret = newPairSecret ?: return
        val now = nowEpochMs()
        val info = DevicePairInfo(
            peerId = peerId,
            peerName = (peerInfo?.name ?: peerNameHint).ifBlank { peerId.take(8) },
            peerPlatform = DevicePlatform.fromWire(peerInfo?.platform),
            peerModel = peerInfo?.model.orEmpty(),
            pairedAtEpochMs = now,
            lastUsedAtEpochMs = now,
        )
        pairs.save(DevicePairGrant(info, secret))
        storedSecret = secret
    }

    /**
     * The single door to the authenticated state. Every caller has already verified a proof against the
     * pairing secret and the transcript, and the phase check is the second lock: a hello-level peer that
     * only completed the key exchange can never reach this method.
     */
    private fun markAuthenticated() {
        if (authenticated) return
        if (!handshake.canAuthenticate()) {
            fail(DeviceConnectErrorCode.INVALID_MESSAGE, "The connection tried to authenticate out of order")
            return
        }
        authenticated = true
        handshake.authenticated()
        listener.onPairingEnded()
        sendDeviceInfoOnce()
        listener.onAuthenticated(
            peerInfo ?: DeviceInfoPayload(
                deviceId = peerDeviceId.orEmpty(),
                name = peerNameHint,
                platform = DevicePlatform.UNKNOWN.wire,
            ),
        )
    }

    private fun sendDeviceInfoOnce() {
        if (deviceInfoSent) return
        deviceInfoSent = true
        scope.launch {
            runCatching {
                sendEncrypted(
                    DeviceMessageType.DEVICE_INFO,
                    DeviceInfoPayload(
                        deviceId = localIdentity.installId,
                        name = localIdentity.name,
                        platform = localIdentity.platform.wire,
                        model = localIdentity.model,
                        appVersion = localIdentity.appVersion,
                    ),
                )
            }
        }
    }

    private fun onSyncPayload(bytes: ByteArray) {
        if (bytes.size > DeviceConnectLimits.MAX_PAYLOAD_BYTES) {
            return fail(DeviceConnectErrorCode.TOO_LARGE, "This queue is too large to sync.")
        }
        val message = runCatching {
            DeviceWireJson.json.decodeFromString(DeviceSyncMessage.serializer(), bytes.decodeToString())
        }.getOrNull() ?: return fail(DeviceConnectErrorCode.INVALID_MESSAGE, "Malformed session message")
        listener.onSync(message)
    }

    private fun onPlaybackChunk(payload: PlaybackChunkPayload) {
        if (payload.messageId.length !in 8..64) return fail(DeviceConnectErrorCode.INVALID_MESSAGE, "Malformed chunk id")
        if (chunks.size > 8 && payload.messageId !in chunks) return fail(DeviceConnectErrorCode.TOO_LARGE, "Too many concurrent transfers")
        if (payload.count !in 1..MAX_CHUNKS || payload.index !in 0 until payload.count) {
            return fail(DeviceConnectErrorCode.INVALID_MESSAGE, "Malformed playback chunk")
        }
        if (payload.totalBytes !in 1..DeviceConnectLimits.MAX_PAYLOAD_BYTES) {
            return fail(DeviceConnectErrorCode.TOO_LARGE, "This queue is too large to sync.")
        }
        val data = payload.data.hexToBytesOrNull() ?: return fail(DeviceConnectErrorCode.INVALID_MESSAGE, "Malformed chunk data")
        if (data.size > DeviceConnectLimits.CHUNK_BYTES) return fail(DeviceConnectErrorCode.INVALID_MESSAGE, "Oversized chunk")
        val now = nowEpochMs()
        val assembly = chunks.getOrPut(payload.messageId) { ChunkAssembly(now, payload.count, payload.totalBytes) }
        if (assembly.count != payload.count || assembly.totalBytes != payload.totalBytes) {
            chunks.remove(payload.messageId)
            return fail(DeviceConnectErrorCode.INVALID_MESSAGE, "Chunk metadata changed")
        }
        if (now - assembly.startedAtMs > DeviceConnectLimits.TRANSFER_TIMEOUT_MS) {
            chunks.remove(payload.messageId)
            return fail(DeviceConnectErrorCode.TIMEOUT, "The queue transfer timed out")
        }
        if (assembly.parts.put(payload.index, data) != null) {
            chunks.remove(payload.messageId)
            return fail(DeviceConnectErrorCode.INVALID_MESSAGE, "Duplicate chunk")
        }
        if (assembly.parts.size == assembly.count) {
            chunks.remove(payload.messageId)
            val bytes = ByteArray(assembly.totalBytes)
            var offset = 0
            for (index in 0 until assembly.count) {
                val part = assembly.parts[index] ?: return fail(DeviceConnectErrorCode.INVALID_MESSAGE, "Missing chunk")
                part.copyInto(bytes, offset)
                offset += part.size
            }
            if (offset != assembly.totalBytes) return fail(DeviceConnectErrorCode.INVALID_MESSAGE, "Chunk size mismatch")
            onSyncPayload(bytes)
        }
    }

    private suspend inline fun <reified T> sendPlain(type: DeviceMessageType, payload: T) {
        sendPlainText(type, DeviceWireJson.json.encodeToString(payload))
    }

    private suspend fun sendPlainText(type: DeviceMessageType, payload: String) =
        sendPlainBytes(type, payload.encodeToByteArray())

    private suspend fun sendPlainBytes(type: DeviceMessageType, payload: ByteArray) {
        sendMutex.withLock {
            if (closed) return
            val seq = nextSequence()
            channel.send(DeviceFrameCodec.encode(type, seq, payload))
        }
    }

    private suspend inline fun <reified T> sendEncrypted(type: DeviceMessageType, payload: T) {
        sendEncryptedBytes(type, DeviceWireJson.json.encodeToString(payload).encodeToByteArray())
    }

    private suspend fun sendEncryptedBytes(type: DeviceMessageType, payload: ByteArray) {
        val hash = transcript ?: return
        val derived = keys ?: return
        val senderKey = DeviceConnectPairing.trafficKey(derived, role)
        sendMutex.withLock {
            if (closed) return
            val seq = nextSequence()
            val nonce = ByteArray(DeviceConnectLimits.FRAME_NONCE_BYTES)
            noncePrefix.copyInto(nonce)
            for (i in 0 until 8) nonce[4 + i] = (seq ushr (56 - 8 * i)).toByte()
            val header = DeviceFrameHeader(
                DeviceConnectLimits.PROTOCOL_VERSION,
                type,
                DeviceFrameCodec.FLAG_ENCRYPTED,
                seq,
            )
            val ciphertext = DeviceAesGcm.seal(senderKey, nonce, header.aad(hash), payload)
            channel.send(DeviceFrameCodec.encode(type, seq, ciphertext, nonce))
        }
    }

    /**
     * Seals a control frame as soon as the key exchange finished. Only the hello commitment exchange runs
     * before the keys exist; after that the connection never writes a readable frame.
     */
    private suspend fun sendControl(type: DeviceMessageType, payload: String) {
        if (keys != null && transcript != null) {
            sendEncryptedBytes(type, payload.encodeToByteArray())
        } else {
            sendPlainText(type, payload)
        }
    }

    private fun nextSequence(): Long {
        sequence++
        if (sequence > 0xffff_ffffL) throw IllegalStateException("frame sequence exhausted")
        return sequence
    }

    private fun fitsFrame(payloadSize: Int): Boolean =
        payloadSize + 7 + DeviceConnectLimits.FRAME_NONCE_BYTES + DeviceConnectLimits.GCM_TAG_BYTES <= DeviceConnectLimits.MAX_FRAME_BYTES

    private fun fail(code: DeviceConnectErrorCode, message: String) {
        if (closed) return
        listener.onFailure(DeviceConnectError(code, message))
        scope.launch {
            runCatching {
                sendControl(
                    DeviceMessageType.ERROR,
                    DeviceWireJson.json.encodeToString(ErrorPayload.serializer(), ErrorPayload(code.name, message)),
                )
            }
        }
        closeInternal()
    }

    private fun closeInternal() {
        if (closed) return
        closed = true
        handshake.closed()
        receiveJob?.cancel()
        watchdogJob?.cancel()
        keyPair?.destroy()
        scope.launch { runCatching { channel.close() } }
        closedSignal.complete(Unit)
        listener.onClosed()
    }

    private class ChunkAssembly(
        val startedAtMs: Long,
        val count: Int,
        val totalBytes: Int,
    ) {
        val parts = mutableMapOf<Int, ByteArray>()
    }

    private companion object {
        val MAX_CHUNKS = DeviceConnectLimits.MAX_PAYLOAD_BYTES / DeviceConnectLimits.CHUNK_BYTES + 1
    }
}
