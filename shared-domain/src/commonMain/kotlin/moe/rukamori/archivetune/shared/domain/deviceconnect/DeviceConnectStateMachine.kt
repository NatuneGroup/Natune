package moe.rukamori.archivetune.shared.domain.deviceconnect

/**
 * A 64-frame sliding replay window. WebSocket delivery is ordered, but a valid frame replayed by a local
 * attacker must still be refused; ACKs never move the window, only accepted frames do.
 */
class DeviceReplayWindow(private val size: Int = DeviceConnectLimits.REPLAY_WINDOW) {
    init {
        require(size in 8..64) { "replay window must fit one Long" }
    }

    private var highest = 0L
    private var bits = 0L

    /** True when [seq] is new, false for a duplicate or a frame older than the window. */
    fun accept(seq: Long): Boolean {
        if (seq <= 0L) return false
        if (seq > highest) {
            val shift = seq - highest
            bits = if (shift >= size) 1L else (bits shl shift.toInt()) or 1L
            highest = seq
            return true
        }
        val distance = highest - seq
        if (distance >= size) return false
        val mask = 1L shl distance.toInt()
        if (bits and mask != 0L) return false
        bits = bits or mask
        return true
    }

    fun reset() {
        highest = 0
        bits = 0
    }
}

enum class DeviceHandshakePhase {
    AWAITING_COMMIT,
    AWAITING_HELLO,
    HELLO_RECEIVED,
    AWAITING_PAIR_APPROVAL,
    AWAITING_PAIR_COMMIT,
    AUTHENTICATED,
    CLOSED,
}

/**
 * The plaintext/encrypted boundary and pairing deadline for one connection. Crypto stays in
 * [DeviceConnectPairing]; this type only owns the phase, so an out-of-order or pre-authentication
 * message cannot reach the session handler.
 */
class DeviceHandshakeState(
    val role: DeviceConnectRole,
    private val pairingTimeoutMs: Long = DeviceConnectLimits.PAIRING_TIMEOUT_MS,
) {
    var phase: DeviceHandshakePhase = DeviceHandshakePhase.AWAITING_COMMIT
        private set

    var pairingStartedAtMs: Long? = null
        private set

    var peerDeviceId: String? = null
        private set

    fun onHelloCommit(): Boolean {
        if (phase != DeviceHandshakePhase.AWAITING_COMMIT) return false
        phase = DeviceHandshakePhase.AWAITING_HELLO
        return true
    }

    fun onHello(peerId: String): Boolean {
        if (phase != DeviceHandshakePhase.AWAITING_HELLO || peerDeviceId != null) return false
        peerDeviceId = peerId
        phase = DeviceHandshakePhase.HELLO_RECEIVED
        return true
    }

    fun awaitingPairApproval(nowMs: Long): Boolean {
        if (phase != DeviceHandshakePhase.HELLO_RECEIVED) return false
        pairingStartedAtMs = nowMs
        phase = DeviceHandshakePhase.AWAITING_PAIR_APPROVAL
        return true
    }

    fun awaitingPairCommit(): Boolean {
        if (phase != DeviceHandshakePhase.AWAITING_PAIR_APPROVAL) return false
        phase = DeviceHandshakePhase.AWAITING_PAIR_COMMIT
        return true
    }

    fun authenticated() {
        phase = DeviceHandshakePhase.AUTHENTICATED
        pairingStartedAtMs = null
    }

    fun closed() {
        phase = DeviceHandshakePhase.CLOSED
    }

    /**
     * Only the two commitment messages that precede the key exchange may travel in the clear. As soon as
     * the hello has been exchanged every pairing step, the authentication result, the device info and all
     * session traffic must arrive sealed, so a peer cannot skip the exchange and claim a state it never
     * proved. Anything else unencrypted closes the connection.
     */
    fun allowsPlainText(type: DeviceMessageType): Boolean = when (phase) {
        DeviceHandshakePhase.AWAITING_COMMIT ->
            type == DeviceMessageType.HELLO_COMMIT || type == DeviceMessageType.ERROR
        DeviceHandshakePhase.AWAITING_HELLO ->
            type == DeviceMessageType.HELLO || type == DeviceMessageType.HELLO_COMMIT || type == DeviceMessageType.ERROR
        else -> false
    }

    /**
     * True when a sealed frame of [type] is expected now. The pre-authentication set is exactly the pairing
     * step that belongs to this side of this phase; session, command and device-info frames exist only after
     * authentication.
     */
    fun allowsEncrypted(type: DeviceMessageType): Boolean = when (phase) {
        DeviceHandshakePhase.AWAITING_COMMIT,
        DeviceHandshakePhase.AWAITING_HELLO,
        DeviceHandshakePhase.CLOSED,
        -> false
        DeviceHandshakePhase.HELLO_RECEIVED ->
            type == DeviceMessageType.AUTH_PROOF || type == DeviceMessageType.ERROR ||
                (type == DeviceMessageType.PAIR_REQUEST && role == DeviceConnectRole.RESPONDER)
        DeviceHandshakePhase.AWAITING_PAIR_APPROVAL ->
            type == DeviceMessageType.PAIR_REJECT || type == DeviceMessageType.ERROR ||
                (type == DeviceMessageType.PAIR_APPROVE && role == DeviceConnectRole.INITIATOR)
        DeviceHandshakePhase.AWAITING_PAIR_COMMIT ->
            type == DeviceMessageType.PAIR_REJECT || type == DeviceMessageType.ERROR ||
                (type == DeviceMessageType.AUTH_RESULT && role == DeviceConnectRole.INITIATOR) ||
                (type == DeviceMessageType.PAIR_COMMIT && role == DeviceConnectRole.RESPONDER)
        DeviceHandshakePhase.AUTHENTICATED ->
            type == DeviceMessageType.PLAYBACK || type == DeviceMessageType.PLAYBACK_CHUNK ||
                type == DeviceMessageType.PING || type == DeviceMessageType.PONG ||
                type == DeviceMessageType.BYE || type == DeviceMessageType.DEVICE_INFO ||
                type == DeviceMessageType.ERROR
    }

    /** A reconnect proof follows the hello and precedes every other pairing step. */
    fun canAcceptAuthProof(): Boolean = phase == DeviceHandshakePhase.HELLO_RECEIVED

    fun canAcceptPairRequest(): Boolean = phase == DeviceHandshakePhase.HELLO_RECEIVED

    /** Only the initiator, and only after it asked for approval. */
    fun canAcceptPairApprove(): Boolean = phase == DeviceHandshakePhase.AWAITING_PAIR_APPROVAL

    /** Only the responder, and only after the user approved its pairing prompt. */
    fun canAcceptPairCommit(): Boolean = phase == DeviceHandshakePhase.AWAITING_PAIR_COMMIT

    /** Only the initiator, and only after it sent the pair commitment. */
    fun canAcceptAuthResult(): Boolean = phase == DeviceHandshakePhase.AWAITING_PAIR_COMMIT

    /** The only phases from which a verified proof may authenticate the connection. */
    fun canAuthenticate(): Boolean =
        phase == DeviceHandshakePhase.HELLO_RECEIVED || phase == DeviceHandshakePhase.AWAITING_PAIR_COMMIT

    fun pairingExpired(nowMs: Long, startedAtMs: Long? = pairingStartedAtMs): Boolean {
        val started = startedAtMs ?: return false
        return nowMs - started > pairingTimeoutMs
    }
}
