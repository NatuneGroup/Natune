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
     * Handshake and pairing messages are plaintext until the pair proof authenticates the connection; the
     * payloads themselves are commitment-, ECDH- and proof-protected. Session traffic is never plaintext.
     */
    fun allowsPlainText(type: DeviceMessageType): Boolean = when (phase) {
        DeviceHandshakePhase.AWAITING_COMMIT ->
            type == DeviceMessageType.HELLO_COMMIT || type == DeviceMessageType.ERROR
        DeviceHandshakePhase.AWAITING_HELLO ->
            type == DeviceMessageType.HELLO || type == DeviceMessageType.HELLO_COMMIT || type == DeviceMessageType.ERROR
        DeviceHandshakePhase.HELLO_RECEIVED,
        DeviceHandshakePhase.AWAITING_PAIR_APPROVAL,
        DeviceHandshakePhase.AWAITING_PAIR_COMMIT,
        ->
            type == DeviceMessageType.AUTH_PROOF || type == DeviceMessageType.PAIR_REQUEST ||
                type == DeviceMessageType.PAIR_APPROVE || type == DeviceMessageType.PAIR_COMMIT ||
                type == DeviceMessageType.PAIR_REJECT || type == DeviceMessageType.AUTH_RESULT ||
                type == DeviceMessageType.DEVICE_INFO || type == DeviceMessageType.ERROR
        DeviceHandshakePhase.AUTHENTICATED,
        DeviceHandshakePhase.CLOSED,
        -> false
    }

    fun pairingExpired(nowMs: Long, startedAtMs: Long? = pairingStartedAtMs): Boolean {
        val started = startedAtMs ?: return false
        return nowMs - started > pairingTimeoutMs
    }
}
