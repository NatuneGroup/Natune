package moe.rukamori.archivetune.shared.domain.deviceconnect

/**
 * The fixed wire and policy limits of Device Connect v1. They are constants rather than settings so two
 * builds cannot disagree about a frame, a chunk or a pairing deadline.
 */
object DeviceConnectLimits {
    const val PROTOCOL_VERSION = 1
    const val PROTOCOL_LABEL = "natune-connect/1"

    /** Bonjour service type without the domain suffix, as Android NSD spells it. */
    const val SERVICE_TYPE = "_natune-connect._tcp."
    const val SERVICE_DOMAIN = "local."
    const val SERVICE_TYPE_FULL = SERVICE_TYPE + SERVICE_DOMAIN
    const val WS_ROUTE = "/connect"

    const val MAX_FRAME_BYTES = 65_536
    const val CHUNK_BYTES = 24_576
    const val MAX_PAYLOAD_BYTES = 8_388_608
    const val MAX_QUEUE_ENTRIES = 5_000
    const val TRANSFER_TIMEOUT_MS = 10_000L

    const val PAIRING_TIMEOUT_MS = 300_000L
    const val HANDSHAKE_TIMEOUT_MS = 30_000L
    const val HEARTBEAT_INTERVAL_MS = 15_000L
    const val HEARTBEAT_TIMEOUT_MS = 45_000L

    const val MAX_PAIRED_DEVICES = 8
    const val MAX_CONNECTIONS = 4
    const val MAX_PAIRING_ATTEMPTS = 3
    const val REPLAY_WINDOW = 64

    const val MAX_DEVICE_NAME = 48
    const val MAX_DEVICE_ID = 64
    const val MAX_MODEL = 48
    const val MAX_APP_VERSION = 32

    const val NONCE_BYTES = 16
    const val FRAME_NONCE_BYTES = 12
    const val PUBLIC_KEY_BYTES = 65
    const val COMMITMENT_BYTES = 32
    const val PAIR_SECRET_BYTES = 32
    const val TRAFFIC_KEY_BYTES = 32
    const val GCM_TAG_BYTES = 16
    const val SAS_DIGITS = 6
    const val SAS_MODULUS = 1_000_000
}

data class DeviceEndpoint(val host: String, val port: Int) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..65_535) { "port must be between 1 and 65535" }
    }

    /** A URL host without brackets; IPv6 literals are bracketed again by [webSocketUrl]. */
    fun webSocketUrl(): String = "ws://${if (host.contains(':')) "[$host]" else host}:$port${DeviceConnectLimits.WS_ROUTE}"

    override fun toString(): String = "${if (host.contains(':')) "[$host]" else host}:$port"
}

object DeviceConnectHosts {
    /** Loopback is accepted only by the in-process tests and the manual-IP path. */
    fun isLoopback(host: String): Boolean {
        val value = host.removePrefix("[").removeSuffix("]").lowercase()
        return value == "127.0.0.1" || value == "::1" || value == "localhost"
    }

    /**
     * True for a private IPv4 address, a loopback address, or an IPv6 ULA/link-local address. Public
     * addresses are refused so discovery and manual entry cannot silently leave the LAN.
     */
    fun isPrivateOrLocal(host: String): Boolean {
        val value = host.removePrefix("[").removeSuffix("]").lowercase().trimEnd('.')
        if (isLoopback(value)) return true
        if (value.contains(':')) {
            if (value == "::1") return true
            return value.startsWith("fc") || value.startsWith("fd") ||
                value.startsWith("fe8") || value.startsWith("fe9") || value.startsWith("fea") || value.startsWith("feb")
        }
        if (value.endsWith(".local")) return true
        val parts = value.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { part -> part.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        return when {
            octets[0] == 10 -> true
            octets[0] == 127 -> true
            octets[0] == 172 && octets[1] in 16..31 -> true
            octets[0] == 192 && octets[1] == 168 -> true
            octets[0] == 169 && octets[1] == 254 -> true
            // RFC 6598 shared address space: Tailscale and several VPNs use 100.64.0.0/10.
            octets[0] == 100 && octets[1] in 64..127 -> true
            else -> false
        }
    }

    /** IPv4:port, [IPv6]:port, or a `.local` host name. */
    fun parseManualEndpoint(text: String): DeviceEndpoint? {
        val value = text.trim()
        if (value.isEmpty() || value.length > 128) return null
        val host: String
        val portText: String
        if (value.startsWith("[")) {
            val close = value.indexOf(']')
            if (close <= 1 || close + 1 >= value.length || value[close + 1] != ':') return null
            host = value.substring(1, close)
            portText = value.substring(close + 2)
        } else {
            val colon = value.lastIndexOf(':')
            if (colon <= 0 || colon == value.length - 1) return null
            host = value.substring(0, colon)
            portText = value.substring(colon + 1)
        }
        if (!isPrivateOrLocal(host)) return null
        val port = portText.toIntOrNull() ?: return null
        if (port !in 1..65_535) return null
        return DeviceEndpoint(host.removePrefix("[").removeSuffix("]"), port)
    }

    fun normalizeDeviceName(value: String): String =
        value.filter { it.code >= 0x20 && it.code != 0x7f }.trim().take(DeviceConnectLimits.MAX_DEVICE_NAME)
            .ifBlank { "Natune" }

    fun isValidDeviceId(value: String): Boolean =
        value.length in 16..DeviceConnectLimits.MAX_DEVICE_ID &&
            value.all { it in '0'..'9' || it in 'a'..'f' }
}
