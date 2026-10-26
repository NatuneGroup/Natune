/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.data.bytesource

/**
 * The provider account a byte stream was resolved for. A null [accountId] means genuinely anonymous access, and a
 * non-null one is always the provider's own user id, never a feed or chat id. [accountScope] names the single stored
 * credential entry that admitted that identity, such as a personal slot or one playback pool entry. Two pool entries
 * can hold the same real user, so cookies are matched by scope as well as identity. A scope never exists without an
 * identity.
 */
data class ByteSourceAccount(
    val provider: String,
    val accountId: String?,
    val accountScope: String? = null,
) {
    init {
        require(accountScope == null || (accountId != null && accountScope.isNotBlank())) {
            "an account scope needs an admitted identity and must not be blank"
        }
    }
}

/** A scheme, host and port triple. Only http and https are valid origins. */
data class StreamOrigin(
    val scheme: String,
    val host: String,
    val port: Int,
) {
    companion object {
        private const val HTTP_DEFAULT_PORT = 80
        private const val HTTPS_DEFAULT_PORT = 443

        /**
         * Parses an absolute http(s) URL into its origin, or returns null when the input is malformed,
         * uses another scheme, or embeds userinfo (credentials must never ride in a URL).
         */
        fun parse(url: String): StreamOrigin? {
            val schemeEnd = url.indexOf("://")
            if (schemeEnd <= 0) return null
            val scheme = url.substring(0, schemeEnd).lowercase()
            val defaultPort =
                when (scheme) {
                    "http" -> HTTP_DEFAULT_PORT
                    "https" -> HTTPS_DEFAULT_PORT
                    else -> return null
                }
            val afterScheme = url.substring(schemeEnd + 3)
            val authorityEnd = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) afterScheme.length else it }
            val authority = afterScheme.substring(0, authorityEnd)
            if (authority.isEmpty() || authority.contains('@')) return null
            val (hostPart, portPart) = splitHostPort(authority) ?: return null
            val host = hostPart.lowercase()
            if (host.isEmpty()) return null
            val port =
                if (portPart == null) {
                    defaultPort
                } else {
                    portPart.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                }
            return StreamOrigin(scheme, host, port)
        }

        private fun splitHostPort(authority: String): Pair<String, String?>? {
            if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                if (close < 0) return null
                val host = authority.substring(0, close + 1)
                val rest = authority.substring(close + 1)
                return when {
                    rest.isEmpty() -> host to null
                    rest.startsWith(":") -> host to rest.substring(1)
                    else -> null
                }
            }
            val colon = authority.indexOf(':')
            if (colon < 0) return authority to null
            if (authority.indexOf(':', colon + 1) >= 0) return null
            return authority.substring(0, colon) to authority.substring(colon + 1)
        }
    }
}

/**
 * The authorisation for reading one byte stream. Every read must stay inside [permittedOrigins],
 * and the lease stops being valid once [expiresAtEpochMs] has passed.
 */
data class ByteSourceLease(
    val account: ByteSourceAccount,
    val primaryOrigin: StreamOrigin,
    val permittedOrigins: Set<StreamOrigin>,
    val cacheKey: String,
    val expiresAtEpochMs: Long?,
    val lengthBytes: Long?,
) {
    init {
        require(primaryOrigin in permittedOrigins) { "permittedOrigins must contain primaryOrigin" }
        require(lengthBytes == null || lengthBytes >= 0) { "lengthBytes must be null or non-negative" }
    }
}

/** A seekable byte stream addressed by 64-bit positions. */
interface RandomAccessByteSource {
    val lease: ByteSourceLease

    /**
     * Reads up to [length] bytes starting at 64-bit [position] into [buffer] at [offset].
     * Returns the byte count (> 0); 0 when no bytes are available yet (caller retries later);
     * or [END_OF_STREAM] when [position] is at or beyond the end. Never returns bytes from before a cancellation.
     */
    suspend fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int

    companion object {
        const val END_OF_STREAM: Int = -1
    }
}

/** Failure reading or interpreting a byte source (bad lease, invalid padding, malformed input). */
class ByteSourceException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
